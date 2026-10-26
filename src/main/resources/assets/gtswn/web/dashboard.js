'use strict';
(() => {
  let token = '', timer = null, request = null, etag = '', failures = 0, locked = true, generation = 0;
  const el = id => document.getElementById(id);
  const status = message => { el('status').textContent = message; };
  function clearViews() { el('dashboards').replaceChildren(); etag = ''; }
  function lock(message) {
    locked = true; generation++; token = ''; clearTimeout(timer); timer = null;
    if (request) request.abort();
    clearViews(); el('token').value = ''; el('login').hidden = false; el('disconnect').hidden = true;
    status(message || 'Dashboard locked');
  }
  function text(tag, value, className) {
    const node = document.createElement(tag); node.textContent = value;
    if (className) node.className = className;
    return node;
  }
  function drawChart(parent, history) {
    const values = history.filter(Number.isFinite);
    if (!values.length) { parent.append(text('p', 'No history available', 'range')); return; }
    const minimum = Math.min(...values), maximum = Math.max(...values);
    const canvas = document.createElement('canvas'); canvas.width = 600; canvas.height = 200;
    canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', `Raw sample history: minimum ${minimum}, maximum ${maximum}. Missing samples are gaps.`);
    const ctx = canvas.getContext('2d'); ctx.strokeStyle = '#63dfbd'; ctx.lineWidth = 3;
    ctx.beginPath(); let connected = false;
    history.forEach((value, i) => {
      if (!Number.isFinite(value)) { connected = false; return; }
      const x = 8 + i / Math.max(1, history.length - 1) * 584;
      const y = maximum === minimum ? 100 : 184 - (value - minimum) / (maximum - minimum) * 168;
      if (connected) ctx.lineTo(x, y); else ctx.moveTo(x, y);
      connected = true;
    }); ctx.stroke();
    if (values.length === 1) { ctx.fillStyle = '#63dfbd'; ctx.fillRect(5, 97, 6, 6); }
    parent.append(canvas, text('p', `Raw range ${minimum.toLocaleString()} — ${maximum.toLocaleString()} · ${values.length} samples`, 'range'));
  }
  function render(payload) {
    const fragment = document.createDocumentFragment();
    for (const dashboard of payload.dashboards || []) {
      const section = document.createElement('section'); section.className = 'dashboard';
      section.append(text('h2', dashboard.title || 'Published dashboard'));
      const meta = document.createElement('div'); meta.className = 'meta';
      meta.append(text('span', dashboard.status, `badge ${dashboard.status === 'LIVE' ? 'live' : ''}`),
        text('span', `Sample tick ${dashboard.sampleTick} · Revision ${dashboard.revision}`));
      section.append(meta);
      const cards = document.createElement('div'); cards.className = 'cards';
      for (const card of dashboard.cards || []) {
        const panel = document.createElement('article'); panel.className = 'card';
        panel.append(text('h3', card.label));
        const value = text('p', card.value === '' ? 'No data' : card.value, 'value');
        value.append(text('span', card.unit, 'unit')); panel.append(value);
        drawChart(panel, card.history || []); cards.append(panel);
      }
      if (!cards.childElementCount) section.append(text('p', 'No authorized cached samples available.'));
      section.append(cards);
      if (dashboard.cardsTruncated) section.append(text('p', `Card display limit reached; ${dashboard.cardsOmitted || 'some'} cards omitted.`, 'note'));
      fragment.append(section);
    }
    if (!fragment.childElementCount) fragment.append(text('p', 'No dashboards are currently published. Ask the owner to consent and an OP to enable a dashboard.'));
    if (payload.truncated) fragment.append(text('p', 'Response budget reached; some dashboards are omitted.', 'note'));
    el('dashboards').replaceChildren(fragment);
  }
  function schedule() {
    clearTimeout(timer);
    if (!locked && !document.hidden) timer = setTimeout(poll, Math.min(30000, 2000 * 2 ** failures) + Math.random() * 250);
  }
  async function poll() {
    if (locked || document.hidden || request) return;
    const controller = new AbortController(), startedGeneration = generation; request = controller;
    const timeout = setTimeout(() => controller.abort(), 7000);
    try {
      const headers = {Authorization: 'Bearer ' + token};
      if (etag) headers['If-None-Match'] = etag;
      const response = await fetch('/api/snapshots', {headers, signal: controller.signal, cache: 'no-store', credentials: 'omit', redirect: 'error'});
      if (locked || request !== controller || generation !== startedGeneration) return;
      if (response.status === 401 || response.status === 403) { lock('Access denied. Dashboard data cleared.'); return; }
      if (response.status !== 304 && !response.ok) throw new Error('unavailable');
      if (response.status !== 304) {
        const payload = await response.json();
        if (locked || request !== controller || generation !== startedGeneration) return;
        render(payload); etag = response.headers.get('ETag') || '';
      }
      failures = 0; status('Connected · Read-only · Last check ' + new Date().toLocaleTimeString());
    } catch (error) {
      if (!locked && !document.hidden && generation === startedGeneration) { failures = Math.min(4, failures + 1); clearViews(); status('Unavailable — data cleared; retrying with backoff.'); }
    } finally {
      clearTimeout(timeout);
      if (request === controller) request = null;
      schedule();
    }
  }
  el('connect').addEventListener('click', () => {
    const local = ['127.0.0.1', '[::1]', 'localhost'].includes(location.hostname);
    if (location.protocol !== 'https:' && !local) { status('Refusing non-local plaintext HTTP. Use HTTPS.'); return; }
    const candidate = el('token').value.trim();
    if (!/^[A-Za-z0-9_~.+/=-]{32,256}$/.test(candidate)) { status('Enter a valid operator-provided bearer token (32–256 characters).'); return; }
    generation++; token = candidate; el('token').value = ''; locked = false; failures = 0;
    el('login').hidden = true; el('disconnect').hidden = false; poll();
  });
  el('token').addEventListener('keydown', event => { if (event.key === 'Enter') el('connect').click(); });
  el('disconnect').addEventListener('click', () => lock());
  document.addEventListener('visibilitychange', () => {
    clearTimeout(timer);
    if (document.hidden) { if (request) request.abort(); clearViews(); status('Paused while hidden — data cleared.'); }
    else if (!locked) poll();
  });
  window.addEventListener('pagehide', () => lock());
})();
