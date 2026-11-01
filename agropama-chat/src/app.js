'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const express = require('express');
const cookieParser = require('cookie-parser');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const multer = require('multer');
const { Server } = require('socket.io');
const { openDatabase, transaction } = require('./db');

const COOKIE = 'agropama_token';
const MAX_FILE_SIZE = 15 * 1024 * 1024; // 15 Mo
const PAGE_SIZE = 50;

function createApp(options = {}) {
  const dataDir = options.dataDir || path.join(__dirname, '..', 'data');
  const uploadDir = path.join(dataDir, 'uploads');
  fs.mkdirSync(uploadDir, { recursive: true });

  const db = openDatabase(options.dbFile || path.join(dataDir, 'agropama.db'));
  const jwtSecret = options.jwtSecret || loadOrCreateSecret(dataDir);
  const secureCookies = Boolean(options.secureCookies);

  seedAdmin(db, options.admin);

  // ---------------------------------------------------------------- helpers
  const q = {
    userById: db.prepare('SELECT * FROM users WHERE id = ?'),
    userByEmail: db.prepare('SELECT * FROM users WHERE email = ?'),
    isMember: db.prepare(
      'SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?'
    ),
    memberIds: db.prepare('SELECT user_id FROM conversation_members WHERE conversation_id = ?'),
    touchUser: db.prepare("UPDATE users SET last_seen = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?"),
  };

  const online = new Map(); // userId -> nombre de sockets connectés

  function publicUser(u) {
    if (!u) return null;
    return {
      id: u.id,
      name: u.name,
      email: u.email,
      phone: u.phone,
      department: u.department,
      jobTitle: u.job_title,
      role: u.role,
      status: u.status,
      createdAt: u.created_at,
      lastSeen: u.last_seen,
      online: online.has(u.id),
    };
  }

  function sign(user) {
    return jwt.sign({ sub: user.id }, jwtSecret, { expiresIn: '7d' });
  }

  function userFromToken(token) {
    if (!token) return null;
    try {
      const payload = jwt.verify(token, jwtSecret);
      const user = q.userById.get(payload.sub);
      return user && user.status === 'approved' ? user : null;
    } catch {
      return null;
    }
  }

  function memberIdsOf(conversationId) {
    return q.memberIds.all(conversationId).map((r) => r.user_id);
  }

  function serializeMessage(m) {
    return {
      id: m.id,
      conversationId: m.conversation_id,
      senderId: m.sender_id,
      senderName: m.sender_name,
      body: m.deleted ? null : m.body,
      deleted: Boolean(m.deleted),
      file:
        m.file_path && !m.deleted
          ? { name: m.file_name, type: m.file_type, size: m.file_size, url: `/api/files/${m.id}` }
          : null,
      createdAt: m.created_at,
    };
  }

  const messageById = db.prepare(`
    SELECT m.*, u.name AS sender_name FROM messages m
    LEFT JOIN users u ON u.id = m.sender_id WHERE m.id = ?`);

  function conversationFor(conversationId, userId) {
    const c = db
      .prepare(
        `SELECT c.*, cm.last_read_id, cm.is_group_admin FROM conversations c
         JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.user_id = ?
         WHERE c.id = ?`
      )
      .get(userId, conversationId);
    if (!c) return null;
    const members = db
      .prepare(
        `SELECT u.*, cm.is_group_admin, cm.last_read_id FROM conversation_members cm
         JOIN users u ON u.id = cm.user_id WHERE cm.conversation_id = ? ORDER BY u.name`
      )
      .all(conversationId);
    const last = db
      .prepare(
        `SELECT m.*, u.name AS sender_name FROM messages m LEFT JOIN users u ON u.id = m.sender_id
         WHERE m.conversation_id = ? ORDER BY m.id DESC LIMIT 1`
      )
      .get(conversationId);
    const unread = db
      .prepare(
        `SELECT COUNT(*) AS n FROM messages
         WHERE conversation_id = ? AND id > ? AND (sender_id IS NULL OR sender_id != ?)`
      )
      .get(conversationId, c.last_read_id, userId).n;

    let title = c.name;
    let peer = null;
    if (c.type === 'direct') {
      const other = members.find((m) => m.id !== userId) || members[0];
      peer = publicUser(other);
      title = other ? other.name : 'Conversation';
    }
    return {
      id: c.id,
      type: c.type,
      title,
      peer,
      isGroupAdmin: Boolean(c.is_group_admin),
      createdAt: c.created_at,
      members: members.map((m) => ({
        ...publicUser(m),
        isGroupAdmin: Boolean(m.is_group_admin),
        lastReadId: m.last_read_id,
      })),
      lastMessage: last ? serializeMessage(last) : null,
      unread,
    };
  }

  function emitToUsers(userIds, event, payloadFn) {
    for (const id of userIds) {
      const payload = typeof payloadFn === 'function' ? payloadFn(id) : payloadFn;
      io.to(`user:${id}`).emit(event, payload);
    }
  }

  function emitToAdmins(event, payload) {
    io.to('admins').emit(event, payload);
  }

  function broadcastConversation(conversationId) {
    emitToUsers(memberIdsOf(conversationId), 'conversation:update', (uid) =>
      conversationFor(conversationId, uid)
    );
  }

  function kickUser(userId, reason) {
    io.to(`user:${userId}`).emit('session:revoked', { reason });
    io.in(`user:${userId}`).disconnectSockets(true);
  }

  // ---------------------------------------------------------------- express
  const app = express();
  app.disable('x-powered-by');
  // Derrière un hébergeur (Render, Railway, Nginx…) : utiliser la vraie adresse IP du client.
  if (options.trustProxy) app.set('trust proxy', 1);
  app.use(express.json({ limit: '200kb' }));
  app.use(cookieParser());
  app.use((req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'same-origin');
    next();
  });
  app.use(express.static(path.join(__dirname, '..', 'public')));

  // Utilisé par l'application Android pour vérifier l'adresse du serveur.
  app.get('/api/health', (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.json({ app: 'agropama-chat', ok: true });
  });

  const wrap = (fn) => (req, res, next) => {
    try {
      fn(req, res, next);
    } catch (err) {
      next(err);
    }
  };

  function requireAuth(req, res, next) {
    const user = userFromToken(req.cookies[COOKIE]);
    if (!user) return res.status(401).json({ error: 'Non authentifié.' });
    req.user = user;
    next();
  }

  function requireAdmin(req, res, next) {
    if (req.user.role !== 'admin') return res.status(403).json({ error: 'Réservé aux administrateurs.' });
    next();
  }

  function requireMember(req, res, next) {
    const id = Number(req.params.id);
    if (!Number.isInteger(id) || !q.isMember.get(id, req.user.id)) {
      return res.status(404).json({ error: 'Conversation introuvable.' });
    }
    req.conversationId = id;
    next();
  }

  const clean = (v, max = 120) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  // Limiteur simple contre le bourrage d'identifiants (par IP).
  const attempts = new Map();
  function rateLimit(req, res, next) {
    const key = req.ip;
    const now = Date.now();
    const entry = attempts.get(key) || { count: 0, reset: now + 15 * 60 * 1000 };
    if (now > entry.reset) Object.assign(entry, { count: 0, reset: now + 15 * 60 * 1000 });
    entry.count += 1;
    attempts.set(key, entry);
    if (entry.count > (options.rateLimit ?? 30)) {
      return res.status(429).json({ error: 'Trop de tentatives. Réessayez plus tard.' });
    }
    next();
  }

  // ---- Authentification
  app.post('/api/auth/register', rateLimit, wrap((req, res) => {
    const name = clean(req.body.name, 80);
    const email = clean(req.body.email, 120).toLowerCase();
    const phone = clean(req.body.phone, 30);
    const department = clean(req.body.department, 80);
    const jobTitle = clean(req.body.jobTitle, 80);
    const password = typeof req.body.password === 'string' ? req.body.password : '';

    if (name.length < 2) return res.status(400).json({ error: 'Le nom est requis.' });
    if (!EMAIL_RE.test(email)) return res.status(400).json({ error: 'Adresse courriel invalide.' });
    if (password.length < 8) {
      return res.status(400).json({ error: 'Le mot de passe doit contenir au moins 8 caractères.' });
    }
    if (q.userByEmail.get(email)) {
      return res.status(409).json({ error: 'Un compte existe déjà avec ce courriel.' });
    }
    const hash = bcrypt.hashSync(password, 10);
    const info = db
      .prepare(
        `INSERT INTO users (name, email, phone, department, job_title, password_hash)
         VALUES (?, ?, ?, ?, ?, ?)`
      )
      .run(name, email, phone || null, department || null, jobTitle || null, hash);
    const user = q.userById.get(info.lastInsertRowid);
    emitToAdmins('admin:registration', publicUser(user));
    res.status(201).json({
      message: 'Inscription reçue. Un administrateur doit valider votre compte avant que vous puissiez vous connecter.',
    });
  }));

  app.post('/api/auth/login', rateLimit, wrap((req, res) => {
    const email = clean(req.body.email, 120).toLowerCase();
    const password = typeof req.body.password === 'string' ? req.body.password : '';
    const user = q.userByEmail.get(email);
    if (!user || !bcrypt.compareSync(password, user.password_hash)) {
      return res.status(401).json({ error: 'Courriel ou mot de passe incorrect.' });
    }
    const blocked = {
      pending: 'Votre compte est en attente de validation par un administrateur.',
      rejected: "Votre demande d'inscription a été refusée. Contactez l'administration.",
      suspended: "Votre compte est suspendu. Contactez l'administration.",
    };
    if (user.status !== 'approved') {
      return res.status(403).json({ error: blocked[user.status], status: user.status });
    }
    res.cookie(COOKIE, sign(user), {
      httpOnly: true,
      sameSite: 'strict',
      secure: secureCookies,
      maxAge: 7 * 24 * 3600 * 1000,
    });
    res.json({ user: publicUser(user) });
  }));

  app.post('/api/auth/logout', (req, res) => {
    res.clearCookie(COOKIE);
    res.json({ ok: true });
  });

  app.get('/api/me', requireAuth, (req, res) => res.json({ user: publicUser(req.user) }));

  app.patch('/api/me', requireAuth, wrap((req, res) => {
    const u = req.user;
    const name = clean(req.body.name ?? u.name, 80);
    if (name.length < 2) return res.status(400).json({ error: 'Le nom est requis.' });
    const phone = clean(req.body.phone ?? u.phone ?? '', 30) || null;
    const department = clean(req.body.department ?? u.department ?? '', 80) || null;
    const jobTitle = clean(req.body.jobTitle ?? u.job_title ?? '', 80) || null;
    let hash = u.password_hash;
    if (req.body.newPassword) {
      if (!bcrypt.compareSync(String(req.body.currentPassword || ''), u.password_hash)) {
        return res.status(400).json({ error: 'Mot de passe actuel incorrect.' });
      }
      if (String(req.body.newPassword).length < 8) {
        return res.status(400).json({ error: 'Le nouveau mot de passe doit contenir au moins 8 caractères.' });
      }
      hash = bcrypt.hashSync(String(req.body.newPassword), 10);
    }
    db.prepare(
      'UPDATE users SET name = ?, phone = ?, department = ?, job_title = ?, password_hash = ? WHERE id = ?'
    ).run(name, phone, department, jobTitle, hash, u.id);
    res.json({ user: publicUser(q.userById.get(u.id)) });
  }));

  // ---- Annuaire des employés (comptes validés uniquement)
  app.get('/api/users', requireAuth, wrap((req, res) => {
    const users = db
      .prepare("SELECT * FROM users WHERE status = 'approved' AND id != ? ORDER BY name")
      .all(req.user.id);
    res.json({ users: users.map(publicUser) });
  }));

  // ---- Conversations
  app.get('/api/conversations', requireAuth, wrap((req, res) => {
    const ids = db
      .prepare('SELECT conversation_id AS id FROM conversation_members WHERE user_id = ?')
      .all(req.user.id);
    const list = ids
      .map((r) => conversationFor(r.id, req.user.id))
      .sort((a, b) => {
        const ta = a.lastMessage ? a.lastMessage.createdAt : a.createdAt;
        const tb = b.lastMessage ? b.lastMessage.createdAt : b.createdAt;
        return tb.localeCompare(ta);
      });
    res.json({ conversations: list });
  }));

  app.post('/api/conversations/direct', requireAuth, wrap((req, res) => {
    const otherId = Number(req.body.userId);
    const other = q.userById.get(otherId);
    if (!other || other.status !== 'approved' || other.id === req.user.id) {
      return res.status(400).json({ error: 'Employé introuvable.' });
    }
    const key = [req.user.id, other.id].sort((a, b) => a - b).join(':');
    let conv = db.prepare('SELECT id FROM conversations WHERE direct_key = ?').get(key);
    if (!conv) {
      conv = transaction(db, () => {
        const info = db
          .prepare("INSERT INTO conversations (type, direct_key, created_by) VALUES ('direct', ?, ?)")
          .run(key, req.user.id);
        const add = db.prepare('INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)');
        add.run(info.lastInsertRowid, req.user.id);
        add.run(info.lastInsertRowid, other.id);
        return { id: Number(info.lastInsertRowid) };
      });
    }
    res.json({ conversation: conversationFor(conv.id, req.user.id) });
  }));

  app.post('/api/conversations/group', requireAuth, wrap((req, res) => {
    const name = clean(req.body.name, 80);
    if (name.length < 2) return res.status(400).json({ error: 'Le nom du groupe est requis.' });
    const ids = [...new Set((req.body.memberIds || []).map(Number))].filter(
      (id) => id !== req.user.id
    );
    const valid = ids.filter((id) => {
      const u = q.userById.get(id);
      return u && u.status === 'approved';
    });
    if (valid.length === 0) return res.status(400).json({ error: 'Ajoutez au moins un membre.' });

    const convId = transaction(db, () => {
      const info = db
        .prepare("INSERT INTO conversations (type, name, created_by) VALUES ('group', ?, ?)")
        .run(name, req.user.id);
      const id = Number(info.lastInsertRowid);
      db.prepare(
        'INSERT INTO conversation_members (conversation_id, user_id, is_group_admin) VALUES (?, ?, 1)'
      ).run(id, req.user.id);
      const add = db.prepare('INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)');
      valid.forEach((uid) => add.run(id, uid));
      insertSystemMessage(id, `${req.user.name} a créé le groupe « ${name} »`);
      return id;
    });
    broadcastConversation(convId);
    res.status(201).json({ conversation: conversationFor(convId, req.user.id) });
  }));

  function canManageGroup(req, conversationId) {
    const row = db
      .prepare(
        `SELECT c.type, cm.is_group_admin FROM conversations c
         JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.user_id = ?
         WHERE c.id = ?`
      )
      .get(req.user.id, conversationId);
    return row && row.type === 'group' && (row.is_group_admin || req.user.role === 'admin');
  }

  function insertSystemMessage(conversationId, text) {
    const info = db
      .prepare('INSERT INTO messages (conversation_id, sender_id, body) VALUES (?, NULL, ?)')
      .run(conversationId, text);
    return messageById.get(info.lastInsertRowid);
  }

  app.post('/api/conversations/:id/members', requireAuth, requireMember, wrap((req, res) => {
    if (!canManageGroup(req, req.conversationId)) {
      return res.status(403).json({ error: 'Seuls les administrateurs du groupe peuvent ajouter des membres.' });
    }
    const added = [];
    for (const raw of req.body.memberIds || []) {
      const u = q.userById.get(Number(raw));
      if (!u || u.status !== 'approved' || q.isMember.get(req.conversationId, u.id)) continue;
      db.prepare('INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)').run(
        req.conversationId,
        u.id
      );
      added.push(u.name);
    }
    if (added.length) {
      const msg = insertSystemMessage(req.conversationId, `${req.user.name} a ajouté ${added.join(', ')}`);
      emitToUsers(memberIdsOf(req.conversationId), 'message:new', serializeMessage(msg));
      broadcastConversation(req.conversationId);
    }
    res.json({ conversation: conversationFor(req.conversationId, req.user.id) });
  }));

  app.delete('/api/conversations/:id/members/:userId', requireAuth, requireMember, wrap((req, res) => {
    const targetId = Number(req.params.userId);
    const self = targetId === req.user.id;
    if (!self && !canManageGroup(req, req.conversationId)) {
      return res.status(403).json({ error: 'Action non autorisée.' });
    }
    const conv = db.prepare('SELECT type FROM conversations WHERE id = ?').get(req.conversationId);
    if (conv.type !== 'group') return res.status(400).json({ error: 'Action réservée aux groupes.' });
    const target = q.userById.get(targetId);
    if (!target || !q.isMember.get(req.conversationId, targetId)) {
      return res.status(404).json({ error: 'Membre introuvable.' });
    }
    const before = memberIdsOf(req.conversationId);
    db.prepare('DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?').run(
      req.conversationId,
      targetId
    );
    const text = self ? `${target.name} a quitté le groupe` : `${req.user.name} a retiré ${target.name}`;
    const msg = insertSystemMessage(req.conversationId, text);
    emitToUsers(memberIdsOf(req.conversationId), 'message:new', serializeMessage(msg));
    broadcastConversation(req.conversationId);
    emitToUsers([targetId], 'conversation:removed', { id: req.conversationId });
    if (before.length <= 1) db.prepare('DELETE FROM conversations WHERE id = ?').run(req.conversationId);
    res.json({ ok: true });
  }));

  // ---- Messages
  app.get('/api/conversations/:id/messages', requireAuth, requireMember, wrap((req, res) => {
    const before = Number(req.query.before) || Number.MAX_SAFE_INTEGER;
    const rows = db
      .prepare(
        `SELECT m.*, u.name AS sender_name FROM messages m LEFT JOIN users u ON u.id = m.sender_id
         WHERE m.conversation_id = ? AND m.id < ? ORDER BY m.id DESC LIMIT ?`
      )
      .all(req.conversationId, before, PAGE_SIZE);
    res.json({ messages: rows.reverse().map(serializeMessage), hasMore: rows.length === PAGE_SIZE });
  }));

  function postMessage(req, res, fields) {
    const info = db
      .prepare(
        `INSERT INTO messages (conversation_id, sender_id, body, file_path, file_name, file_type, file_size)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        req.conversationId,
        req.user.id,
        fields.body || null,
        fields.filePath || null,
        fields.fileName || null,
        fields.fileType || null,
        fields.fileSize || null
      );
    db.prepare(
      'UPDATE conversation_members SET last_read_id = ? WHERE conversation_id = ? AND user_id = ?'
    ).run(info.lastInsertRowid, req.conversationId, req.user.id);
    const msg = serializeMessage(messageById.get(info.lastInsertRowid));
    const members = memberIdsOf(req.conversationId);
    emitToUsers(members, 'message:new', msg);
    broadcastConversation(req.conversationId);
    res.status(201).json({ message: msg });
  }

  app.post('/api/conversations/:id/messages', requireAuth, requireMember, wrap((req, res) => {
    const body = typeof req.body.body === 'string' ? req.body.body.trim().slice(0, 5000) : '';
    if (!body) return res.status(400).json({ error: 'Message vide.' });
    postMessage(req, res, { body });
  }));

  const upload = multer({
    storage: multer.diskStorage({
      destination: uploadDir,
      filename: (req, file, cb) => cb(null, crypto.randomUUID()),
    }),
    limits: { fileSize: MAX_FILE_SIZE, files: 1 },
  });

  app.post(
    '/api/conversations/:id/files',
    requireAuth,
    requireMember,
    (req, res, next) =>
      upload.single('file')(req, res, (err) => {
        if (err) {
          const msg = err.code === 'LIMIT_FILE_SIZE' ? 'Fichier trop volumineux (15 Mo max).' : 'Échec du téléversement.';
          return res.status(400).json({ error: msg });
        }
        next();
      }),
    wrap((req, res) => {
      if (!req.file) return res.status(400).json({ error: 'Aucun fichier reçu.' });
      const originalName = Buffer.from(req.file.originalname, 'latin1').toString('utf8').slice(0, 200);
      postMessage(req, res, {
        body: typeof req.body.body === 'string' ? req.body.body.trim().slice(0, 5000) : '',
        filePath: req.file.filename,
        fileName: originalName,
        fileType: req.file.mimetype,
        fileSize: req.file.size,
      });
    })
  );

  app.get('/api/files/:messageId', requireAuth, wrap((req, res) => {
    const m = db.prepare('SELECT * FROM messages WHERE id = ?').get(Number(req.params.messageId));
    if (!m || !m.file_path || m.deleted || !q.isMember.get(m.conversation_id, req.user.id)) {
      return res.status(404).json({ error: 'Fichier introuvable.' });
    }
    const inline = /^image\/(png|jpe?g|gif|webp)$/.test(m.file_type) && req.query.download === undefined;
    res.setHeader('Content-Type', inline ? m.file_type : 'application/octet-stream');
    res.setHeader('Content-Security-Policy', "default-src 'none'");
    res.setHeader(
      'Content-Disposition',
      `${inline ? 'inline' : 'attachment'}; filename*=UTF-8''${encodeURIComponent(m.file_name)}`
    );
    res.sendFile(path.join(uploadDir, m.file_path));
  }));

  app.delete('/api/messages/:messageId', requireAuth, wrap((req, res) => {
    const m = db.prepare('SELECT * FROM messages WHERE id = ?').get(Number(req.params.messageId));
    if (!m || !q.isMember.get(m.conversation_id, req.user.id)) {
      return res.status(404).json({ error: 'Message introuvable.' });
    }
    if (m.sender_id !== req.user.id && req.user.role !== 'admin') {
      return res.status(403).json({ error: 'Vous ne pouvez supprimer que vos propres messages.' });
    }
    db.prepare('UPDATE messages SET deleted = 1, body = NULL WHERE id = ?').run(m.id);
    if (m.file_path) fs.rm(path.join(uploadDir, m.file_path), { force: true }, () => {});
    const msg = serializeMessage(messageById.get(m.id));
    emitToUsers(memberIdsOf(m.conversation_id), 'message:update', msg);
    res.json({ message: msg });
  }));

  app.post('/api/conversations/:id/read', requireAuth, requireMember, wrap((req, res) => {
    const last = db
      .prepare('SELECT MAX(id) AS id FROM messages WHERE conversation_id = ?')
      .get(req.conversationId).id || 0;
    db.prepare(
      'UPDATE conversation_members SET last_read_id = ? WHERE conversation_id = ? AND user_id = ?'
    ).run(last, req.conversationId, req.user.id);
    emitToUsers(memberIdsOf(req.conversationId), 'conversation:read', {
      conversationId: req.conversationId,
      userId: req.user.id,
      lastReadId: last,
    });
    res.json({ lastReadId: last });
  }));

  // ---- Administration
  const admin = express.Router();
  admin.use(requireAuth, requireAdmin);

  admin.get('/stats', wrap((req, res) => {
    const counts = Object.fromEntries(
      db.prepare('SELECT status, COUNT(*) AS n FROM users GROUP BY status').all().map((r) => [r.status, r.n])
    );
    res.json({
      users: counts,
      online: online.size,
      conversations: db.prepare('SELECT COUNT(*) AS n FROM conversations').get().n,
      messages: db.prepare('SELECT COUNT(*) AS n FROM messages WHERE sender_id IS NOT NULL').get().n,
    });
  }));

  admin.get('/users', wrap((req, res) => {
    const status = req.query.status;
    const rows = ['pending', 'approved', 'rejected', 'suspended'].includes(status)
      ? db.prepare('SELECT * FROM users WHERE status = ? ORDER BY created_at DESC').all(status)
      : db.prepare('SELECT * FROM users ORDER BY created_at DESC').all();
    res.json({ users: rows.map(publicUser) });
  }));

  function loadTarget(req, res) {
    const target = q.userById.get(Number(req.params.id));
    if (!target) {
      res.status(404).json({ error: 'Utilisateur introuvable.' });
      return null;
    }
    if (target.id === req.user.id) {
      res.status(400).json({ error: 'Vous ne pouvez pas modifier votre propre compte ici.' });
      return null;
    }
    return target;
  }

  const transitions = {
    approve: { to: 'approved', from: ['pending', 'rejected', 'suspended'] },
    reject: { to: 'rejected', from: ['pending'] },
    suspend: { to: 'suspended', from: ['approved'] },
    reactivate: { to: 'approved', from: ['suspended'] },
  };

  for (const [action, rule] of Object.entries(transitions)) {
    admin.post(`/users/:id/${action}`, wrap((req, res) => {
      const target = loadTarget(req, res);
      if (!target) return;
      if (!rule.from.includes(target.status)) {
        return res.status(409).json({ error: `Action impossible : le compte est « ${target.status} ».` });
      }
      db.prepare(
        "UPDATE users SET status = ?, validated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), validated_by = ? WHERE id = ?"
      ).run(rule.to, req.user.id, target.id);
      if (rule.to !== 'approved') kickUser(target.id, 'Votre compte a été désactivé par un administrateur.');
      const updated = publicUser(q.userById.get(target.id));
      emitToAdmins('admin:user', updated);
      io.emit('user:update', updated);
      res.json({ user: updated });
    }));
  }

  admin.patch('/users/:id/role', wrap((req, res) => {
    const target = loadTarget(req, res);
    if (!target) return;
    const role = req.body.role;
    if (!['employee', 'admin'].includes(role)) return res.status(400).json({ error: 'Rôle invalide.' });
    db.prepare('UPDATE users SET role = ? WHERE id = ?').run(role, target.id);
    const updated = publicUser(q.userById.get(target.id));
    // Forcer une reconnexion pour que les droits soient pris en compte en temps réel.
    io.to(`user:${target.id}`).emit('session:refresh');
    emitToAdmins('admin:user', updated);
    res.json({ user: updated });
  }));

  admin.post('/users/:id/reset-password', wrap((req, res) => {
    const target = loadTarget(req, res);
    if (!target) return;
    const temp = crypto.randomBytes(6).toString('base64url');
    db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(bcrypt.hashSync(temp, 10), target.id);
    kickUser(target.id, 'Votre mot de passe a été réinitialisé par un administrateur.');
    res.json({ temporaryPassword: temp });
  }));

  admin.delete('/users/:id', wrap((req, res) => {
    const target = loadTarget(req, res);
    if (!target) return;
    kickUser(target.id, 'Votre compte a été supprimé.');
    db.prepare('DELETE FROM users WHERE id = ?').run(target.id);
    emitToAdmins('admin:user-deleted', { id: target.id });
    res.json({ ok: true });
  }));

  app.use('/api/admin', admin);

  app.use('/api', (req, res) => res.status(404).json({ error: 'Route inconnue.' }));
  app.use((err, req, res, _next) => {
    console.error(err);
    res.status(500).json({ error: 'Erreur interne du serveur.' });
  });

  // ---------------------------------------------------------------- sockets
  const server = http.createServer(app);
  const io = new Server(server);

  io.use((socket, next) => {
    const cookies = Object.fromEntries(
      (socket.handshake.headers.cookie || '')
        .split(';')
        .map((c) => c.trim().split('='))
        .filter((p) => p.length === 2)
        .map(([k, v]) => [k, decodeURIComponent(v)])
    );
    const user = userFromToken(cookies[COOKIE]);
    if (!user) return next(new Error('unauthorized'));
    socket.data.user = user;
    next();
  });

  io.on('connection', (socket) => {
    const user = socket.data.user;
    socket.join(`user:${user.id}`);
    if (user.role === 'admin') socket.join('admins');

    const count = (online.get(user.id) || 0) + 1;
    online.set(user.id, count);
    q.touchUser.run(user.id);
    if (count === 1) io.emit('presence', { userId: user.id, online: true });

    socket.on('typing', (data) => {
      const conversationId = Number(data && data.conversationId);
      if (!q.isMember.get(conversationId, user.id)) return;
      for (const id of memberIdsOf(conversationId)) {
        if (id !== user.id) {
          io.to(`user:${id}`).emit('typing', {
            conversationId,
            userId: user.id,
            name: user.name,
            typing: Boolean(data.typing),
          });
        }
      }
    });

    socket.on('disconnect', () => {
      const left = (online.get(user.id) || 1) - 1;
      if (left <= 0) {
        online.delete(user.id);
        q.touchUser.run(user.id);
        io.emit('presence', { userId: user.id, online: false, lastSeen: new Date().toISOString() });
      } else {
        online.set(user.id, left);
      }
    });
  });

  return { app, server, io, db };
}

function loadOrCreateSecret(dataDir) {
  if (process.env.JWT_SECRET) return process.env.JWT_SECRET;
  const file = path.join(dataDir, '.jwt-secret');
  if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8');
  const secret = crypto.randomBytes(48).toString('hex');
  fs.writeFileSync(file, secret, { mode: 0o600 });
  return secret;
}

function seedAdmin(db, adminOpts = {}) {
  const exists = db.prepare("SELECT 1 FROM users WHERE role = 'admin' LIMIT 1").get();
  if (exists) return;
  const email = (adminOpts.email || process.env.ADMIN_EMAIL || 'admin@agropama.com').toLowerCase();
  const generated = !(adminOpts.password || process.env.ADMIN_PASSWORD);
  const password = adminOpts.password || process.env.ADMIN_PASSWORD || crypto.randomBytes(9).toString('base64url');
  const name = adminOpts.name || process.env.ADMIN_NAME || 'Administrateur Agropama';
  db.prepare(
    `INSERT INTO users (name, email, password_hash, role, status, validated_at)
     VALUES (?, ?, ?, 'admin', 'approved', strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).run(name, email, bcrypt.hashSync(password, 10));
  if (!adminOpts.silent) {
    console.log('──────────────────────────────────────────────');
    console.log(' Compte administrateur initial créé');
    console.log(`   Courriel     : ${email}`);
    console.log(`   Mot de passe : ${generated ? password : '(défini par ADMIN_PASSWORD)'}`);
    console.log(' Changez ce mot de passe après la première connexion.');
    console.log('──────────────────────────────────────────────');
  }
}

module.exports = { createApp };
