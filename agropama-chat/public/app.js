'use strict';

/* Agropama Messagerie — client web */

const $ = (sel) => document.querySelector(sel);

const state = {
  me: null,
  conversations: new Map(),
  users: new Map(),
  activeId: null,
  messages: new Map(), // conversationId -> tableau de messages
  hasMore: new Map(),
  typing: new Map(), // conversationId -> Map(userId -> {name, timer})
  socket: null,
};

// ------------------------------------------------------------ utilitaires
function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

function svg(path, cls) {
  const ns = 'http://www.w3.org/2000/svg';
  const s = document.createElementNS(ns, 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  if (cls) s.setAttribute('class', cls);
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', path);
  s.append(p);
  return s;
}

const ICON_FILE = 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6Zm-1 7V3.5L18.5 9H13Z';
const ICON_TICKS = 'M1 12.5 5.5 17 7 15.5 2.5 11 1 12.5Zm21-8L11.5 15 7.5 11 6 12.5l5.5 5.5L23.5 6 22 4.5Zm-4.5 1.5L16 4.5 9.5 11l1.5 1.5L17.5 6Z';
const ICON_TICK = 'M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2Z';

async function api(path, opts = {}) {
  const init = { method: opts.method || 'GET', headers: {}, credentials: 'same-origin' };
  if (opts.body instanceof FormData) init.body = opts.body;
  else if (opts.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(opts.body);
  }
  const res = await fetch(path, init);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || `Erreur ${res.status}`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

function initials(name = '?') {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0].toUpperCase())
    .join('');
}

function avatar(name, { group = false, online = false } = {}) {
  return h('span', { class: `avatar${group ? ' group' : ''}${online ? ' online' : ''}` }, group ? '👥' : initials(name));
}

function fillAvatar(el, name, opts = {}) {
  el.className = `avatar${opts.group ? ' group' : ''}${opts.online ? ' online' : ''}`;
  el.textContent = opts.group ? '👥' : initials(name);
}

const fmtTime = (iso) => new Date(iso).toLocaleTimeString('fr-CA', { hour: '2-digit', minute: '2-digit' });

function fmtShort(iso) {
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return fmtTime(iso);
  const y = new Date(now);
  y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return 'Hier';
  return d.toLocaleDateString('fr-CA', { day: '2-digit', month: '2-digit', year: '2-digit' });
}

