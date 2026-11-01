'use strict';

/*
 * Agropama — chiffrement de bout en bout (WebCrypto, aucune dépendance).
 *
 * Identité d'un employé : deux paires de clés P-256 créées sur son appareil
 *   - ECDH  : pour recevoir des messages chiffrés ;
 *   - ECDSA : pour signer ce qu'il envoie (authenticité, anti-usurpation).
 * Les clés privées sont sauvegardées sur le serveur uniquement sous forme chiffrée,
 * avec une clé dérivée du mot de passe (PBKDF2, 310 000 itérations). Le serveur ne reçoit
 * jamais le mot de passe : la connexion utilise une empreinte dérivée séparément.
 *
 * Message : clé AES-256-GCM aléatoire par message ; cette clé est chiffrée pour chaque
 * destinataire via ECDH éphémère + HKDF, puis l'ensemble est signé par l'expéditeur.
 * Le serveur ne stocke et ne relaie que du texte chiffré.
 */
(function (root) {
  const cryptoApi = root.crypto || (typeof require === 'function' ? require('node:crypto').webcrypto : null);
  const subtle = cryptoApi.subtle;
  const te = new TextEncoder();
  const td = new TextDecoder();

  const AUTH_ITERATIONS = 100000;
  const KEY_ITERATIONS = 310000;
  const ECDH = { name: 'ECDH', namedCurve: 'P-256' };
  const ECDSA = { name: 'ECDSA', namedCurve: 'P-256' };
  const SIGN = { name: 'ECDSA', hash: 'SHA-256' };

  // ---------------------------------------------------------------- utilitaires
  function b64(bytes) {
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  }
  function unb64(str) {
    if (typeof str !== 'string') throw new Error('format');
    const s = atob(str);
    const u8 = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    return u8;
  }
  const random = (n) => cryptoApi.getRandomValues(new Uint8Array(n));
  const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');

  async function pbkdf2(password, salt, iterations) {
    const base = await subtle.importKey('raw', te.encode(String(password).normalize('NFKC')), 'PBKDF2', false, ['deriveBits', 'deriveKey']);
    return { base, params: { name: 'PBKDF2', hash: 'SHA-256', salt, iterations } };
  }

  function publicJwk(jwk) {
    const ok = (v) => typeof v === 'string' && /^[A-Za-z0-9_-]{43}$/.test(v);
    if (!jwk || jwk.kty !== 'EC' || jwk.crv !== 'P-256' || !ok(jwk.x) || !ok(jwk.y)) throw new Error('clé publique invalide');
    return { kty: 'EC', crv: 'P-256', x: jwk.x, y: jwk.y };
  }

  // ---------------------------------------------------------------- authentification
  /** Empreinte envoyée au serveur à la place du mot de passe (le mot de passe ne quitte jamais l'appareil). */
  async function authSecret(email, password) {
    const salt = te.encode('agropama-auth-v1|' + String(email).trim().toLowerCase());
    const { base, params } = await pbkdf2(password, salt, AUTH_ITERATIONS);
    return hex(await subtle.deriveBits(params, base, 256));
  }

  /** Règles de mot de passe (vérifiées sur l'appareil, le serveur ne voyant jamais le mot de passe). */
  function passwordProblem(password, email = '') {
    const p = String(password || '');
    if (p.length < 10) return 'Le mot de passe doit contenir au moins 10 caractères.';
    if (!/[A-Za-zÀ-ÿ]/.test(p) || !/\d/.test(p)) return 'Le mot de passe doit contenir des lettres et des chiffres.';
    if (/^(.)\1+$/.test(p) || /^(0123456789|1234567890|azertyuiop|qwertyuiop|motdepasse|password)/i.test(p)) {
      return 'Ce mot de passe est trop facile à deviner.';
    }
    const local = String(email).split('@')[0].toLowerCase();
    if (local.length >= 4 && p.toLowerCase().includes(local)) return 'Le mot de passe ne doit pas contenir votre identifiant.';
    return null;
  }

  // ---------------------------------------------------------------- identité
  async function generateIdentity() {
    const enc = await subtle.generateKey(ECDH, true, ['deriveBits']);
    const sign = await subtle.generateKey(ECDSA, true, ['sign', 'verify']);
    return { encPriv: enc.privateKey, encPub: enc.publicKey, signPriv: sign.privateKey, signPub: sign.publicKey };
  }

  async function exportPublic(identity) {
    return {
      enc: publicJwk(await subtle.exportKey('jwk', identity.encPub)),
      sign: publicJwk(await subtle.exportKey('jwk', identity.signPub)),
    };
  }

  /** Chiffre les clés privées avec le mot de passe (sauvegarde stockée sur le serveur). */
  async function wrapPrivate(identity, password) {
    const salt = random(16);
    const iv = random(12);
    const { base, params } = await pbkdf2(password, salt, KEY_ITERATIONS);
    const kek = await subtle.deriveKey(params, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt']);
    const payload = te.encode(JSON.stringify({
      enc: b64(await subtle.exportKey('pkcs8', identity.encPriv)),
      sign: b64(await subtle.exportKey('pkcs8', identity.signPriv)),
    }));
    const ct = await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: te.encode('agropama-keys-v1') }, kek, payload);
    return { v: 1, kdf: 'PBKDF2-SHA256', iter: KEY_ITERATIONS, salt: b64(salt), iv: b64(iv), ct: b64(ct) };
  }

  /** Déchiffre la sauvegarde ; échoue si le mot de passe est incorrect. */
  async function unwrapPrivate(wrapped, password, extractable = false) {
    const iter = Math.min(Math.max(Number(wrapped.iter) || KEY_ITERATIONS, 100000), 2000000);
    const { base, params } = await pbkdf2(password, unb64(wrapped.salt), iter);
    const kek = await subtle.deriveKey(params, base, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
    const plain = await subtle.decrypt({ name: 'AES-GCM', iv: unb64(wrapped.iv), additionalData: te.encode('agropama-keys-v1') }, kek, unb64(wrapped.ct));
    const keys = JSON.parse(td.decode(plain));
    return {
      encPriv: await subtle.importKey('pkcs8', unb64(keys.enc), ECDH, extractable, ['deriveBits']),
      signPriv: await subtle.importKey('pkcs8', unb64(keys.sign), ECDSA, extractable, ['sign']),
    };
  }

  const importEncPublic = (jwk) => subtle.importKey('jwk', publicJwk(jwk), ECDH, true, []);
  const importSignPublic = (jwk) => subtle.importKey('jwk', publicJwk(jwk), ECDSA, true, ['verify']);

  // ---------------------------------------------------------------- vérification d'identité
  async function fingerprint(pub) {
    const e = publicJwk(pub.enc);
    const s = publicJwk(pub.sign);
    return hex(await subtle.digest('SHA-256', te.encode(`${e.x}.${e.y}.${s.x}.${s.y}`)));
  }

  /** Code de sécurité commun à deux employés (60 chiffres), à comparer de vive voix. */
  async function safetyNumber(idA, fpA, idB, fpB) {
    const parts = [[Number(idA), fpA], [Number(idB), fpB]].sort((x, y) => x[0] - y[0]);
    const h1 = new Uint8Array(await subtle.digest('SHA-256', te.encode(`agropama-sn-v1|${parts[0][1]}|${parts[1][1]}`)));
    const h2 = new Uint8Array(await subtle.digest('SHA-256', h1));
    const bytes = new Uint8Array([...h1, ...h2]);
    const groups = [];
    for (let i = 0; i < 12; i++) {
      const v = ((bytes[i * 4] << 24) | (bytes[i * 4 + 1] << 16) | (bytes[i * 4 + 2] << 8) | bytes[i * 4 + 3]) >>> 0;
      groups.push(String(v % 100000).padStart(5, '0'));
    }
    return groups;
  }

  // ---------------------------------------------------------------- messages
  function toSign(env) {
    const keys = Object.keys(env.keys || {}).sort().map((k) => [k, env.keys[k].kid, env.keys[k].iv, env.keys[k].k]);
    return te.encode(JSON.stringify(['agropama-msg-v1', env.v, env.c, env.s, env.sk, env.epk, env.iv, env.ct, keys]));
  }

  async function kekFor(sharedBits, epkRaw, recipientId, kid, usage) {
    const base = await subtle.importKey('raw', sharedBits, 'HKDF', false, ['deriveKey']);
    return subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: epkRaw, info: te.encode(`agropama-wrap-v1|${recipientId}|${kid}`) },
      base, { name: 'AES-GCM', length: 256 }, false, [usage]
    );
  }

  /**
   * recipients : [{ id, kid, enc: CryptoKey publique ECDH }] — inclure l'expéditeur pour qu'il relise ses messages.
   */
  async function encryptMessage({ payload, conversationId, senderId, senderKeyId, signPriv, recipients }) {
    const contentKeyRaw = random(32);
    const contentKey = await subtle.importKey('raw', contentKeyRaw, 'AES-GCM', false, ['encrypt']);
    const iv = random(12);
    const env = { v: 1, c: Number(conversationId), s: Number(senderId), sk: Number(senderKeyId) };
    const aad = te.encode(`agropama-v1|${env.c}|${env.s}|${env.sk}`);
    env.iv = b64(iv);
    env.ct = b64(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad }, contentKey, te.encode(JSON.stringify(payload))));
    const eph = await subtle.generateKey(ECDH, true, ['deriveBits']);
    const epkRaw = new Uint8Array(await subtle.exportKey('raw', eph.publicKey));
    env.epk = b64(epkRaw);
    env.keys = {};
    for (const r of recipients) {
      const shared = await subtle.deriveBits({ name: 'ECDH', public: r.enc }, eph.privateKey, 256);
      const kek = await kekFor(shared, epkRaw, r.id, r.kid, 'encrypt');
      const wiv = random(12);
      const k = await subtle.encrypt({ name: 'AES-GCM', iv: wiv, additionalData: te.encode(`${env.c}|${r.id}|${r.kid}`) }, kek, contentKeyRaw);
      env.keys[String(r.id)] = { kid: Number(r.kid), iv: b64(wiv), k: b64(k) };
    }
    env.sig = b64(await subtle.sign(SIGN, signPriv, toSign(env)));
    return env;
  }

  class DecryptError extends Error {
    constructor(code, message) { super(message); this.code = code; }
  }

  async function decryptMessage(env, { myId, myKeyId, encPriv, senderSignPub, conversationId, senderId }) {
    if (!env || env.v !== 1) throw new DecryptError('format', 'Format inconnu');
    if (Number(env.c) !== Number(conversationId) || Number(env.s) !== Number(senderId)) {
      throw new DecryptError('forged', 'Message déplacé ou expéditeur usurpé');
    }
    const valid = await subtle.verify(SIGN, senderSignPub, unb64(env.sig), toSign(env));
    if (!valid) throw new DecryptError('signature', 'Signature invalide');
    const mine = env.keys && env.keys[String(myId)];
    if (!mine) throw new DecryptError('nokey', "Message chiffré avant votre arrivée");
    if (Number(mine.kid) !== Number(myKeyId)) throw new DecryptError('oldkey', 'Message chiffré pour une ancienne clé');
    const epkRaw = unb64(env.epk);
    const epk = await subtle.importKey('raw', epkRaw, ECDH, false, []);
    const shared = await subtle.deriveBits({ name: 'ECDH', public: epk }, encPriv, 256);
    const kek = await kekFor(shared, epkRaw, myId, mine.kid, 'decrypt');
    const contentKeyRaw = await subtle.decrypt({ name: 'AES-GCM', iv: unb64(mine.iv), additionalData: te.encode(`${env.c}|${myId}|${mine.kid}`) }, kek, unb64(mine.k));
    const contentKey = await subtle.importKey('raw', contentKeyRaw, 'AES-GCM', false, ['decrypt']);
    const aad = te.encode(`agropama-v1|${env.c}|${env.s}|${env.sk}`);
    const plain = await subtle.decrypt({ name: 'AES-GCM', iv: unb64(env.iv), additionalData: aad }, contentKey, unb64(env.ct));
    return JSON.parse(td.decode(plain));
  }

  // ---------------------------------------------------------------- fichiers
  async function encryptFile(data) {
    const raw = random(32);
    const iv = random(12);
    const key = await subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt']);
    const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv }, key, data));
    return { key: b64(raw), iv: b64(iv), data: ct };
  }

  async function decryptFile(data, keyB64, ivB64) {
    const key = await subtle.importKey('raw', unb64(keyB64), 'AES-GCM', false, ['decrypt']);
    return subtle.decrypt({ name: 'AES-GCM', iv: unb64(ivB64) }, key, data);
  }

  // ---------------------------------------------------------------- signatures (appels)
  async function signText(signPriv, text) {
    return b64(await subtle.sign(SIGN, signPriv, te.encode(text)));
  }
  async function verifyText(signPub, text, sig) {
    try {
      return await subtle.verify(SIGN, signPub, unb64(sig), te.encode(text));
    } catch {
      return false;
    }
  }

  const api = {
    authSecret, passwordProblem, generateIdentity, exportPublic, wrapPrivate, unwrapPrivate,
    importEncPublic, importSignPublic, fingerprint, safetyNumber,
    encryptMessage, decryptMessage, DecryptError, encryptFile, decryptFile, signText, verifyText,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.E2E = api;
})(typeof globalThis !== 'undefined' ? globalThis : window);
