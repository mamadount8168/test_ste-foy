'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { DatabaseSync } = require('node:sqlite');
const path = require('node:path');
const { ADMIN, startServer, client, team, once } = require('./helpers');

test('inscription, validation, messagerie chiffrée de bout en bout, sessions révocables', async () => {
  const { base, dataDir, close } = await startServer();
  try {
    const admin = client(base);
    const alice = client(base);
    const bob = client(base);

    // Inscription : compte en attente, mot de passe jamais transmis en clair
    assert.equal((await alice.register('Alice Diallo', 'alice@agropama.com', 'Semences2026')).status, 201);
    assert.equal((await bob.register('Bob Ndiaye', 'bob@agropama.com', 'Recolte2026x')).status, 201);
    assert.equal((await alice.register('Alice bis', 'ALICE@agropama.com', 'Autre2026xx')).status, 201, 'réponse identique : pas d’énumération');
    assert.equal((await alice.call('POST', '/api/auth/register', { name: 'X', email: 'x@agropama.com', password: '12345678' })).status, 400);

    let r = await alice.login('alice@agropama.com', 'Semences2026');
    assert.equal(r.status, 403);
    assert.equal(r.data.status, 'pending');

    assert.equal((await admin.login(ADMIN.email, ADMIN.password)).status, 200);
    const pending = (await admin.call('GET', '/api/admin/users?status=pending')).data.users;
    assert.equal(pending.length, 2);
    for (const u of pending) assert.equal((await admin.call('POST', `/api/admin/users/${u.id}/approve`)).status, 200);

    assert.equal((await alice.login('alice@agropama.com', 'Semences2026')).status, 200);
    assert.equal((await bob.login('bob@agropama.com', 'Recolte2026x')).status, 200);
    assert.equal((await alice.call('GET', '/api/admin/users')).status, 403);

    // Clés de chiffrement
    await alice.setupKeys('Semences2026');
    await bob.setupKeys('Recolte2026x');
    assert.equal((await alice.call('POST', '/api/keys', { enc: {}, sign: {}, wrapped: {} })).status, 400);
    const me = (await alice.call('GET', '/api/keys/me')).data.key;
    assert.ok(me.wrapped.ct, 'sauvegarde chiffrée disponible');

    // Discussion + message chiffré en temps réel
    const bobSocket = bob.socket();
    await once(bobSocket, 'connect');
    const conv = (await alice.call('POST', '/api/conversations/direct', { userId: bob.id })).data.conversation;
    assert.equal(conv.title, 'Bob Ndiaye');
    assert.ok(conv.members.every((m) => m.keyId && m.publicKeys));

    assert.equal((await alice.call('POST', `/api/conversations/${conv.id}/messages`, { body: 'en clair' })).status, 400, 'texte en clair refusé');

    const received = once(bobSocket, 'message:new');
    r = await alice.sendText(conv, 'La livraison est prête.');
    assert.equal(r.status, 201);
    const msg = await received;
    assert.equal(msg.body, null);
    assert.ok(!JSON.stringify(msg).includes('livraison'));
    const aliceKeys = (await bob.call('GET', `/api/keys/${alice.id}`)).data.keys.at(-1);
    assert.equal((await bob.decrypt(msg, aliceKeys)).text, 'La livraison est prête.');

    // La base de données ne contient aucun texte en clair
    const db = new DatabaseSync(path.join(dataDir, 'test.db'), { readOnly: true });
    assert.ok(!JSON.stringify(db.prepare('SELECT * FROM messages').all()).includes('livraison'));
    db.close();

    // Non-lus
    assert.equal((await bob.call('GET', '/api/conversations')).data.conversations[0].unread, 1);
    await bob.call('POST', `/api/conversations/${conv.id}/read`);
    assert.equal((await bob.call('GET', '/api/conversations')).data.conversations[0].unread, 0);

    // Un non-membre ne lit rien
    assert.equal((await admin.call('GET', `/api/conversations/${conv.id}/messages`)).status, 404);

    // Déconnexion : le cookie volé ne fonctionne plus
    const stolen = bob.cookie;
    await bob.call('POST', '/api/auth/logout');
    assert.equal((await fetch(base + '/api/me', { headers: { cookie: stolen } })).status, 401);

    // Suspension : session coupée immédiatement et définitivement
    await bob.login('bob@agropama.com', 'Recolte2026x');
    const s2 = bob.socket();
    await once(s2, 'connect');
    const revoked = once(s2, 'session:revoked');
    await admin.call('POST', `/api/admin/users/${bob.id}/suspend`);
    await revoked;
    assert.equal((await bob.call('GET', '/api/me')).status, 401);
    await admin.call('POST', `/api/admin/users/${bob.id}/reactivate`);
    assert.equal((await bob.call('GET', '/api/me')).status, 401, 'il faut se reconnecter après réactivation');
    bobSocket.close();
    s2.close();
  } finally {
    await close();
  }
});

test('verrouillage du compte après 5 échecs et protections CSRF', async () => {
  const { base, close } = await startServer();
  try {
    const { alice } = await team(base, ['alice']);
    const brute = client(base);
    const statuses = [];
    for (let i = 0; i < 6; i++) statuses.push((await brute.login('alice@agropama.com', `faux${i}`)).status);
    assert.deepEqual(statuses, [401, 401, 401, 401, 429, 429]);
    assert.equal((await brute.login('alice@agropama.com', 'Semences2026')).status, 429, 'même le bon mot de passe attend 15 min');

    const r = await alice.call('POST', '/api/conversations/group', { name: 'x', memberIds: [1] }, { origin: 'https://pirate.example' });
    assert.equal(r.status, 403);
  } finally {
    await close();
  }
});
