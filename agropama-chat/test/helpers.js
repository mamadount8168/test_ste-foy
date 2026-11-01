'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { io: ioClient } = require('socket.io-client');
const E2E = require('../public/e2e.js');
const { createApp } = require('../src/app');

const ADMIN = { email: 'admin@agropama.com', password: 'AdminPass2026', silent: true };

async function startServer() {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'agropama-'));
  const { server, io, db } = createApp({ dataDir, dbFile: path.join(dataDir, 'test.db'), admin: ADMIN, rateLimit: 1000 });
  await new Promise((r) => server.listen(0, r));
  const base = `http://localhost:${server.address().port}`;
  const close = async () => {
    io.close();
    await new Promise((r) => server.close(r));
    db.close();
    fs.rmSync(dataDir, { recursive: true, force: true });
  };
  return { base, db, dataDir, close };
}

function client(base) {
  let cookie = '';
  const c = {
    id: null,
    keys: null,
    get cookie() { return cookie; },
    async call(method, url, body, headers = {}) {
      const isForm = body instanceof FormData;
      const res = await fetch(base + url, {
        method,
        headers: isForm ? { cookie, ...headers } : { 'Content-Type': 'application/json', cookie, ...headers },
        body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
      });
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0];
      const type = res.headers.get('content-type') || '';
      return { status: res.status, headers: res.headers, data: type.includes('json') ? await res.json() : Buffer.from(await res.arrayBuffer()) };
    },
    async register(name, email, password) {
      return c.call('POST', '/api/auth/register', { name, email, password: await E2E.authSecret(email, password) });
    },
    async login(email, password) {
      const r = await c.call('POST', '/api/auth/login', { email, password: await E2E.authSecret(email, password) });
      if (r.status === 200) c.id = r.data.user.id;
      return r;
    },
    async setupKeys(password) {
      const identity = await E2E.generateIdentity();
      const pub = await E2E.exportPublic(identity);
      const r = await c.call('POST', '/api/keys', { ...pub, wrapped: await E2E.wrapPrivate(identity, password) });
      c.keys = { identity, keyId: r.data.key.id };
      return r;
    },
    async envelope(conv, payload) {
      const recipients = [];
      for (const m of conv.members) recipients.push({ id: m.id, kid: m.keyId, enc: await E2E.importEncPublic(m.publicKeys.enc) });
      return E2E.encryptMessage({
        payload, conversationId: conv.id, senderId: c.id, senderKeyId: c.keys.keyId, signPriv: c.keys.identity.signPriv, recipients,
      });
    },
    async sendText(conv, text) {
      return c.call('POST', `/api/conversations/${conv.id}/messages`, { envelope: await c.envelope(conv, { t: 'text', text }) });
    },
    async decrypt(msg, senderKeys) {
      return E2E.decryptMessage(msg.envelope, {
        myId: c.id, myKeyId: c.keys.keyId, encPriv: c.keys.identity.encPriv,
        senderSignPub: await E2E.importSignPublic(senderKeys.sign), conversationId: msg.conversationId, senderId: msg.senderId,
      });
    },
    socket() {
      return ioClient(base, { extraHeaders: { cookie }, transports: ['websocket'], reconnection: false });
    },
  };
  return c;
}

/** Crée l'admin + des employés validés avec leurs clés. */
async function team(base, names) {
  const admin = client(base);
  await admin.login(ADMIN.email, ADMIN.password);
  const out = { admin };
  for (const n of names) {
    const c = client(base);
    const email = `${n}@agropama.com`;
    await c.register(n, email, 'Semences2026');
    out[n] = c;
  }
  for (const u of (await admin.call('GET', '/api/admin/users?status=pending')).data.users) {
    await admin.call('POST', `/api/admin/users/${u.id}/approve`);
  }
  for (const n of names) {
    await out[n].login(`${n}@agropama.com`, 'Semences2026');
    await out[n].setupKeys('Semences2026');
  }
  return out;
}

const once = (socket, event) => new Promise((resolve) => socket.once(event, resolve));
const emitAck = (socket, event, data) => new Promise((resolve) => socket.emit(event, data, resolve));

module.exports = { ADMIN, E2E, startServer, client, team, once, emitAck };
