'use strict';

const crypto = require('node:crypto');

// Signalisation des appels vocaux et vidéo (WebRTC) entre deux employés.
// Le son et l'image circulent directement entre les téléphones ; le serveur ne fait
// que mettre les deux appareils en relation (sonnerie, acceptation, échange SDP/ICE).

const RING_TIMEOUT_MS = 45 * 1000;

function formatDuration(ms) {
  const total = Math.max(1, Math.round(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h) return `${h} h ${String(m).padStart(2, '0')} min`;
  if (m) return `${m} min ${String(s).padStart(2, '0')} s`;
  return `${s} s`;
}

function createCallManager({ io, db, online, isMember, memberIdsOf, logToConversation }) {
  const calls = new Map(); // callId -> appel
  const userCall = new Map(); // userId -> callId

  const kind = (call) => (call.video ? 'vidéo' : 'vocal');

  function end(call, reason) {
    if (!calls.has(call.id)) return;
    clearTimeout(call.timer);
    calls.delete(call.id);
    userCall.delete(call.callerId);
    userCall.delete(call.calleeId);
    for (const uid of [call.callerId, call.calleeId]) {
      io.to(`user:${uid}`).emit('call:ended', { callId: call.id, reason });
    }
    let text;
    if (reason === 'completed') text = `📞 Appel ${kind(call)} · ${formatDuration(Date.now() - call.startedAt)}`;
    else if (reason === 'declined') text = `📞 Appel ${kind(call)} refusé`;
    else text = `📞 Appel ${kind(call)} manqué`;
    logToConversation(call.conversationId, text);
  }

  function attach(socket) {
    const user = socket.data.user;
    const reply = (ack) => (typeof ack === 'function' ? ack : () => {});

    function participant(callId) {
      const call = calls.get(String(callId));
      if (!call || (call.callerId !== user.id && call.calleeId !== user.id)) return null;
      return call;
    }

    socket.on('call:invite', (data = {}, ack) => {
      const respond = reply(ack);
      const conversationId = Number(data.conversationId);
      const conv = db.prepare('SELECT type FROM conversations WHERE id = ?').get(conversationId);
      if (!conv || !isMember(conversationId, user.id)) return respond({ error: 'Discussion introuvable.' });
      if (conv.type !== 'direct') {
        return respond({ error: 'Les appels sont disponibles dans les discussions individuelles.' });
      }
      const peerId = memberIdsOf(conversationId).find((id) => id !== user.id);
      if (!peerId) return respond({ error: 'Correspondant introuvable.' });
      if (userCall.has(user.id)) return respond({ error: 'Vous êtes déjà en communication.' });
      if (userCall.has(peerId)) return respond({ error: 'Votre correspondant est déjà en communication.' });

      const call = {
        id: crypto.randomUUID(),
        conversationId,
        video: Boolean(data.video),
        callerId: user.id,
        calleeId: peerId,
        callerSocket: socket.id,
        calleeSocket: null,
        startedAt: null,
      };

      if (!online.has(peerId)) {
        logToConversation(conversationId, `📞 Appel ${kind(call)} manqué`);
        return respond({ error: "Votre correspondant n'est pas connecté pour le moment. Il verra votre appel manqué." });
      }

      calls.set(call.id, call);
      userCall.set(user.id, call.id);
      userCall.set(peerId, call.id);
      call.timer = setTimeout(() => end(call, 'missed'), RING_TIMEOUT_MS);
      io.to(`user:${peerId}`).emit('call:incoming', {
        callId: call.id,
        conversationId,
        video: call.video,
        from: { id: user.id, name: user.name },
      });
      respond({ callId: call.id });
    });

    socket.on('call:accept', (data = {}, ack) => {
      const respond = reply(ack);
      const call = participant(data.callId);
      if (!call || call.calleeId !== user.id || call.calleeSocket) {
        return respond({ error: "Cet appel n'est plus disponible." });
      }
      clearTimeout(call.timer);
      call.calleeSocket = socket.id;
      call.startedAt = Date.now();
      // Faire taire la sonnerie sur les autres appareils de la personne appelée.
      socket.to(`user:${user.id}`).emit('call:ended', { callId: call.id, reason: 'answered-elsewhere' });
      io.to(call.callerSocket).emit('call:accepted', { callId: call.id });
      respond({ ok: true });
    });

    socket.on('call:decline', (data = {}) => {
      const call = participant(data.callId);
      if (call && call.calleeId === user.id && !call.calleeSocket) end(call, 'declined');
    });

    socket.on('call:end', (data = {}) => {
      const call = participant(data.callId);
      if (call) end(call, call.startedAt ? 'completed' : 'missed');
    });

    socket.on('call:signal', (data = {}) => {
      const call = participant(data.callId);
      if (!call || !call.calleeSocket) return;
      const target = socket.id === call.callerSocket ? call.calleeSocket : call.callerSocket;
      if (socket.id !== call.callerSocket && socket.id !== call.calleeSocket) return;
      io.to(target).emit('call:signal', { callId: call.id, data: data.data });
    });

    socket.on('disconnect', () => {
      const callId = userCall.get(user.id);
      const call = callId && calls.get(callId);
      if (call && (call.callerSocket === socket.id || call.calleeSocket === socket.id)) {
        end(call, call.startedAt ? 'completed' : 'missed');
      }
    });
  }

  return { attach, calls };
}

module.exports = { createCallManager, formatDuration };
