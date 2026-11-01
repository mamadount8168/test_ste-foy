'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { io: ioClient } = require('socket.io-client');
const { createApp } = require('../src/app');

const ADMIN = { email: 'admin@agropama.com', password: 'AdminPass123', silent: true };

async function setup() {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'agropama-'));
  const { server, io, db } = createApp({ dataDir, dbFile: ':memory:', jwtSecret: 'test', admin: ADMIN, rateLimit: 1000 });
  await new Promise((r) => server.listen(0, r));
  const base = `http://localhost:${server.address().port}`;
  const close = async () => {
    io.close();
    await new Promise((r) => server.close(r));
    db.close();
    fs.rmSync(dataDir, { recursive: true, force: true });
  };
  return { base, close };
}

function client(base) {
  let cookie = '';
  async function call(method, url, body) {
    const res = await fetch(base + url, {
      method,
      headers: { 'Content-Type': 'application/json', cookie },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const set = res.headers.get('set-cookie');
    if (set) cookie = set.split(';')[0];
    return { status: res.status, data: await res.json() };
  }
  return {
    call,
    get cookie() { return cookie; },
    socket() {
      return ioClient(base, { extraHeaders: { cookie }, transports: ['websocket'], reconnection: false });
    },
  };
}

const once = (socket, event) => new Promise((resolve) => socket.once(event, resolve));

test('inscription, validation par l’admin, puis messagerie en temps réel', async () => {
  const { base, close } = await setup();
  try {
    const admin = client(base);
    const alice = client(base);
    const bob = client(base);

    // Inscription : le compte reste en attente
    let r = await alice.call('POST', '/api/auth/register', { name: 'Alice Diallo', email: 'alice@agropama.com', password: 'motdepasse1', department: 'Production' });
    assert.equal(r.status, 201);
    r = await bob.call('POST', '/api/auth/register', { name: 'Bob Ndiaye', email: 'bob@agropama.com', password: 'motdepasse2' });
    assert.equal(r.status, 201);
    r = await alice.call('POST', '/api/auth/register', { name: 'Alice', email: 'ALICE@agropama.com', password: 'motdepasse1' });
    assert.equal(r.status, 409, 'courriel en double refusé');

    r = await alice.call('POST', '/api/auth/login', { email: 'alice@agropama.com', password: 'motdepasse1' });
    assert.equal(r.status, 403);
    assert.equal(r.data.status, 'pending');

    // Un employé ne peut pas accéder à l'administration
    r = await admin.call('POST', '/api/auth/login', ADMIN);
    assert.equal(r.status, 200);
    assert.equal(r.data.user.role, 'admin');

    r = await admin.call('GET', '/api/admin/users?status=pending');
    assert.equal(r.data.users.length, 2);
    const [aliceId, bobId] = ['alice', 'bob'].map((n) => r.data.users.find((u) => u.email.startsWith(n)).id);

    assert.equal((await admin.call('POST', `/api/admin/users/${aliceId}/approve`)).status, 200);
    assert.equal((await admin.call('POST', `/api/admin/users/${bobId}/approve`)).status, 200);

    assert.equal((await alice.call('POST', '/api/auth/login', { email: 'alice@agropama.com', password: 'motdepasse1' })).status, 200);
    assert.equal((await bob.call('POST', '/api/auth/login', { email: 'bob@agropama.com', password: 'motdepasse2' })).status, 200);
    assert.equal((await alice.call('GET', '/api/admin/users')).status, 403, 'employé exclu de l’admin');

    // Discussion directe + message temps réel
    const bobSocket = bob.socket();
    await once(bobSocket, 'connect');

    r = await alice.call('POST', '/api/conversations/direct', { userId: bobId });
    const convId = r.data.conversation.id;
    assert.equal(r.data.conversation.title, 'Bob Ndiaye');
    r = await alice.call('POST', '/api/conversations/direct', { userId: bobId });
    assert.equal(r.data.conversation.id, convId, 'pas de doublon de discussion');

    const received = once(bobSocket, 'message:new');
    r = await alice.call('POST', `/api/conversations/${convId}/messages`, { body: 'Bonjour Bob, la livraison est prête.' });
    assert.equal(r.status, 201);
    const msg = await received;
    assert.equal(msg.body, 'Bonjour Bob, la livraison est prête.');
    assert.equal(msg.senderName, 'Alice Diallo');

    r = await bob.call('GET', '/api/conversations');
    assert.equal(r.data.conversations[0].unread, 1);
    await bob.call('POST', `/api/conversations/${convId}/read`);
    r = await bob.call('GET', '/api/conversations');
    assert.equal(r.data.conversations[0].unread, 0);

    // Un non-membre (l'admin) ne peut pas lire la discussion
    assert.equal((await admin.call('GET', `/api/conversations/${convId}/messages`)).status, 404);

    // Groupe
    r = await alice.call('POST', '/api/conversations/group', { name: 'Équipe Production', memberIds: [bobId] });
    assert.equal(r.status, 201);
    assert.equal(r.data.conversation.members.length, 2);

    // Suspension : la session de Bob est coupée immédiatement
    const revoked = once(bobSocket, 'session:revoked');
    await admin.call('POST', `/api/admin/users/${bobId}/suspend`);
    await revoked;
    assert.equal((await bob.call('GET', '/api/me')).status, 401);
    r = await bob.call('POST', '/api/auth/login', { email: 'bob@agropama.com', password: 'motdepasse2' });
    assert.equal(r.status, 403);
    assert.equal(r.data.status, 'suspended');
    bobSocket.close();
  } finally {
    await close();
  }
});
