'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const express = require('express');
const cookieParser = require('cookie-parser');
const bcrypt = require('bcryptjs');
const multer = require('multer');
const { Server } = require('socket.io');
const { openDatabase, transaction } = require('./db');
const { createCallManager } = require('./calls');

const COOKIE = 'agropama_session';
const SESSION_TTL_MS = 7 * 24 * 3600 * 1000;
const MAX_FILE_SIZE = 60 * 1024 * 1024; // 50 Mo de contenu + surcoût du chiffrement
const MAX_ENVELOPE_BYTES = 256 * 1024;
const PAGE_SIZE = 50;
const LOCK_AFTER_FAILURES = 5;
const LOCK_DURATION_MS = 15 * 60 * 1000;
const SECRET_RE = /^[0-9a-f]{64}$/; // empreinte PBKDF2 calculée sur l'appareil (jamais le mot de passe)
const B64_RE = /^[A-Za-z0-9+/]*={0,2}$/;
const BCRYPT_COST = 11;

/** Même dérivation que public/e2e.js (authSecret) : utilisée pour le compte admin initial et les réinitialisations. */
function deriveAuthSecret(email, password) {
  return crypto
    .pbkdf2Sync(String(password).normalize('NFKC'), 'agropama-auth-v1|' + String(email).trim().toLowerCase(), 100000, 32, 'sha256')
    .toString('hex');
}

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