function fmtDay(iso) {
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return "Aujourd'hui";
  const y = new Date(now);
  y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return 'Hier';
  return d.toLocaleDateString('fr-CA', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}

function fmtSize(n) {
  if (n < 1024) return `${n} o`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} Ko`;
  return `${(n / 1024 / 1024).toFixed(1)} Mo`;
}

function lastSeenText(u) {
  if (!u) return '';
  if (u.online) return 'en ligne';
  if (!u.lastSeen) return u.department || '';
  return `vu(e) ${fmtShort(u.lastSeen).toLowerCase()} ${new Date(u.lastSeen).toDateString() === new Date().toDateString() ? '' : 'à ' + fmtTime(u.lastSeen)}`.trim();
}

let toastTimer;
function toast(msg, isError = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = `toast${isError ? ' error' : ''}`;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 3500);
}

function linkify(text) {
  const frag = document.createDocumentFragment();
  const re = /\bhttps?:\/\/[^\s<]+/g;
  let last = 0;
  let m;
  while ((m = re.exec(text))) {
    frag.append(text.slice(last, m.index));
    frag.append(h('a', { href: m[0], target: '_blank', rel: 'noopener noreferrer' }, m[0]));
    last = m.index + m[0].length;
  }
  frag.append(text.slice(last));
  return frag;
}

const STATUS_LABEL = { pending: 'En attente', approved: 'Validé', rejected: 'Refusé', suspended: 'Suspendu' };

// ------------------------------------------------------------ authentification
function showAuth(notice, kind) {
  $('#main-view').hidden = true;
  $('#auth-view').hidden = false;
  setNotice(notice, kind);
}

function setNotice(text, kind = '') {
  const n = $('#auth-notice');
  n.hidden = !text;
  n.textContent = text || '';
  n.className = `notice ${kind}`;
}

function switchTab(tab) {
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  $('#login-form').hidden = tab !== 'login';
  $('#register-form').hidden = tab !== 'register';
  setNotice('');
}

document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    const { user } = await api('/api/auth/login', {
      method: 'POST',
      body: { email: f.get('email'), password: f.get('password') },
    });
    e.target.reset();
    await start(user);
  } catch (err) {
    setNotice(err.message, err.data && err.data.status === 'pending' ? 'warn' : '');
  }
});

$('#register-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  if (f.password !== f.confirm) return setNotice('Les mots de passe ne correspondent pas.');
  try {
    const { message } = await api('/api/auth/register', { method: 'POST', body: f });
    e.target.reset();
    switchTab('login');
    setNotice(message, 'ok');
  } catch (err) {
    setNotice(err.message);
  }
});

$('#logout-btn').addEventListener('click', async () => {
  await api('/api/auth/logout', { method: 'POST' }).catch(() => {});
  location.reload();
});

// ------------------------------------------------------------ démarrage
async function start(user) {
  state.me = user;
  $('#auth-view').hidden = true;
  $('#main-view').hidden = false;
  renderMe();
  await Promise.all([loadConversations(), loadUsers()]);
  connectSocket();
  if (user.role === 'admin') refreshPendingBadge();
}

function renderMe() {
  const me = state.me;
  fillAvatar($('#me-avatar'), me.name);
  $('#me-name').textContent = me.name;
  $('#me-role').textContent = me.role === 'admin' ? 'Administrateur' : me.department || me.email;
  $('#admin-btn').hidden = me.role !== 'admin';
}

async function loadUsers() {
  const { users } = await api('/api/users');
  state.users = new Map(users.map((u) => [u.id, u]));
}

async function loadConversations() {
  const { conversations } = await api('/api/conversations');
  state.conversations = new Map(conversations.map((c) => [c.id, c]));
  renderConversations();
}

// ------------------------------------------------------------ temps réel
function connectSocket() {
  if (state.socket) state.socket.disconnect();
  const socket = io({ withCredentials: true });
  state.socket = socket;

  socket.on('connect_error', async (err) => {
    if (err.message === 'unauthorized') {
      socket.disconnect();
      showAuth('Votre session a expiré. Veuillez vous reconnecter.', 'warn');
    }
  });

  socket.on('connect', () => {
    // Resynchroniser après une reconnexion
    loadConversations().catch(() => {});
    if (state.activeId) loadMessages(state.activeId).catch(() => {});
  });

  socket.on('message:new', (msg) => {
    const list = state.messages.get(msg.conversationId);
    if (list && !list.some((m) => m.id === msg.id)) list.push(msg);
    clearTyping(msg.conversationId, msg.senderId);
    if (msg.conversationId === state.activeId) {
      renderMessages(true);
      if (document.visibilityState === 'visible') markRead(msg.conversationId);
    } else if (msg.senderId && msg.senderId !== state.me.id) {
      notify(msg);
    }
  });

  socket.on('message:update', (msg) => {
    const list = state.messages.get(msg.conversationId);
    if (!list) return;
    const i = list.findIndex((m) => m.id === msg.id);
    if (i >= 0) list[i] = msg;
    if (msg.conversationId === state.activeId) renderMessages(false);
  });

  socket.on('conversation:update', (conv) => {
    if (!conv) return;
    if (conv.id === state.activeId) conv.unread = 0;
    state.conversations.set(conv.id, conv);
    renderConversations();
    if (conv.id === state.activeId) renderChatHeader();
  });

  socket.on('conversation:removed', ({ id }) => {
    state.conversations.delete(id);
    if (state.activeId === id) closeChat();
    renderConversations();
  });

  socket.on('conversation:read', ({ conversationId, userId, lastReadId }) => {
    const conv = state.conversations.get(conversationId);
    if (!conv) return;
    const m = conv.members.find((x) => x.id === userId);
    if (m) m.lastReadId = lastReadId;
    if (conversationId === state.activeId) renderMessages(false);
  });

  socket.on('typing', ({ conversationId, userId, name, typing }) => {
    if (!state.typing.has(conversationId)) state.typing.set(conversationId, new Map());
    const map = state.typing.get(conversationId);
    clearTimeout(map.get(userId)?.timer);
    if (typing) {
      map.set(userId, { name, timer: setTimeout(() => clearTyping(conversationId, userId), 5000) });
    } else map.delete(userId);
    renderConversations();
    if (conversationId === state.activeId) renderChatHeader();
  });

  socket.on('presence', ({ userId, online, lastSeen }) => {
    const u = state.users.get(userId);
    if (u) Object.assign(u, { online, lastSeen: lastSeen || u.lastSeen });
    for (const c of state.conversations.values()) {
      for (const m of c.members) if (m.id === userId) Object.assign(m, { online, lastSeen: lastSeen || m.lastSeen });
      if (c.peer && c.peer.id === userId) Object.assign(c.peer, { online, lastSeen: lastSeen || c.peer.lastSeen });
    }
    renderConversations();
    if (state.activeId) renderChatHeader();
  });

  socket.on('user:update', (u) => {
    if (u.status === 'approved') state.users.set(u.id, u);
    else state.users.delete(u.id);
  });

  socket.on('session:revoked', ({ reason }) => {
    socket.disconnect();
    showAuth(reason, 'warn');
  });

  socket.on('session:refresh', () => location.reload());

  socket.on('admin:registration', (u) => {
    toast(`Nouvelle demande d'inscription : ${u.name}`);
    refreshPendingBadge();
    if (adminView) adminView.reload();
  });
  socket.on('admin:user', () => {
    refreshPendingBadge();
    if (adminView) adminView.reload();
  });
}

