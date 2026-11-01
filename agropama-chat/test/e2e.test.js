'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const E2E = require('../public/e2e.js');

async function user(id, kid) {
  const identity = await E2E.generateIdentity();
  const pub = await E2E.exportPublic(identity);
  return { id, kid, identity, pub, enc: await E2E.importEncPublic(pub.enc), sign: await E2E.importSignPublic(pub.sign) };
}

test('chiffrement de bout en bout : messages, falsifications, sauvegarde des clés', async () => {
  const alice = await user(1, 10);
  const bob = await user(2, 20);
  const eve = await user(3, 30);

  const env = await E2E.encryptMessage({
    payload: { t: 'text', text: 'Livraison de semences mardi 8 h' },
    conversationId: 5, senderId: 1, senderKeyId: 10, signPriv: alice.identity.signPriv,
    recipients: [alice, bob].map((u) => ({ id: u.id, kid: u.kid, enc: u.enc })),
  });
  assert.ok(!JSON.stringify(env).includes('semences'), 'aucun texte en clair dans l’enveloppe');

  const ctx = (u) => ({ myId: u.id, myKeyId: u.kid, encPriv: u.identity.encPriv, senderSignPub: alice.sign, conversationId: 5, senderId: 1 });
  assert.equal((await E2E.decryptMessage(env, ctx(bob))).text, 'Livraison de semences mardi 8 h');
  assert.equal((await E2E.decryptMessage(env, ctx(alice))).text, 'Livraison de semences mardi 8 h');

  // Eve (non destinataire) ne peut pas lire
  await assert.rejects(E2E.decryptMessage(env, ctx(eve)), (e) => e.code === 'nokey');
  // Le serveur modifie le texte chiffré -> signature invalide
  const tampered = { ...env, ct: env.ct.slice(0, -4) + (env.ct.endsWith('AAA=') ? 'BBB=' : 'AAA=') };
  await assert.rejects(E2E.decryptMessage(tampered, ctx(bob)), (e) => e.code === 'signature');
  // Le serveur déplace le message dans une autre discussion / l'attribue à un autre
  await assert.rejects(E2E.decryptMessage(env, { ...ctx(bob), conversationId: 6 }), (e) => e.code === 'forged');
  await assert.rejects(E2E.decryptMessage(env, { ...ctx(bob), senderId: 3 }), (e) => e.code === 'forged');
  // Eve signe un message en se faisant passer pour Alice -> refus
  const fake = await E2E.encryptMessage({ payload: { t: 'text', text: 'virement urgent' }, conversationId: 5, senderId: 1, senderKeyId: 10, signPriv: eve.identity.signPriv, recipients: [bob] });
  await assert.rejects(E2E.decryptMessage(fake, ctx(bob)), (e) => e.code === 'signature');
  // Clé du destinataire remplacée (ancienne clé)
  await assert.rejects(E2E.decryptMessage(env, { ...ctx(bob), myKeyId: 21 }), (e) => e.code === 'oldkey');

  // Sauvegarde des clés protégée par mot de passe
  const wrapped = await E2E.wrapPrivate(bob.identity, 'Agropama2026!');
  assert.ok(!JSON.stringify(wrapped).includes(bob.pub.enc.x));
  await assert.rejects(E2E.unwrapPrivate(wrapped, 'mauvais-mot-de-passe'));
  const restored = await E2E.unwrapPrivate(wrapped, 'Agropama2026!');
  assert.equal((await E2E.decryptMessage(env, { ...ctx(bob), encPriv: restored.encPriv })).text, 'Livraison de semences mardi 8 h');

  // Fichiers
  const data = new TextEncoder().encode('contenu photo');
  const f = await E2E.encryptFile(data);
  assert.notDeepEqual(Buffer.from(f.data), Buffer.from(data));
  assert.equal(new TextDecoder().decode(await E2E.decryptFile(f.data, f.key, f.iv)), 'contenu photo');

  // Empreinte de connexion : déterministe, ne révèle pas le mot de passe
  const s1 = await E2E.authSecret('Bob@Agropama.com ', 'Agropama2026!');
  assert.equal(s1, await E2E.authSecret('bob@agropama.com', 'Agropama2026!'));
  assert.match(s1, /^[0-9a-f]{64}$/);

  // Code de sécurité identique des deux côtés
  const fa = await E2E.fingerprint(alice.pub);
  const fb = await E2E.fingerprint(bob.pub);
  assert.deepEqual(await E2E.safetyNumber(1, fa, 2, fb), await E2E.safetyNumber(2, fb, 1, fa));

  // Signature des appels
  const sig = await E2E.signText(alice.identity.signPriv, 'sdp');
  assert.equal(await E2E.verifyText(alice.sign, 'sdp', sig), true);
  assert.equal(await E2E.verifyText(alice.sign, 'sdp modifié', sig), false);

  // Politique de mot de passe
  assert.ok(E2E.passwordProblem('12345678'));
  assert.ok(E2E.passwordProblem('abcdefghij'));
  assert.equal(E2E.passwordProblem('Semences2026'), null);
});