function createApp(options = {}) {
  const dataDir = options.dataDir || path.join(__dirname, '..', 'data');
  const uploadDir = path.join(dataDir, 'uploads');
  fs.mkdirSync(uploadDir, { recursive: true, mode: 0o700 });

  const db = openDatabase(options.dbFile || path.join(dataDir, 'agropama.db'));
  const secureCookies = Boolean(options.secureCookies);
  const DUMMY_HASH = bcrypt.hashSync(crypto.randomBytes(32).toString('hex'), BCRYPT_COST);

  seedAdmin(db, options.admin);

  // ---------------------------------------------------------------- requêtes
  const q = {
    userById: db.prepare('SELECT * FROM users WHERE id = ?'),
    userByEmail: db.prepare('SELECT * FROM users WHERE email = ?'),
    isMember: db.prepare('SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?'),
    memberIds: db.prepare('SELECT user_id FROM conversation_members WHERE conversation_id = ?'),
    touchUser: db.prepare("UPDATE users SET last_seen = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?"),
    activeKey: db.prepare('SELECT * FROM user_keys WHERE user_id = ? AND active = 1 ORDER BY id DESC LIMIT 1'),
    session: db.prepare('SELECT * FROM sessions WHERE id_hash = ? AND expires_at > ?'),
  };

  const online = new Map(); // userId -> nombre de connexions temps réel

  function publicKeysOf(userId) {
    const k = q.activeKey.get(userId);
    return k ? { keyId: k.id, publicKeys: { enc: JSON.parse(k.enc_pub), sign: JSON.parse(k.sign_pub) } } : { keyId: null, publicKeys: null };
  }

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
      ...publicKeysOf(u.id),
    };
  }

  // ---------------------------------------------------------------- sessions
  function createSession(res, userId, userAgent) {
    const token = crypto.randomBytes(32).toString('base64url');
    const now = Date.now();
    db.prepare('INSERT INTO sessions (id_hash, user_id, expires_at, last_seen, user_agent) VALUES (?, ?, ?, ?, ?)').run(
      sha256(token), userId, now + SESSION_TTL_MS, now, String(userAgent || '').slice(0, 200)
    );
    res.cookie(COOKIE, token, { httpOnly: true, sameSite: 'strict', secure: secureCookies, maxAge: SESSION_TTL_MS, path: '/' });
    return sha256(token);
  }

  function sessionFromToken(token) {
    if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token)) return null;
    const idHash = sha256(token);
    const now = Date.now();
    const s = q.session.get(idHash, now);
    if (!s) return null;
    const user = q.userById.get(s.user_id);
    if (!user || user.status !== 'approved') return null;
    if (now - s.last_seen > 3600 * 1000) {
      db.prepare('UPDATE sessions SET last_seen = ?, expires_at = ? WHERE id_hash = ?').run(now, now + SESSION_TTL_MS, idHash);
    }
    return { user, idHash };
  }

  function revokeSessions(userId, exceptHash = null) {
    db.prepare('DELETE FROM sessions WHERE user_id = ? AND id_hash IS NOT ?').run(userId, exceptHash);
  }

  setInterval(() => db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(Date.now()), 3600 * 1000).unref();

  // ---------------------------------------------------------------- messages
  function memberIdsOf(conversationId) {
    return q.memberIds.all(conversationId).map((r) => r.user_id);
  }

  function serializeMessage(m) {
    let envelope = null;
    if (m.ciphertext && !m.deleted) {
      try { envelope = JSON.parse(m.ciphertext); } catch { envelope = null; }
    }
    return {
      id: m.id,
      conversationId: m.conversation_id,
      senderId: m.sender_id,
      senderName: m.sender_name,
      envelope,
      body: m.deleted ? null : m.body, // messages système uniquement (appels, création de groupe)
      deleted: Boolean(m.deleted),
      file: m.file_path && !m.deleted ? { url: `/api/files/${m.id}`, size: m.file_size } : null,
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
      members: members.map((m) => ({ ...publicUser(m), isGroupAdmin: Boolean(m.is_group_admin), lastReadId: m.last_read_id })),
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

  const emitToAdmins = (event, payload) => io.to('admins').emit(event, payload);

  function broadcastConversation(conversationId) {
    emitToUsers(memberIdsOf(conversationId), 'conversation:update', (uid) => conversationFor(conversationId, uid));
  }

  function kickUser(userId, reason) {
    io.to(`user:${userId}`).emit('session:revoked', { reason });
    io.in(`user:${userId}`).disconnectSockets(true);
  }

  // ---------------------------------------------------------------- application HTTP
  const app = express();
  app.disable('x-powered-by');
  if (options.trustProxy) app.set('trust proxy', 1);
  app.use(express.json({ limit: '512kb' }));
  app.use(cookieParser());

  // En-têtes de sécurité
  app.use((req, res, next) => {
    const host = req.get('host') || '';
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
    res.setHeader('Permissions-Policy', 'camera=(self), microphone=(self), geolocation=(), payment=(), usb=(), interest-cohort=()');
    res.setHeader(
      'Content-Security-Policy',
      [
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self'",
        "img-src 'self' blob: data:",
        "media-src 'self' blob:",
        `connect-src 'self' wss://${host} ws://${host}`,
        "font-src 'self'",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action 'self'",
        "frame-ancestors 'none'",
        "worker-src 'self' blob:",
      ].join('; ')
    );
    if (req.path !== '/api/health') res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');
    if (secureCookies) res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
    if (req.path.startsWith('/api/')) res.setHeader('Cache-Control', 'no-store');
    next();
  });

  // Protection CSRF : toute requête modifiante venant d'un autre site est refusée.
  app.use('/api', (req, res, next) => {
    if (['GET', 'HEAD', 'OPTIONS'].includes(req.method)) return next();
    const origin = req.get('origin');
    if (origin) {
      let originHost = null;
      try { originHost = new URL(origin).host; } catch { /* origine illisible */ }
      if (originHost !== req.get('host')) return res.status(403).json({ error: 'Origine non autorisée.' });
    }
    next();
  });

  app.use(express.static(path.join(__dirname, '..', 'public'), { dotfiles: 'deny', index: 'index.html' }));

  // Utilisé par l'application Android pour vérifier l'adresse du serveur.
  app.get('/api/health', (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.json({ app: 'agropama-chat', ok: true, e2e: true });
  });

  const wrap = (fn) => (req, res, next) => {
    try {
      const r = fn(req, res, next);
      if (r && typeof r.catch === 'function') r.catch(next);
    } catch (err) {
      next(err);
    }
  };

  function requireAuth(req, res, next) {
    const s = sessionFromToken(req.cookies[COOKIE]);
    if (!s) return res.status(401).json({ error: 'Non authentifié.' });
    req.user = s.user;
    req.sessionHash = s.idHash;
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

  // Limiteur par adresse IP (en plus du verrouillage par compte).
  function limiter(max, windowMs) {
    const hits = new Map();
    setInterval(() => {
      const now = Date.now();
      for (const [k, v] of hits) if (now > v.reset) hits.delete(k);
    }, windowMs).unref();
    return (key) => {
      const now = Date.now();
      let e = hits.get(key);
      if (!e || now > e.reset) {
        e = { count: 0, reset: now + windowMs };
        hits.set(key, e);
      }
      e.count += 1;
      return e.count <= max;
    };
  }
  const authLimiter = limiter(options.rateLimit ?? 60, 15 * 60 * 1000);
  const uploadLimiter = limiter(40, 10 * 60 * 1000);
  const rateLimit = (req, res, next) =>
    authLimiter(req.ip) ? next() : res.status(429).json({ error: 'Trop de tentatives. Réessayez plus tard.' });

  // ---------------------------------------------------------------- authentification
  app.post('/api/auth/register', rateLimit, wrap((req, res) => {
    const name = clean(req.body.name, 80);
    const email = clean(req.body.email, 120).toLowerCase();
    const phone = clean(req.body.phone, 30);
    const department = clean(req.body.department, 80);
    const jobTitle = clean(req.body.jobTitle, 80);
    const secret = req.body.password;

    if (name.length < 2) return res.status(400).json({ error: 'Le nom est requis.' });
    if (!EMAIL_RE.test(email)) return res.status(400).json({ error: 'Adresse courriel invalide.' });
    if (typeof secret !== 'string' || !SECRET_RE.test(secret)) {
      return res.status(400).json({ error: "Mot de passe refusé. Mettez à jour l'application." });
    }
    const message = 'Inscription reçue. Un administrateur doit valider votre compte avant que vous puissiez vous connecter.';
    const hash = bcrypt.hashSync(secret, BCRYPT_COST);
    // Réponse identique si le courriel existe déjà : impossible de savoir qui a un compte.
    if (q.userByEmail.get(email)) return res.status(201).json({ message });
    const info = db
      .prepare('INSERT INTO users (name, email, phone, department, job_title, password_hash) VALUES (?, ?, ?, ?, ?, ?)')
      .run(name, email, phone || null, department || null, jobTitle || null, hash);
    emitToAdmins('admin:registration', publicUser(q.userById.get(info.lastInsertRowid)));
    res.status(201).json({ message });
  }));

  app.post('/api/auth/login', rateLimit, wrap((req, res) => {
    const email = clean(req.body.email, 120).toLowerCase();
    const secret = typeof req.body.password === 'string' ? req.body.password : '';
    const user = email ? q.userByEmail.get(email) : null;
    const now = Date.now();
    if (user && user.locked_until > now) {
      const minutes = Math.ceil((user.locked_until - now) / 60000);
      return res.status(429).json({ error: `Trop de tentatives sur ce compte. Réessayez dans ${minutes} min.` });
    }
    // Comparaison toujours effectuée (même temps de réponse que le compte existe ou non).
    const valid = bcrypt.compareSync(SECRET_RE.test(secret) ? secret : 'x', user ? user.password_hash : DUMMY_HASH);
    if (!user || !valid) {
      if (user) {
        const failures = user.failed_logins + 1;
        if (failures >= LOCK_AFTER_FAILURES) {
          db.prepare('UPDATE users SET failed_logins = 0, locked_until = ? WHERE id = ?').run(now + LOCK_DURATION_MS, user.id);
          return res.status(429).json({ error: 'Trop de tentatives sur ce compte. Réessayez dans 15 min.' });
        }
        db.prepare('UPDATE users SET failed_logins = ? WHERE id = ?').run(failures, user.id);
      }
      return res.status(401).json({ error: 'Courriel ou mot de passe incorrect.' });
    }
    db.prepare('UPDATE users SET failed_logins = 0, locked_until = 0 WHERE id = ?').run(user.id);
    const blocked = {
      pending: 'Votre compte est en attente de validation par un administrateur.',
      rejected: "Votre demande d'inscription a été refusée. Contactez l'administration.",
      suspended: "Votre compte est suspendu. Contactez l'administration.",
    };
    if (user.status !== 'approved') return res.status(403).json({ error: blocked[user.status], status: user.status });
    createSession(res, user.id, req.get('user-agent'));
    res.json({ user: publicUser(user) });
  }));

  app.post('/api/auth/logout', (req, res) => {
    const token = req.cookies[COOKIE];
    if (typeof token === 'string') db.prepare('DELETE FROM sessions WHERE id_hash = ?').run(sha256(token));
    res.clearCookie(COOKIE, { httpOnly: true, sameSite: 'strict', secure: secureCookies, path: '/' });
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
    const changingPassword = Boolean(req.body.newPassword);
    if (changingPassword) {
      const current = String(req.body.currentPassword || '');
      if (!SECRET_RE.test(current) || !bcrypt.compareSync(current, u.password_hash)) {
        return res.status(400).json({ error: 'Mot de passe actuel incorrect.' });
      }
      if (!SECRET_RE.test(String(req.body.newPassword))) return res.status(400).json({ error: 'Nouveau mot de passe invalide.' });
      const key = q.activeKey.get(u.id);
      if (key && !validWrapped(req.body.wrapped)) {
        return res.status(400).json({ error: 'Sauvegarde des clés de chiffrement manquante.' });
      }
      hash = bcrypt.hashSync(String(req.body.newPassword), BCRYPT_COST);
      if (key) db.prepare('UPDATE user_keys SET wrapped = ? WHERE id = ?').run(JSON.stringify(req.body.wrapped), key.id);
    }
    db.prepare('UPDATE users SET name = ?, phone = ?, department = ?, job_title = ?, password_hash = ? WHERE id = ?')
      .run(name, phone, department, jobTitle, hash, u.id);
    if (changingPassword) {
      // Déconnecte tous les autres appareils (un voleur de session perd l'accès).
      revokeSessions(u.id, req.sessionHash);
      for (const s of io.sockets.sockets.values()) {
        if (s.data.user && s.data.user.id === u.id && s.data.sessionHash !== req.sessionHash) {
          s.emit('session:revoked', { reason: 'Votre mot de passe a été modifié sur un autre appareil.' });
          s.disconnect(true);
        }
      }
    }
    res.json({ user: publicUser(q.userById.get(u.id)) });
  }));

  // ---------------------------------------------------------------- clés de chiffrement
  function validJwk(jwk) {
    return Boolean(jwk) && jwk.kty === 'EC' && jwk.crv === 'P-256' &&
      typeof jwk.x === 'string' && /^[A-Za-z0-9_-]{43}$/.test(jwk.x) &&
      typeof jwk.y === 'string' && /^[A-Za-z0-9_-]{43}$/.test(jwk.y);
  }
  function validWrapped(w) {
    return Boolean(w) && w.v === 1 && Number.isInteger(w.iter) && w.iter >= 100000 && w.iter <= 2000000 &&
      ['salt', 'iv', 'ct'].every((k) => typeof w[k] === 'string' && w[k].length <= 8192 && B64_RE.test(w[k]));
  }
  const keyView = (k) => ({ id: k.id, enc: JSON.parse(k.enc_pub), sign: JSON.parse(k.sign_pub), active: Boolean(k.active), createdAt: k.created_at });

  app.get('/api/keys/me', requireAuth, (req, res) => {
    const k = q.activeKey.get(req.user.id);
    res.json({ key: k ? { ...keyView(k), wrapped: k.wrapped ? JSON.parse(k.wrapped) : null } : null });
  });

  app.post('/api/keys', requireAuth, wrap((req, res) => {
    const { enc, sign, wrapped, replace } = req.body || {};
    if (!validJwk(enc) || !validJwk(sign) || !validWrapped(wrapped)) return res.status(400).json({ error: 'Clés invalides.' });
    const existing = q.activeKey.get(req.user.id);
    if (existing && !replace) return res.status(409).json({ error: 'Des clés existent déjà pour ce compte.' });
    const pick = (j) => JSON.stringify({ kty: 'EC', crv: 'P-256', x: j.x, y: j.y });
    const id = transaction(db, () => {
      db.prepare('UPDATE user_keys SET active = 0, wrapped = NULL WHERE user_id = ?').run(req.user.id);
      return Number(db.prepare('INSERT INTO user_keys (user_id, enc_pub, sign_pub, wrapped) VALUES (?, ?, ?, ?)')
        .run(req.user.id, pick(enc), pick(sign), JSON.stringify(wrapped)).lastInsertRowid);
    });
    const key = keyView(db.prepare('SELECT * FROM user_keys WHERE id = ?').get(id));
    io.emit('user:keys', { userId: req.user.id, keyId: key.id, publicKeys: { enc: key.enc, sign: key.sign }, replaced: Boolean(existing) });
    res.status(201).json({ key });
  }));

  app.get('/api/keys/:userId', requireAuth, (req, res) => {
    const rows = db.prepare('SELECT * FROM user_keys WHERE user_id = ? ORDER BY id').all(Number(req.params.userId));
    res.json({ keys: rows.map(keyView) });
  });

  // ---------------------------------------------------------------- annuaire
  app.get('/api/users', requireAuth, wrap((req, res) => {
    const users = db.prepare("SELECT * FROM users WHERE status = 'approved' AND id != ? ORDER BY name").all(req.user.id);
    res.json({ users: users.map(publicUser) });
  }));

  // Serveurs STUN/TURN utilisés pour établir les appels (TURN recommandé pour les réseaux mobiles).
  const iceServers = [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }];
  const turnUrls = (options.turnUrls ?? process.env.TURN_URLS ?? '').split(',').map((u) => u.trim()).filter(Boolean);
  if (turnUrls.length) {
    iceServers.push({
      urls: turnUrls,
      username: options.turnUsername ?? process.env.TURN_USERNAME,
      credential: options.turnCredential ?? process.env.TURN_CREDENTIAL,
    });
  }
  app.get('/api/rtc-config', requireAuth, (req, res) => res.json({ iceServers }));

  // ---------------------------------------------------------------- discussions
  app.get('/api/conversations', requireAuth, wrap((req, res) => {
    const ids = db.prepare('SELECT conversation_id AS id FROM conversation_members WHERE user_id = ?').all(req.user.id);
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
    const other = q.userById.get(Number(req.body.userId));
    if (!other || other.status !== 'approved' || other.id === req.user.id) {
      return res.status(400).json({ error: 'Employé introuvable.' });
    }
    const key = [req.user.id, other.id].sort((a, b) => a - b).join(':');
    let conv = db.prepare('SELECT id FROM conversations WHERE direct_key = ?').get(key);
    if (!conv) {
      conv = transaction(db, () => {
        const info = db.prepare("INSERT INTO conversations (type, direct_key, created_by) VALUES ('direct', ?, ?)").run(key, req.user.id);
        const add = db.prepare('INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)');
        add.run(info.lastInsertRowid, req.user.id);
        add.run(info.lastInsertRowid, other.id);
        return { id: Number(info.lastInsertRowid) };
      });
    }
    res.json({ conversation: conversationFor(conv.id, req.user.id) });
  }));

  function insertSystemMessage(conversationId, text) {
    const info = db.prepare('INSERT INTO messages (conversation_id, sender_id, body) VALUES (?, NULL, ?)').run(conversationId, text);
    return messageById.get(info.lastInsertRowid);
  }

  app.post('/api/conversations/group', requireAuth, wrap((req, res) => {
    const name = clean(req.body.name, 80);
    if (name.length < 2) return res.status(400).json({ error: 'Le nom du groupe est requis.' });
    const ids = [...new Set((Array.isArray(req.body.memberIds) ? req.body.memberIds : []).map(Number))].filter((id) => id !== req.user.id);
    const valid = ids.filter((id) => {
      const u = q.userById.get(id);
      return u && u.status === 'approved';
    }).slice(0, 500);
    if (valid.length === 0) return res.status(400).json({ error: 'Ajoutez au moins un membre.' });

    const convId = transaction(db, () => {
      const id = Number(db.prepare("INSERT INTO conversations (type, name, created_by) VALUES ('group', ?, ?)").run(name, req.user.id).lastInsertRowid);
      db.prepare('INSERT INTO conversation_members (conversation_id, user_id, is_group_admin) VALUES (?, ?, 1)').run(id, req.user.id);
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

  app.post('/api/conversations/:id/members', requireAuth, requireMember, wrap((req, res) => {
    if (!canManageGroup(req, req.conversationId)) {
      return res.status(403).json({ error: 'Seuls les administrateurs du groupe peuvent ajouter des membres.' });
    }
    const added = [];
    for (const raw of (Array.isArray(req.body.memberIds) ? req.body.memberIds : []).slice(0, 500)) {
      const u = q.userById.get(Number(raw));
      if (!u || u.status !== 'approved' || q.isMember.get(req.conversationId, u.id)) continue;
      db.prepare('INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)').run(req.conversationId, u.id);
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
    if (!self && !canManageGroup(req, req.conversationId)) return res.status(403).json({ error: 'Action non autorisée.' });
    const conv = db.prepare('SELECT type FROM conversations WHERE id = ?').get(req.conversationId);
    if (conv.type !== 'group') return res.status(400).json({ error: 'Action réservée aux groupes.' });
    const target = q.userById.get(targetId);
    if (!target || !q.isMember.get(req.conversationId, targetId)) return res.status(404).json({ error: 'Membre introuvable.' });
    const before = memberIdsOf(req.conversationId);
    db.prepare('DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?').run(req.conversationId, targetId);
    const text = self ? `${target.name} a quitté le groupe` : `${req.user.name} a retiré ${target.name}`;
    const msg = insertSystemMessage(req.conversationId, text);
    emitToUsers(memberIdsOf(req.conversationId), 'message:new', serializeMessage(msg));
    broadcastConversation(req.conversationId);
    emitToUsers([targetId], 'conversation:removed', { id: req.conversationId });
    if (before.length <= 1) db.prepare('DELETE FROM conversations WHERE id = ?').run(req.conversationId);
    res.json({ ok: true });
  }));

  // ---------------------------------------------------------------- messages chiffrés
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

  /** Vérifie la forme d'une enveloppe chiffrée (le serveur ne peut ni la lire ni la falsifier sans être détecté). */
  function checkEnvelope(env, conversationId, user) {
    if (!env || typeof env !== 'object' || Array.isArray(env)) return 'Les messages doivent être chiffrés.';
    if (env.v !== 1 || env.c !== conversationId || env.s !== user.id) return 'Enveloppe chiffrée invalide.';
    const key = q.activeKey.get(user.id);
    if (!key || env.sk !== key.id) return 'Clé de chiffrement périmée : rechargez l’application.';
    const isB64 = (v, max) => typeof v === 'string' && v.length > 0 && v.length <= max && B64_RE.test(v);
    if (!isB64(env.iv, 24) || !isB64(env.epk, 120) || !isB64(env.sig, 200) || !isB64(env.ct, MAX_ENVELOPE_BYTES)) {
      return 'Enveloppe chiffrée invalide.';
    }
    if (!env.keys || typeof env.keys !== 'object') return 'Enveloppe chiffrée invalide.';
    const entries = Object.entries(env.keys);
    if (entries.length === 0 || entries.length > 600) return 'Enveloppe chiffrée invalide.';
    for (const [uid, k] of entries) {
      if (!/^\d+$/.test(uid) || !k || !Number.isInteger(k.kid) || !isB64(k.iv, 24) || !isB64(k.k, 80)) return 'Enveloppe chiffrée invalide.';
    }
    if (JSON.stringify(env).length > MAX_ENVELOPE_BYTES) return 'Message trop long.';
    return null;
  }

  function postMessage(req, res, fields) {
    const info = db
      .prepare('INSERT INTO messages (conversation_id, sender_id, ciphertext, file_path, file_name, file_type, file_size) VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(req.conversationId, req.user.id, fields.ciphertext, fields.filePath || null, fields.filePath ? 'fichier-chiffre' : null,
        fields.filePath ? 'application/octet-stream' : null, fields.fileSize || null);
    db.prepare('UPDATE conversation_members SET last_read_id = ? WHERE conversation_id = ? AND user_id = ?')
      .run(info.lastInsertRowid, req.conversationId, req.user.id);
    const msg = serializeMessage(messageById.get(info.lastInsertRowid));
    emitToUsers(memberIdsOf(req.conversationId), 'message:new', msg);
    broadcastConversation(req.conversationId);
    res.status(201).json({ message: msg });
  }

  app.post('/api/conversations/:id/messages', requireAuth, requireMember, wrap((req, res) => {
    const problem = checkEnvelope(req.body.envelope, req.conversationId, req.user);
    if (problem) return res.status(400).json({ error: problem });
    postMessage(req, res, { ciphertext: JSON.stringify(req.body.envelope) });
  }));

  const upload = multer({
    storage: multer.diskStorage({
      destination: uploadDir,
      filename: (req, file, cb) => cb(null, crypto.randomUUID()),
    }),
    limits: { fileSize: MAX_FILE_SIZE, files: 1, fields: 2, fieldSize: MAX_ENVELOPE_BYTES + 1024 },
  });

  app.post(
    '/api/conversations/:id/files',
    requireAuth,
    requireMember,
    (req, res, next) => {
      if (!uploadLimiter(`u${req.user.id}`)) return res.status(429).json({ error: "Trop d'envois de fichiers. Patientez quelques minutes." });
      upload.single('file')(req, res, (err) => {
        if (err) {
          return res.status(400).json({ error: err.code === 'LIMIT_FILE_SIZE' ? 'Fichier trop volumineux (50 Mo max).' : 'Échec du téléversement.' });
        }
        next();
      });
    },
    wrap((req, res) => {
      if (!req.file) return res.status(400).json({ error: 'Aucun fichier reçu.' });
      let env = null;
      try { env = JSON.parse(req.body.envelope || 'null'); } catch { env = null; }
      const problem = checkEnvelope(env, req.conversationId, req.user);
      if (problem) {
        fs.rm(req.file.path, { force: true }, () => {});
        return res.status(400).json({ error: problem });
      }
      postMessage(req, res, { ciphertext: JSON.stringify(env), filePath: req.file.filename, fileSize: req.file.size });
    })
  );

  app.get('/api/files/:messageId', requireAuth, wrap((req, res) => {
    const m = db.prepare('SELECT * FROM messages WHERE id = ?').get(Number(req.params.messageId));
    if (!m || !m.file_path || m.deleted || !q.isMember.get(m.conversation_id, req.user.id)) {
      return res.status(404).json({ error: 'Fichier introuvable.' });
    }
    // Contenu chiffré : déchiffré uniquement sur l'appareil du destinataire.
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Security-Policy', "default-src 'none'; sandbox");
    res.setHeader('Content-Disposition', 'attachment; filename="fichier-chiffre.bin"');
    res.sendFile(path.join(uploadDir, path.basename(m.file_path)));
  }));

  app.delete('/api/messages/:messageId', requireAuth, wrap((req, res) => {
    const m = db.prepare('SELECT * FROM messages WHERE id = ?').get(Number(req.params.messageId));
    if (!m || !q.isMember.get(m.conversation_id, req.user.id)) return res.status(404).json({ error: 'Message introuvable.' });
    if (m.sender_id !== req.user.id && req.user.role !== 'admin') {
      return res.status(403).json({ error: 'Vous ne pouvez supprimer que vos propres messages.' });
    }
    db.prepare('UPDATE messages SET deleted = 1, body = NULL, ciphertext = NULL WHERE id = ?').run(m.id);
    if (m.file_path) fs.rm(path.join(uploadDir, path.basename(m.file_path)), { force: true }, () => {});
    const msg = serializeMessage(messageById.get(m.id));
    emitToUsers(memberIdsOf(m.conversation_id), 'message:update', msg);
    res.json({ message: msg });
  }));

  app.post('/api/conversations/:id/read', requireAuth, requireMember, wrap((req, res) => {
    const last = db.prepare('SELECT MAX(id) AS id FROM messages WHERE conversation_id = ?').get(req.conversationId).id || 0;
    db.prepare('UPDATE conversation_members SET last_read_id = ? WHERE conversation_id = ? AND user_id = ?').run(last, req.conversationId, req.user.id);
    emitToUsers(memberIdsOf(req.conversationId), 'conversation:read', { conversationId: req.conversationId, userId: req.user.id, lastReadId: last });
    res.json({ lastReadId: last });
  }));

  // ---------------------------------------------------------------- administration
  const admin = express.Router();
  admin.use(requireAuth, requireAdmin);

  admin.get('/stats', wrap((req, res) => {
    const counts = Object.fromEntries(db.prepare('SELECT status, COUNT(*) AS n FROM users GROUP BY status').all().map((r) => [r.status, r.n]));
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
      db.prepare("UPDATE users SET status = ?, validated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), validated_by = ? WHERE id = ?")
        .run(rule.to, req.user.id, target.id);
      if (rule.to !== 'approved') {
        revokeSessions(target.id);
        kickUser(target.id, 'Votre compte a été désactivé par un administrateur.');
      }
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
    io.to(`user:${target.id}`).emit('session:refresh');
    emitToAdmins('admin:user', updated);
    res.json({ user: updated });
  }));

  admin.post('/users/:id/reset-password', wrap((req, res) => {
    const target = loadTarget(req, res);
    if (!target) return;
    const temp = `Agro${crypto.randomInt(10, 99)}-${crypto.randomBytes(6).toString('base64url')}`;
    db.prepare('UPDATE users SET password_hash = ?, failed_logins = 0, locked_until = 0 WHERE id = ?')
      .run(bcrypt.hashSync(deriveAuthSecret(target.email, temp), BCRYPT_COST), target.id);
    revokeSessions(target.id);
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
    if (err && err.type === 'entity.too.large') return res.status(413).json({ error: 'Requête trop volumineuse.' });
    if (err && err.type === 'entity.parse.failed') return res.status(400).json({ error: 'Requête invalide.' });
    console.error(err);
    res.status(500).json({ error: 'Erreur interne du serveur.' });
  });

  // ---------------------------------------------------------------- temps réel
  const server = http.createServer(app);
  const io = new Server(server, {
    maxHttpBufferSize: 256 * 1024,
    // Refuse les connexions ouvertes depuis un autre site (détournement de WebSocket).
    allowRequest: (req, cb) => {
      const origin = req.headers.origin;
      if (!origin) return cb(null, true);
      let host = null;
      try { host = new URL(origin).host; } catch { /* origine illisible */ }
      cb(null, host === req.headers.host);
    },
  });

  io.use((socket, next) => {
    const cookies = Object.fromEntries(
      (socket.handshake.headers.cookie || '')
        .split(';')
        .map((c) => c.trim().split('='))
        .filter((p) => p.length === 2)
        .map(([k, v]) => [k, decodeURIComponent(v)])
    );
    const s = sessionFromToken(cookies[COOKIE]);
    if (!s) return next(new Error('unauthorized'));
    socket.data.user = s.user;
    socket.data.sessionHash = s.idHash;
    next();
  });

  const callManager = createCallManager({
    io,
    db,
    online,
    isMember: (cid, uid) => Boolean(q.isMember.get(cid, uid)),
    memberIdsOf,
    logToConversation(conversationId, text) {
      if (!db.prepare('SELECT 1 FROM conversations WHERE id = ?').get(conversationId)) return;
      const msg = insertSystemMessage(conversationId, text);
      emitToUsers(memberIdsOf(conversationId), 'message:new', serializeMessage(msg));
      broadcastConversation(conversationId);
    },
  });

  io.on('connection', (socket) => {
    const user = socket.data.user;

    // Limitation du nombre d'événements (protection contre l'inondation / déni de service).
    let windowStart = Date.now();
    let count = 0;
    socket.use((packet, next) => {
      const now = Date.now();
      if (now - windowStart > 10000) {
        windowStart = now;
        count = 0;
      }
      count += 1;
      if (count > 400) return socket.disconnect(true);
      if (count > 150) return; // événements ignorés au-delà de la limite
      next();
    });

    callManager.attach(socket);
    socket.join(`user:${user.id}`);
    if (user.role === 'admin') socket.join('admins');

    const n = (online.get(user.id) || 0) + 1;
    online.set(user.id, n);
    q.touchUser.run(user.id);
    if (n === 1) io.emit('presence', { userId: user.id, online: true });

    socket.on('typing', (data) => {
      const conversationId = Number(data && data.conversationId);
      if (!q.isMember.get(conversationId, user.id)) return;
      for (const id of memberIdsOf(conversationId)) {
        if (id !== user.id) {
          io.to(`user:${id}`).emit('typing', { conversationId, userId: user.id, name: user.name, typing: Boolean(data.typing) });
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

function seedAdmin(db, adminOpts = {}) {
  const exists = db.prepare("SELECT 1 FROM users WHERE role = 'admin' LIMIT 1").get();
  if (exists) return;
  const email = (adminOpts.email || process.env.ADMIN_EMAIL || 'admin@agropama.com').toLowerCase();
  const generated = !(adminOpts.password || process.env.ADMIN_PASSWORD);
  const password = adminOpts.password || process.env.ADMIN_PASSWORD || `Agro${crypto.randomInt(10, 99)}-${crypto.randomBytes(9).toString('base64url')}`;
  const name = adminOpts.name || process.env.ADMIN_NAME || 'Administrateur Agropama';
  db.prepare(
    `INSERT INTO users (name, email, password_hash, role, status, validated_at)
     VALUES (?, ?, ?, 'admin', 'approved', strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).run(name, email, bcrypt.hashSync(deriveAuthSecret(email, password), BCRYPT_COST));
  if (!adminOpts.silent) {
    console.log('──────────────────────────────────────────────');
    console.log(' Compte administrateur initial créé');
    console.log(`   Courriel     : ${email}`);
    console.log(`   Mot de passe : ${generated ? password : '(défini par ADMIN_PASSWORD)'}`);
    console.log(' Changez ce mot de passe après la première connexion.');
    console.log('──────────────────────────────────────────────');
  }
}

module.exports = { createApp, deriveAuthSecret };