function clearTyping(conversationId, userId) {
  const map = state.typing.get(conversationId);
  if (!map || !map.has(userId)) return;
  clearTimeout(map.get(userId).timer);
  map.delete(userId);
  renderConversations();
  if (conversationId === state.activeId) renderChatHeader();
}

function typingText(conversationId) {
  const map = state.typing.get(conversationId);
  if (!map || map.size === 0) return '';
  const conv = state.conversations.get(conversationId);
  const names = [...map.values()].map((v) => v.name.split(' ')[0]);
  if (conv && conv.type === 'direct') return 'écrit…';
  return names.length === 1 ? `${names[0]} écrit…` : `${names.join(', ')} écrivent…`;
}

function notify(msg) {
  const conv = state.conversations.get(msg.conversationId);
  const title = conv ? conv.title : 'Nouveau message';
  const text = msg.body || (msg.file ? `📎 ${msg.file.name}` : '');
  if ('Notification' in window && Notification.permission === 'granted' && document.visibilityState !== 'visible') {
    const n = new Notification(title, { body: `${msg.senderName}: ${text}`, icon: 'logo.svg', tag: `conv-${msg.conversationId}` });
    n.onclick = () => {
      window.focus();
      openChat(msg.conversationId);
    };
  }
}

// ------------------------------------------------------------ liste des discussions
$('#conv-search').addEventListener('input', renderConversations);

function previewText(conv) {
  const t = typingText(conv.id);
  if (t) return h('span', { class: 'typing' }, t);
  const m = conv.lastMessage;
  if (!m) return h('span', {}, conv.type === 'group' ? `${conv.members.length} membres` : 'Nouvelle discussion');
  let text = m.deleted ? '🚫 Message supprimé' : m.body || (m.file ? `📎 ${m.file.name}` : '');
  if (m.senderId === state.me.id) text = `Vous : ${text}`;
  else if (conv.type === 'group' && m.senderName) text = `${m.senderName.split(' ')[0]} : ${text}`;
  return h('span', {}, text);
}

function renderConversations() {
  const list = $('#conv-list');
  const term = $('#conv-search').value.trim().toLowerCase();
  const convs = [...state.conversations.values()]
    .filter((c) => !term || c.title.toLowerCase().includes(term))
    .sort((a, b) => {
      const ta = a.lastMessage ? a.lastMessage.createdAt : a.createdAt;
      const tb = b.lastMessage ? b.lastMessage.createdAt : b.createdAt;
      return String(tb).localeCompare(String(ta));
    });
  list.replaceChildren();
  if (convs.length === 0) {
    list.append(
      h('li', { class: 'conv-empty' }, term ? 'Aucun résultat.' : 'Aucune discussion. Cliquez sur ',
        term ? '' : h('strong', {}, 'Nouvelle discussion'), term ? '' : ' pour écrire à un collègue.')
    );
  }
  for (const c of convs) {
    const time = c.lastMessage ? c.lastMessage.createdAt : c.createdAt;
    list.append(
      h('li', { class: `conv${c.id === state.activeId ? ' active' : ''}`, onclick: () => openChat(c.id) },
        avatar(c.title, { group: c.type === 'group', online: c.peer && c.peer.online }),
        h('div', { class: 'conv-main' },
          h('div', { class: 'conv-top' }, h('strong', {}, c.title), h('time', {}, time ? fmtShort(time) : '')),
          h('div', { class: 'conv-bottom' }, previewText(c), c.unread > 0 && c.id !== state.activeId ? h('b', { class: 'unread' }, c.unread) : null)
        )
      )
    );
  }
  const totalUnread = convs.reduce((n, c) => n + (c.id === state.activeId ? 0 : c.unread), 0);
  document.title = totalUnread ? `(${totalUnread}) Agropama Messagerie` : 'Agropama Messagerie';
}

