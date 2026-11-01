'use strict';

/*
 * Gestion des clés de chiffrement sur l'appareil et (dé)chiffrement des messages.
 * - Les clés privées sont conservées dans IndexedDB sous forme NON exportable :
 *   même un script malveillant ne peut pas les copier, seulement les utiliser sur place.
 * - Les clés publiques des collègues sont « épinglées » (TOFU) : si le serveur présente
 *   une autre clé pour quelqu'un, l'application le signale.
 */
const Secure = (() => {
  const DB_NAME = 'agropama-secure';
  let me = null;
  let keys = null; // { userId, keyId, encPriv, signPriv, pub }
  const serverKeys = new Map(); // userId -> Promise<[{id, enc, sign, active}]>
  const imported = new Map(); // `${kind}:${kid}` -> CryptoKey
  const results = new Map(); // messageId -> { payload } | { error }
  const pending = new Map(); // messageId -> Promise
  let onUpdate = () => {};

  // ---------------------------------------------------------------- IndexedDB
  function idb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore('keys', { keyPath: 'userId' });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  async function idbDo(mode, fn) {
    const db = await idb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('keys', mode);
      const req = fn(tx.objectStore('keys'));
      tx.oncomplete = () => { db.close(); resolve(req && req.result); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  // ---------------------------------------------------------------- épinglage des clés
  const pinKey = () => `agropama.pins.${me.id}`;
  function readPins() {
    try { return JSON.parse(localStorage.getItem(pinKey()) || '{}'); } catch { return {}; }
  }
  function writePins(p) {
    try { localStorage.setItem(pinKey(), JSON.stringify(p)); } catch { /* stockage indisponible */ }
  }

  /** Enregistre la clé actuelle d'un collègue ; renvoie true si elle a changé depuis la dernière fois. */
  async function observe(user) {
    if (!me || !user || !user.keyId || !user.publicKeys || user.id === me.id) return false;
    const pins = readPins();
    const entry = pins[user.id] || { current: null, seen: {} };
    if (entry.seen[user.keyId]) {
      entry.current = entry.current || user.keyId;
      pins[user.id] = entry;
      writePins(pins);
      return false;
    }
    const fp = await E2E.fingerprint(user.publicKeys);
    const changed = entry.current !== null && entry.current !== user.keyId;
    entry.seen[user.keyId] = fp;
    entry.current = user.keyId;
    pins[user.id] = entry;
    writePins(pins);
    return changed;
  }

  // ---------------------------------------------------------------- session de chiffrement
  async function nonExtractable(identity) {
    const s = crypto.subtle;
    return {
      encPriv: await s.importKey('pkcs8', await s.exportKey('pkcs8', identity.encPriv), { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']),
      signPriv: await s.importKey('pkcs8', await s.exportKey('pkcs8', identity.signPriv), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']),
    };
  }

  async function createKeys(password, replace) {
    const identity = await E2E.generateIdentity();
    const pub = await E2E.exportPublic(identity);
    const wrapped = await E2E.wrapPrivate(identity, password);
    const { key } = await api('/api/keys', { method: 'POST', body: { ...pub, wrapped, replace } });
    return { keyId: key.id, ...(await nonExtractable(identity)), pub };
  }

  /** Après la connexion : récupère (ou crée) les clés et les garde sur l'appareil. */
  async function unlock(user, password) {
    me = user;
    const { key } = await api('/api/keys/me');
    let k = null;
    if (key && key.wrapped) {
      try {
        const priv = await E2E.unwrapPrivate(key.wrapped, password);
        k = { keyId: key.id, ...priv, pub: { enc: key.enc, sign: key.sign } };
      } catch {
        const ok = confirm(
          "Votre mot de passe a été réinitialisé : vos anciennes clés de chiffrement ne peuvent plus être ouvertes.\n\n" +
          'De nouvelles clés vont être créées. Les anciens messages resteront illisibles sur vos appareils, ' +
          'et vos collègues verront que votre code de sécurité a changé.\n\nContinuer ?'
        );
        if (!ok) throw new Error('Connexion annulée.');
      }
    }
    if (!k) k = await createKeys(password, Boolean(key));
    keys = { userId: user.id, ...k };
    await idbDo('readwrite', (st) => st.put(keys));
    return keys;
  }

  /** À l'ouverture de l'application : recharge les clés déjà présentes sur l'appareil. */
  async function load(user) {
    me = user;
    try {
      const rec = await idbDo('readonly', (st) => st.get(user.id));
      if (!rec || rec.keyId !== user.keyId) return false;
      keys = rec;
      return true;
    } catch {
      return false;
    }
  }

  async function forget() {
    keys = null;
    results.clear();
    try { await idbDo('readwrite', (st) => st.clear()); } catch { /* ignore */ }
  }

  /** Changement de mot de passe : rechiffre la sauvegarde des clés avec le nouveau mot de passe. */
  async function rewrap(currentPassword, newPassword) {
    const { key } = await api('/api/keys/me');
    if (!key || !key.wrapped) return undefined;
    const priv = await E2E.unwrapPrivate(key.wrapped, currentPassword, true);
    return E2E.wrapPrivate(priv, newPassword);
  }

  // ---------------------------------------------------------------- clés publiques
  function keysOf(userId, force = false) {
    if (force || !serverKeys.has(userId)) {
      serverKeys.set(userId, api(`/api/keys/${userId}`).then((r) => r.keys).catch((e) => { serverKeys.delete(userId); throw e; }));
    }
    return serverKeys.get(userId);
  }

  async function cachedImport(kind, kid, jwk) {
    const id = `${kind}:${kid}`;
    if (!imported.has(id)) imported.set(id, kind === 'enc' ? E2E.importEncPublic(jwk) : E2E.importSignPublic(jwk));
    return imported.get(id);
  }

  /** Clé de signature d'un collègue, uniquement si elle a déjà été vue (protection contre une clé injectée). */
  async function signKeyFor(userId, kid) {
    if (userId === me.id) {
      if (kid === keys.keyId) return cachedImport('sign', kid, keys.pub.sign);
    }
    let list = await keysOf(userId);
    let k = list.find((x) => x.id === kid);
    if (!k) {
      list = await keysOf(userId, true);
      k = list.find((x) => x.id === kid);
    }
    if (!k) throw new E2E.DecryptError('unknownkey', 'Clé inconnue');
    if (userId !== me.id) {
      const pins = readPins();
      const entry = pins[userId];
      const fp = await E2E.fingerprint({ enc: k.enc, sign: k.sign });
      if (entry && entry.seen[kid]) {
        if (entry.seen[kid] !== fp) throw new E2E.DecryptError('unknownkey', 'Clé falsifiée');
      } else if (k.active && (!entry || !entry.current || entry.current === kid)) {
        await observe({ id: userId, keyId: kid, publicKeys: { enc: k.enc, sign: k.sign } });
      } else {
        throw new E2E.DecryptError('unknownkey', 'Clé jamais vérifiée');
      }
    }
    return cachedImport('sign', kid, k.sign);
  }

  function invalidate(userId) {
    serverKeys.delete(userId);
  }

  // ---------------------------------------------------------------- messages
  async function encryptFor(conv, payload) {
    if (!keys) throw new Error('Chiffrement non initialisé : reconnectez-vous.');
    const missing = conv.members.filter((m) => !m.keyId || !m.publicKeys);
    if (missing.length) {
      throw new Error(`${missing.map((m) => m.name).join(', ')} doit d'abord se connecter une fois à la nouvelle version de l'application.`);
    }
    const recipients = [];
    for (const m of conv.members) {
      if (m.id !== me.id) await observe(m);
      const enc = m.id === me.id ? keys.pub.enc : m.publicKeys.enc;
      const kid = m.id === me.id ? keys.keyId : m.keyId;
      recipients.push({ id: m.id, kid, enc: await cachedImport('enc', kid, enc) });
    }
    return E2E.encryptMessage({ payload, conversationId: conv.id, senderId: me.id, senderKeyId: keys.keyId, signPriv: keys.signPriv, recipients });
  }

  const ERRORS = {
    signature: '⚠️ Message rejeté : signature invalide (possible falsification)',
    forged: '⚠️ Message falsifié rejeté',
    unknownkey: "⚠️ Message rejeté : clé de l'expéditeur non reconnue",
    nokey: '🔒 Message envoyé avant votre arrivée dans la discussion',
    oldkey: '🔒 Message chiffré pour une ancienne clé (mot de passe réinitialisé)',
  };

  async function decrypt(m) {
    if (results.has(m.id)) return results.get(m.id);
    if (pending.has(m.id)) return pending.get(m.id);
    const p = (async () => {
      let out;
      try {
        const env = m.envelope;
        const signPub = await signKeyFor(m.senderId, env.sk);
        const payload = await E2E.decryptMessage(env, {
          myId: me.id, myKeyId: keys.keyId, encPriv: keys.encPriv, senderSignPub: signPub,
          conversationId: m.conversationId, senderId: m.senderId,
        });
        out = { payload };
      } catch (err) {
        out = { error: ERRORS[err && err.code] || '🔒 Impossible de déchiffrer ce message' };
      }
      results.set(m.id, out);
      pending.delete(m.id);
      onUpdate();
      return out;
    })();
    pending.set(m.id, p);
    return p;
  }

  /** Résultat immédiat si déjà déchiffré, sinon lance le déchiffrement et renvoie undefined. */
  function peek(m) {
    if (!m.envelope || !keys) return null;
    if (results.has(m.id)) return results.get(m.id);
    decrypt(m);
    return undefined;
  }

  function remember(messageId, payload) {
    results.set(messageId, { payload });
  }

  // ---------------------------------------------------------------- vérification et appels
  async function safetyNumberWith(peer) {
    const mine = await E2E.fingerprint(keys.pub);
    const theirs = await E2E.fingerprint(peer.publicKeys);
    return E2E.safetyNumber(me.id, mine, peer.id, theirs);
  }

  async function shortCode(user) {
    if (!user.publicKeys) return null;
    return (await E2E.fingerprint(user.publicKeys)).slice(0, 20).replace(/(.{4})/g, '$1 ').trim().toUpperCase();
  }

  async function signText(text) {
    return { sig: await E2E.signText(keys.signPriv, text), kid: keys.keyId };
  }

  async function verifyFrom(userId, text, sig, kid) {
    try {
      const pins = readPins()[userId];
      if (!pins || pins.current !== kid) return false; // seule la clé actuellement épinglée est acceptée
      return E2E.verifyText(await signKeyFor(userId, kid), text, sig);
    } catch {
      return false;
    }
  }

  return {
    unlock, load, forget, rewrap, observe, invalidate, encryptFor, decrypt, peek, remember,
    safetyNumberWith, shortCode, signText, verifyFrom,
    get keyId() { return keys && keys.keyId; },
    get ready() { return Boolean(keys); },
    set onUpdate(fn) { onUpdate = fn; },
  };
})();
