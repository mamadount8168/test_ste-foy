'use strict';
// Écran de démarrage de l'APK : mémorise l'adresse du serveur Agropama puis l'ouvre.
(function () {
  const KEY = 'agropama.server';
  const form = document.getElementById('form');
  const input = document.getElementById('url');
  const errorBox = document.getElementById('error');
  const loading = document.getElementById('loading');
  const button = document.getElementById('go');

  function read() { try { return localStorage.getItem(KEY); } catch { return null; } }
  function save(v) { try { localStorage.setItem(KEY, v); } catch { /* ignore */ } }

  function normalize(raw) {
    let v = (raw || '').trim();
    if (!v) return null;
    if (!/^https?:\/\//i.test(v)) v = 'https://' + v;
    try {
      const u = new URL(v);
      return u.origin;
    } catch {
      return null;
    }
  }

  async function reachable(origin) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 8000);
    try {
      const res = await fetch(origin + '/api/health', { signal: ctrl.signal, cache: 'no-store' });
      const data = await res.json();
      return data && data.app === 'agropama-chat';
    } catch {
      return false;
    } finally {
      clearTimeout(t);
    }
  }

  function showForm(message, value) {
    loading.hidden = true;
    form.hidden = false;
    errorBox.hidden = !message;
    errorBox.textContent = message || '';
    if (value) input.value = value;
    button.disabled = false;
  }

  async function connect(origin, fromForm) {
    loading.hidden = false;
    form.hidden = true;
    if (await reachable(origin)) {
      save(origin);
      window.location.replace(origin + '/');
    } else {
      showForm(
        fromForm
          ? "Impossible de joindre un serveur Agropama à cette adresse. Vérifiez l'adresse et votre connexion Internet."
          : 'Le serveur ne répond pas. Vérifiez votre connexion Internet, puis réessayez.',
        origin
      );
    }
  }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const origin = normalize(input.value);
    if (!origin) return showForm('Adresse invalide.', input.value);
    button.disabled = true;
    connect(origin, true);
  });

  const params = new URLSearchParams(location.search);
  const saved = read();
  if (saved && !params.has('change')) connect(saved, false);
  else showForm('', saved || '');
})();