// ------------------------------------------------------------ discussion active
async function openChat(id) {
  state.activeId = id;
  // Historique : le bouton « retour » du téléphone ramène à la liste des discussions.
  if (history.state && history.state.chat) history.replaceState({ chat: id }, '');
  else history.pushState({ chat: id }, '');
  const conv = state.conversations.get(id);
  if (conv) conv.unread = 0;
  $('#chat-empty').hidden = true;
  $('#chat-panel').hidden = false;
  $('#main-view').classList.add('chat-open');
  renderConversations();
  renderChatHeader();
  $('#messages').replaceChildren(h('div', { class: 'day' }, 'Chargement…'));
  await loadMessages(id);
  markRead(id);
  $('#message-input').focus();
  if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission();
}

function closeChat() {
  state.activeId = null;
  $('#chat-panel').hidden = true;
  $('#chat-empty').hidden = false;
  $('#main-view').classList.remove('chat-open');
  renderConversations();
}

$('#back-btn').addEventListener('click', () => {
  if (history.state && history.state.chat) history.back();
  else closeChat();
});

window.addEventListener('popstate', () => {
  if (modal.open) modal.close();
  if (state.activeId) closeChat();
});

// Dans l'application Android (APK), permettre de changer l'adresse du serveur.
const IN_APP = /AgropamaApp/.test(navigator.userAgent);

async function loadMessages(id, before) {
  const { messages, hasMore } = await api(`/api/conversations/${id}/messages${before ? `?before=${before}` : ''}`);
  const existing = before ? state.messages.get(id) || [] : [];
  state.messages.set(id, [...messages, ...existing]);
  state.hasMore.set(id, hasMore);
  if (id === state.activeId) renderMessages(!before);
}

function markRead(id) {
  api(`/api/conversations/${id}/read`, { method: 'POST' }).catch(() => {});
}

function renderChatHeader() {
  const conv = state.conversations.get(state.activeId);
  if (!conv) return;
  fillAvatar($('#chat-avatar'), conv.title, { group: conv.type === 'group', online: conv.peer && conv.peer.online });
  $('#chat-title').textContent = conv.title;
  const typing = typingText(conv.id);
  let sub;
  if (typing) sub = typing;
  else if (conv.type === 'group') sub = conv.members.map((m) => (m.id === state.me.id ? 'Vous' : m.name.split(' ')[0])).join(', ');
  else sub = [lastSeenText(conv.peer), conv.peer && conv.peer.department].filter(Boolean).join(' · ');
  $('#chat-subtitle').textContent = sub;
}

function readStatus(conv, msg) {
  const others = conv.members.filter((m) => m.id !== state.me.id);
  if (others.length === 0) return 'sent';
  return others.every((m) => m.lastReadId >= msg.id) ? 'read' : 'sent';
}

function renderMessages(scrollToBottom) {
  const box = $('#messages');
  const id = state.activeId;
  const conv = state.conversations.get(id);
  const list = state.messages.get(id) || [];
  const prevHeight = box.scrollHeight;
  const prevTop = box.scrollTop;
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 120;

  box.replaceChildren();
  if (state.hasMore.get(id)) {
    box.append(h('button', { class: 'btn small load-more', onclick: () => loadMessages(id, list[0] && list[0].id) }, 'Messages précédents'));
  }
  let lastDay = null;
  let lastSender;
  for (const m of list) {
    const day = new Date(m.createdAt).toDateString();
    if (day !== lastDay) {
      box.append(h('div', { class: 'day' }, fmtDay(m.createdAt)));
      lastDay = day;
      lastSender = undefined;
    }
    if (!m.senderId) {
      box.append(h('div', { class: 'system' }, m.body));
      lastSender = undefined;
      continue;
    }
    const mine = m.senderId === state.me.id;
    const first = m.senderId !== lastSender;
    lastSender = m.senderId;

    const content = [];
    if (conv && conv.type === 'group' && !mine && first) content.push(h('span', { class: 'sender' }, m.senderName || 'Ancien employé'));
    if (m.deleted) {
      content.push(h('span', { class: 'body' }, '🚫 Ce message a été supprimé'));
    } else {
      if (m.file) {
        if (/^image\/(png|jpe?g|gif|webp)$/.test(m.file.type)) {
          content.push(h('img', { class: 'thumb', src: m.file.url, alt: m.file.name, loading: 'lazy', onclick: () => window.open(m.file.url, '_blank') }));
        } else {
          content.push(
            h('a', { class: 'file-card', href: `${m.file.url}?download`, download: m.file.name },
              svg(ICON_FILE), h('span', {}, m.file.name, h('small', {}, fmtSize(m.file.size))))
          );
        }
      }
      if (m.body) content.push(h('span', { class: 'body' }, linkify(m.body)));
    }
    const meta = h('span', { class: 'meta' }, fmtTime(m.createdAt));
    if (mine && !m.deleted && conv) {
      const st = readStatus(conv, m);
      meta.append(svg(st === 'read' ? ICON_TICKS : ICON_TICK, `ticks${st === 'read' ? ' read' : ''}`));
    }
    content.push(meta);
    const canDelete = !m.deleted && (mine || state.me.role === 'admin');
    if (canDelete) {
      content.push(h('button', { class: 'msg-menu', title: 'Supprimer', onclick: () => deleteMessage(m) }, '✕'));
    }
    box.append(h('div', { class: `msg${mine ? ' out' : ''}${first ? ' first' : ''}${m.deleted ? ' deleted' : ''}` }, content));
  }

  if (scrollToBottom || nearBottom) box.scrollTop = box.scrollHeight;
  else box.scrollTop = prevTop + (box.scrollHeight - prevHeight);
}

