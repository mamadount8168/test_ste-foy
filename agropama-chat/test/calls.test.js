'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { E2E, startServer, team, once, emitAck } = require('./helpers');

test('fichiers chiffrés et appels (signalisation WebRTC)', async () => {
  const { base, close } = await startServer();
  const sockets = [];
  try {
    const { alice, bob } = await team(base, ['alice', 'bob']);
    const conv = (await alice.call('POST', '/api/conversations/direct', { userId: bob.id })).data.conversation;

    // Message vocal chiffré : le serveur ne reçoit que des octets illisibles
    const audio = Buffer.from('OggS fausse voix');
    const f = await E2E.encryptFile(audio);
    const env = await alice.envelope(conv, { t: 'file', kind: 'audio', name: 'vocal.webm', type: 'audio/webm', size: audio.length, key: f.key, iv: f.iv });
    const fd = new FormData();
    fd.append('envelope', JSON.stringify(env));
    fd.append('file', new Blob([f.data]), 'x.bin');
    let r = await alice.call('POST', `/api/conversations/${conv.id}/files`, fd);
    assert.equal(r.status, 201);
    const file = await bob.call('GET', r.data.message.file.url);
    assert.equal(file.headers.get('content-type'), 'application/octet-stream');
    assert.ok(!file.data.includes(Buffer.from('fausse voix')));
    const meta = await bob.decrypt(r.data.message, (await bob.call('GET', `/api/keys/${alice.id}`)).data.keys.at(-1));
    assert.equal(Buffer.from(await E2E.decryptFile(file.data, meta.key, meta.iv)).toString(), 'OggS fausse voix');

    // Fichier sans enveloppe chiffrée refusé
    const bad = new FormData();
    bad.append('file', new Blob([Buffer.from('clair')]), 'clair.txt');
    assert.equal((await alice.call('POST', `/api/conversations/${conv.id}/files`, bad)).status, 400);

    // Configuration ICE
    r = await alice.call('GET', '/api/rtc-config');
    assert.ok(r.data.iceServers.length >= 1);

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

    const inc2 = once(sb, 'call:incoming');
    const inv2 = await emitAck(sa, 'call:invite', { conversationId: conv.id, video: false });
    await inc2;
    const ended2 = once(sa, 'call:ended');
    const logged2 = once(sa, 'message:new');
    sb.emit('call:decline', { callId: inv2.callId });
    assert.equal((await ended2).reason, 'declined');
    assert.equal((await logged2).body, '📞 Appel vocal refusé');

    const group = (await alice.call('POST', '/api/conversations/group', { name: 'Équipe', memberIds: [bob.id] })).data.conversation;
    assert.match((await emitAck(sa, 'call:invite', { conversationId: group.id })).error, /individuelles/);

    sb.close();
    await new Promise((res) => setTimeout(res, 100));
    const off = await emitAck(sa, 'call:invite', { conversationId: conv.id, video: false });
    assert.match(off.error, /pas connecté/);
    const msgs = (await alice.call('GET', `/api/conversations/${conv.id}/messages`)).data.messages;
    assert.equal(msgs.at(-1).body, '📞 Appel vocal manqué');
  } finally {
    sockets.forEach((s) => s.close());
    await close();
  }
});
