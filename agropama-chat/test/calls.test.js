'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { io: ioClient } = require('socket.io-client');
const { createApp } = require('../src/app');

const ADMIN = { email: 'admin@agropama.com', password: 'AdminPass123', silent: true };

function client(base) {
  let cookie = '';
  return {
    async call(method, url, body) {
      const isForm = body instanceof FormData;
      const res = await fetch(base + url, {
        method,
        headers: isForm ? { cookie } : { 'Content-Type': 'application/json', cookie },
        body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
      });
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0];
      const type = res.headers.get('content-type') || '';
      return { status: res.status, headers: res.headers, data: type.includes('json') ? await res.json() : await res.arrayBuffer() };
    },
    socket() {
      return ioClient(base, { extraHeaders: { cookie }, transports: ['websocket'], reconnection: false });
    },
  };
}

const once = (socket, event) => new Promise((resolve) => socket.once(event, resolve));
const emitAck = (socket, event, data) => new Promise((resolve) => socket.emit(event, data, resolve));

test('messages vocaux et appels (signalisation WebRTC)', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'agropama-'));
  const { server, io, db } = createApp({ dataDir, dbFile: ':memory:', jwtSecret: 't', admin: ADMIN, rateLimit: 1000 });
  await new Promise((r) => server.listen(0, r));
  const base = `http://localhost:${server.address().port}`;
  const sockets = [];
  try {
    const admin = client(base);
    const alice = client(base);
    const bob = client(base);
    await admin.call('POST', '/api/auth/login', ADMIN);
    for (const [c, n] of [[alice, 'alice'], [bob, 'bob']]) {
      await c.call('POST', '/api/auth/register', { name: n, email: `${n}@agropama.com`, password: 'motdepasse1' });
    }
    for (const u of (await admin.call('GET', '/api/admin/users?status=pending')).data.users) {
      await admin.call('POST', `/api/admin/users/${u.id}/approve`);
    }
    await alice.call('POST', '/api/auth/login', { email: 'alice@agropama.com', password: 'motdepasse1' });
    await bob.call('POST', '/api/auth/login', { email: 'bob@agropama.com', password: 'motdepasse1' });
    const bobId = (await alice.call('GET', '/api/users')).data.users.find((u) => u.name === 'bob').id;
    const conv = (await alice.call('POST', '/api/conversations/direct', { userId: bobId })).data.conversation;

    // Message vocal : type nettoyé et lecture directe autorisée
    const fd = new FormData();
    fd.append('file', new Blob([Buffer.alloc(2048, 1)], { type: 'audio/webm;codecs=opus' }), 'message-vocal.webm');
    let r = await alice.call('POST', `/api/conversations/${conv.id}/files`, fd);
    assert.equal(r.status, 201);
    assert.equal(r.data.message.file.type, 'audio/webm');
    const file = await bob.call('GET', r.data.message.file.url);
    assert.equal(file.status, 200);
    assert.equal(file.headers.get('content-type'), 'audio/webm');
    assert.match(file.headers.get('content-disposition'), /^inline/);

    // Configuration ICE
    r = await alice.call('GET', '/api/rtc-config');
    assert.ok(r.data.iceServers.length >= 1);

    // Appel : invitation -> sonnerie -> acceptation -> échange SDP/ICE -> fin
    const sa = alice.socket();
    const sb = bob.socket();
    sockets.push(sa, sb);
    await Promise.all([once(sa, 'connect'), once(sb, 'connect')]);

    const incoming = once(sb, 'call:incoming');
    const invite = await emitAck(sa, 'call:invite', { conversationId: conv.id, video: true });
    assert.ok(invite.callId);
    const inc = await incoming;
    assert.equal(inc.from.name, 'alice');
    assert.equal(inc.video, true);

    const busy = await emitAck(sa, 'call:invite', { conversationId: conv.id, video: false });
    assert.match(busy.error, /déjà/);

    const accepted = once(sa, 'call:accepted');
    assert.deepEqual(await emitAck(sb, 'call:accept', { callId: inc.callId }), { ok: true });
    await accepted;

    const sig = once(sb, 'call:signal');
    sa.emit('call:signal', { callId: invite.callId, data: { sdp: { type: 'offer', sdp: 'v=0' } } });
    assert.equal((await sig).data.sdp.type, 'offer');
    const back = once(sa, 'call:signal');
    sb.emit('call:signal', { callId: invite.callId, data: { candidate: { candidate: 'x' } } });
    assert.equal((await back).data.candidate.candidate, 'x');

    const ended = once(sb, 'call:ended');
    const logged = once(sb, 'message:new');
    sa.emit('call:end', { callId: invite.callId });
    assert.equal((await ended).reason, 'completed');
    assert.match((await logged).body, /Appel vidéo · \d+ s/);

    // Appel refusé
    const inc2 = once(sb, 'call:incoming');
    const inv2 = await emitAck(sa, 'call:invite', { conversationId: conv.id, video: false });
    await inc2;
    const ended2 = once(sa, 'call:ended');
    const logged2 = once(sa, 'message:new');
    sb.emit('call:decline', { callId: inv2.callId });
    assert.equal((await ended2).reason, 'declined');
    assert.equal((await logged2).body, '📞 Appel vocal refusé');

    // Pas d'appel dans un groupe
    const group = (await alice.call('POST', '/api/conversations/group', { name: 'Équipe', memberIds: [bobId] })).data.conversation;
    assert.match((await emitAck(sa, 'call:invite', { conversationId: group.id })).error, /individuelles/);

    // Correspondant hors ligne -> appel manqué enregistré
    sb.close();
    await new Promise((r) => setTimeout(r, 100));
    const off = await emitAck(sa, 'call:invite', { conversationId: conv.id, video: false });
    assert.match(off.error, /pas connecté/);
    const msgs = (await alice.call('GET', `/api/conversations/${conv.id}/messages`)).data.messages;
    assert.equal(msgs.at(-1).body, '📞 Appel vocal manqué');
  } finally {
    sockets.forEach((s) => s.close());
    io.close();
    await new Promise((r) => server.close(r));
    db.close();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