async function deleteMessage(m) {
  if (!confirm('Supprimer ce message pour tout le monde ?')) return;
  try {
    await api(`/api/messages/${m.id}`, { method: 'DELETE' });
  } catch (err) {
    toast(err.message, true);
  }
}

// ------------------------------------------------------------ rédaction
const input = $('#message-input');
let typingTimer;
let isTyping = false;

function autoGrow() {
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 140)}px`;
}

function sendTyping(flag) {
  if (!state.socket || !state.activeId) return;
  if (flag === isTyping) return;
  isTyping = flag;
  state.socket.emit('typing', { conversationId: state.activeId, typing: flag });
}

input.addEventListener('input', () => {
  autoGrow();
  sendTyping(input.value.length > 0);
  clearTimeout(typingTimer);
  typingTimer = setTimeout(() => sendTyping(false), 3000);
});

input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    $('#composer').requestSubmit();
  }
});

$('#composer').addEventListener('submit', async (e) => {
  e.preventDefault();
  const body = input.value.trim();
  if (!body || !state.activeId) return;
  input.value = '';
  autoGrow();
  sendTyping(false);
  try {
    const { message } = await api(`/api/conversations/${state.activeId}/messages`, { method: 'POST', body: { body } });
    const list = state.messages.get(message.conversationId);
    if (list && !list.some((m) => m.id === message.id)) {
      list.push(message);
      if (message.conversationId === state.activeId) renderMessages(true);
    }
  } catch (err) {
    input.value = body;
    toast(err.message, true);
  }
});

$('#file-input').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file || !state.activeId) return;
  if (file.size > 15 * 1024 * 1024) return toast('Fichier trop volumineux (15 Mo max).', true);
  const fd = new FormData();
  fd.append('file', file);
  const caption = input.value.trim();
  if (caption) fd.append('body', caption);
  toast(`Envoi de « ${file.name} »…`);
  try {
    await api(`/api/conversations/${state.activeId}/files`, { method: 'POST', body: fd });
    input.value = '';
    autoGrow();
  } catch (err) {
    toast(err.message, true);
  }
});

// ------------------------------------------------------------ modales
const modal = $('#modal');
$('#modal-close').addEventListener('click', () => modal.close());
modal.addEventListener('close', () => {
  adminView = null;
  modal.classList.remove('wide');
});

function openModal(title, ...content) {
  $('#modal-title').textContent = title;
  $('#modal-body').replaceChildren(...content);
  if (!modal.open) modal.showModal();
}

function userRow(u, extra) {
  return [
    avatar(u.name, { online: u.online }),
    h('div', { class: 'info' }, h('strong', {}, u.name), h('small', {}, [u.jobTitle, u.department].filter(Boolean).join(' · ') || u.email)),
    extra,
  ];
}

function filterableUserList(users, renderItem) {
  const search = h('input', { type: 'search', placeholder: 'Rechercher un employé (nom, service…)' });
  const ul = h('ul', { class: 'pick-list' });
  const draw = () => {
    const t = search.value.trim().toLowerCase();
    ul.replaceChildren();
    const shown = users.filter((u) => !t || [u.name, u.department, u.jobTitle, u.email].join(' ').toLowerCase().includes(t));
    if (shown.length === 0) ul.append(h('li', { class: 'empty-row' }, 'Aucun employé trouvé.'));
    shown.forEach((u) => ul.append(renderItem(u)));
  };
  search.addEventListener('input', draw);
  draw();
  return { search, ul };
}

$('#new-chat-btn').addEventListener('click', async () => {
  await loadUsers();
  const users = [...state.users.values()];
  const { search, ul } = filterableUserList(users, (u) =>
    h('li', { onclick: () => startDirect(u.id) }, userRow(u))
  );
  openModal('Nouvelle discussion', search, ul);
  search.focus();
});

async function startDirect(userId) {
  try {
    const { conversation } = await api('/api/conversations/direct', { method: 'POST', body: { userId } });
    state.conversations.set(conversation.id, conversation);
    modal.close();
    openChat(conversation.id);
  } catch (err) {
    toast(err.message, true);
  }
}

$('#new-group-btn').addEventListener('click', async () => {
  await loadUsers();
  const selected = new Set();
  const name = h('input', { placeholder: 'Nom du groupe (ex. Équipe Production)', maxlength: 80 });
  const { search, ul } = filterableUserList([...state.users.values()], (u) => {
    const cb = h('input', { type: 'checkbox' });
    cb.checked = selected.has(u.id);
    cb.addEventListener('change', () => (cb.checked ? selected.add(u.id) : selected.delete(u.id)));
    return h('li', { onclick: (e) => { if (e.target !== cb) { cb.checked = !cb.checked; cb.dispatchEvent(new Event('change')); } } }, userRow(u, cb));
  });
  const create = h('button', { class: 'btn primary', onclick: async () => {
    try {
      const { conversation } = await api('/api/conversations/group', {
        method: 'POST',
        body: { name: name.value, memberIds: [...selected] },
      });
      state.conversations.set(conversation.id, conversation);
      modal.close();
      openChat(conversation.id);
    } catch (err) {
      toast(err.message, true);
    }
  } }, 'Créer le groupe');
  openModal('Nouveau groupe', name, search, ul, h('div', { class: 'modal-actions' }, create));
  name.focus();
});

$('#chat-info-btn').addEventListener('click', () => {
  const conv = state.conversations.get(state.activeId);
  if (!conv) return;
  if (conv.type === 'direct') {
    const p = conv.peer;
    openModal(p.name,
      h('div', { class: 'pick-list' }, h('li', {}, userRow(p))),
      h('p', {}, h('strong', {}, 'Courriel : '), h('a', { href: `mailto:${p.email}` }, p.email)),
      p.phone ? h('p', {}, h('strong', {}, 'Téléphone : '), h('a', { href: `tel:${p.phone}` }, p.phone)) : null,
      p.jobTitle ? h('p', {}, h('strong', {}, 'Poste : '), p.jobTitle) : null,
      p.department ? h('p', {}, h('strong', {}, 'Service : '), p.department) : null,
    );
    return;
  }
  showGroupInfo(conv);
});

function showGroupInfo(conv) {
  const canManage = conv.isGroupAdmin || state.me.role === 'admin';
  const ul = h('ul', { class: 'pick-list' });
  for (const m of conv.members) {
    const btns = [];
    if (m.isGroupAdmin) btns.push(h('span', { class: 'pill approved' }, 'Admin du groupe'));
    if (canManage && m.id !== state.me.id) {
      btns.push(h('button', { class: 'btn small danger', onclick: async () => {
        if (!confirm(`Retirer ${m.name} du groupe ?`)) return;
        try {
          await api(`/api/conversations/${conv.id}/members/${m.id}`, { method: 'DELETE' });
          modal.close();
        } catch (err) { toast(err.message, true); }
      } }, 'Retirer'));
    }
    ul.append(h('li', {}, userRow({ ...m, name: m.id === state.me.id ? `${m.name} (vous)` : m.name }, h('div', { class: 'btns' }, btns))));
  }
  const actions = h('div', { class: 'modal-actions' });
  if (canManage) {
    actions.append(h('button', { class: 'btn', onclick: () => addMembers(conv) }, 'Ajouter des membres'));
  }
  actions.append(h('button', { class: 'btn danger', onclick: async () => {
    if (!confirm('Quitter ce groupe ?')) return;
    try {
      await api(`/api/conversations/${conv.id}/members/${state.me.id}`, { method: 'DELETE' });
      modal.close();
    } catch (err) { toast(err.message, true); }
  } }, 'Quitter le groupe'));
  openModal(conv.title, h('p', {}, `${conv.members.length} membres`), ul, actions);
}

async function addMembers(conv) {
  await loadUsers();
  const current = new Set(conv.members.map((m) => m.id));
  const selected = new Set();
  const { search, ul } = filterableUserList([...state.users.values()].filter((u) => !current.has(u.id)), (u) => {
    const cb = h('input', { type: 'checkbox' });
    cb.addEventListener('change', () => (cb.checked ? selected.add(u.id) : selected.delete(u.id)));
    return h('li', { onclick: (e) => { if (e.target !== cb) { cb.checked = !cb.checked; cb.dispatchEvent(new Event('change')); } } }, userRow(u, cb));
  });
  openModal(`Ajouter à « ${conv.title} »`, search, ul, h('div', { class: 'modal-actions' },
    h('button', { class: 'btn primary', onclick: async () => {
      try {
        await api(`/api/conversations/${conv.id}/members`, { method: 'POST', body: { memberIds: [...selected] } });
        modal.close();
      } catch (err) { toast(err.message, true); }
    } }, 'Ajouter')));
}

// ---- Profil
$('#me-btn').addEventListener('click', () => {
  const me = state.me;
  const form = h('form', { class: 'form' },
    h('label', {}, 'Nom complet', h('input', { name: 'name', value: me.name, required: true, minlength: 2 })),
    h('label', {}, 'Courriel', h('input', { value: me.email, disabled: true })),
    h('div', { class: 'row' },
      h('label', {}, 'Téléphone', h('input', { name: 'phone', value: me.phone || '' })),
      h('label', {}, 'Service', h('input', { name: 'department', value: me.department || '', list: 'departments' }))),
    h('label', {}, 'Poste', h('input', { name: 'jobTitle', value: me.jobTitle || '' })),
    h('hr'),
    h('label', {}, 'Mot de passe actuel', h('input', { name: 'currentPassword', type: 'password', autocomplete: 'current-password' })),
    h('label', {}, 'Nouveau mot de passe', h('input', { name: 'newPassword', type: 'password', minlength: 8, autocomplete: 'new-password' })),
    h('div', { class: 'modal-actions' },
      IN_APP ? h('button', { class: 'btn', type: 'button', onclick: () => {
        if (confirm("Changer l'adresse du serveur Agropama ?")) location.href = 'https://localhost/?change';
      } }, 'Changer de serveur') : null,
      h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer'))
  );
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(form));
    if (!data.newPassword) { delete data.newPassword; delete data.currentPassword; }
    try {
      const { user } = await api('/api/me', { method: 'PATCH', body: data });
      state.me = user;
      renderMe();
      modal.close();
      toast('Profil mis à jour.');
    } catch (err) { toast(err.message, true); }
  });
  openModal('Mon profil', form);
});

// ------------------------------------------------------------ administration
let adminView = null;

async function refreshPendingBadge() {
  if (!state.me || state.me.role !== 'admin') return;
  try {
    const { users } = await api('/api/admin/users?status=pending');
    const b = $('#pending-badge');
    b.hidden = users.length === 0;
    b.textContent = users.length;
  } catch { /* ignore */ }
}

$('#admin-btn').addEventListener('click', () => openAdmin('pending'));

async function openAdmin(filter) {
  modal.classList.add('wide');
  let current = filter;
  const statsBox = h('div', { class: 'stats' });
  const tabs = h('div', { class: 'admin-tabs' });
  const search = h('input', { type: 'search', placeholder: 'Rechercher par nom, courriel ou service' });
  const tableWrap = h('div', { class: 'table-wrap' });
  let users = [];

  const filters = [
    ['pending', 'En attente'], ['approved', 'Validés'], ['suspended', 'Suspendus'], ['rejected', 'Refusés'], ['all', 'Tous'],
  ];

  function drawTabs() {
    tabs.replaceChildren(...filters.map(([k, label]) =>
      h('button', { class: current === k ? 'active' : '', onclick: () => { current = k; reload(); } }, label)));
  }

  async function act(u, action, confirmText) {
    if (confirmText && !confirm(confirmText)) return;
    try {
      if (action === 'delete') await api(`/api/admin/users/${u.id}`, { method: 'DELETE' });
      else if (action === 'role') await api(`/api/admin/users/${u.id}/role`, { method: 'PATCH', body: { role: u.role === 'admin' ? 'employee' : 'admin' } });
      else if (action === 'reset-password') {
        const { temporaryPassword } = await api(`/api/admin/users/${u.id}/reset-password`, { method: 'POST' });
        prompt(`Mot de passe temporaire pour ${u.name} (à lui communiquer) :`, temporaryPassword);
      } else await api(`/api/admin/users/${u.id}/${action}`, { method: 'POST' });
      if (action !== 'reset-password') toast('Modification enregistrée.');
      reload();
      refreshPendingBadge();
    } catch (err) { toast(err.message, true); }
  }

  function drawTable() {
    const t = search.value.trim().toLowerCase();
    const shown = users.filter((u) => !t || [u.name, u.email, u.department, u.jobTitle].join(' ').toLowerCase().includes(t));
    const rows = shown.map((u) => {
      const self = u.id === state.me.id;
      const b = [];
      if (!self) {
        if (u.status === 'pending') {
          b.push(h('button', { class: 'btn small primary', onclick: () => act(u, 'approve') }, 'Valider'));
          b.push(h('button', { class: 'btn small danger', onclick: () => act(u, 'reject', `Refuser l'inscription de ${u.name} ?`) }, 'Refuser'));
        }
        if (u.status === 'approved') {
          b.push(h('button', { class: 'btn small', onclick: () => act(u, 'role', u.role === 'admin' ? `Retirer les droits d'administrateur à ${u.name} ?` : `Nommer ${u.name} administrateur ?`) }, u.role === 'admin' ? 'Retirer admin' : 'Nommer admin'));
          b.push(h('button', { class: 'btn small', onclick: () => act(u, 'reset-password', `Réinitialiser le mot de passe de ${u.name} ?`) }, 'Réinit. mot de passe'));
          b.push(h('button', { class: 'btn small danger', onclick: () => act(u, 'suspend', `Suspendre le compte de ${u.name} ? Il sera déconnecté immédiatement.`) }, 'Suspendre'));
        }
        if (u.status === 'suspended') b.push(h('button', { class: 'btn small primary', onclick: () => act(u, 'reactivate') }, 'Réactiver'));
        if (u.status === 'rejected') b.push(h('button', { class: 'btn small primary', onclick: () => act(u, 'approve') }, 'Valider'));
        b.push(h('button', { class: 'btn small danger', onclick: () => act(u, 'delete', `Supprimer définitivement le compte de ${u.name} ?`) }, 'Supprimer'));
      }
      return h('tr', {},
        h('td', {}, h('strong', {}, u.name), u.role === 'admin' ? [' ', h('span', { class: 'pill admin' }, 'Admin')] : null, h('small', {}, u.email)),
        h('td', {}, u.department || '—', h('small', {}, u.jobTitle || '')),
        h('td', {}, u.phone || '—'),
        h('td', {}, h('span', { class: `pill ${u.status}` }, STATUS_LABEL[u.status])),
        h('td', {}, new Date(u.createdAt).toLocaleDateString('fr-CA')),
        h('td', {}, self ? h('small', {}, 'Vous') : h('div', { class: 'btns' }, b))
      );
    });
    tableWrap.replaceChildren(h('table', {},
      h('thead', {}, h('tr', {}, ['Employé', 'Service / poste', 'Téléphone', 'Statut', 'Inscrit le', 'Actions'].map((x) => h('th', {}, x)))),
      h('tbody', {}, rows.length ? rows : h('tr', {}, h('td', { colspan: 6, class: 'empty-row' }, 'Aucun compte dans cette catégorie.')))
    ));
  }

  async function reload() {
    drawTabs();
    try {
      const [stats, list] = await Promise.all([
        api('/api/admin/stats'),
        api(`/api/admin/users${current === 'all' ? '' : `?status=${current}`}`),
      ]);
      const s = stats.users;
      statsBox.replaceChildren(
        h('div', { class: `stat${s.pending ? ' alert' : ''}` }, h('b', {}, s.pending || 0), h('span', {}, 'En attente')),
        h('div', { class: 'stat' }, h('b', {}, s.approved || 0), h('span', {}, 'Comptes actifs')),
        h('div', { class: 'stat' }, h('b', {}, stats.online), h('span', {}, 'En ligne')),
        h('div', { class: 'stat' }, h('b', {}, stats.conversations), h('span', {}, 'Discussions')),
        h('div', { class: 'stat' }, h('b', {}, stats.messages), h('span', {}, 'Messages'))
      );
      users = list.users;
      drawTable();
    } catch (err) { toast(err.message, true); }
  }

  search.addEventListener('input', drawTable);
  openModal('Administration des comptes', statsBox, tabs, search, tableWrap);
  adminView = { reload };
  await reload();
}

// ------------------------------------------------------------ amorçage
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && state.activeId) markRead(state.activeId);
});

(async function boot() {
  try {
    const { user } = await api('/api/me');
    await start(user);
  } catch {
    showAuth();
  }
})();
