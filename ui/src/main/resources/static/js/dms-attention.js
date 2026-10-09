// Account-menu attention dot, fed by /api/attention (see AttentionSource on the server).
// Call window.DmsAttention.refresh() after a page clears something the dot points at.
(function () {
  'use strict';

  const toggle = document.getElementById('accountMenuToggle');
  const menu = document.getElementById('accountMenu');
  if (!toggle || !menu) return;

  function render(items) {
    menu.querySelectorAll('[data-attention-generated]').forEach(function (n) { n.remove(); });
    menu.querySelectorAll('.has-attention').forEach(function (n) { n.classList.remove('has-attention'); });

    const anchor = menu.querySelector('[data-attention-anchor]');
    let addedEntries = 0;
    items.forEach(function (item) {
      const target = menu.querySelector('[data-attention-key="' + CSS.escape(item.key) + '"]');
      const note = document.createElement('span');
      note.className = 'attention-note';
      note.textContent = item.message;
      note.setAttribute('data-attention-generated', '');
      if (target) {
        target.classList.add('has-attention');
        target.appendChild(note);
        return;
      }
      const li = document.createElement('li');
      li.setAttribute('data-attention-generated', '');
      const link = document.createElement('a');
      link.className = 'dropdown-item has-attention';
      link.href = item.url;
      link.textContent = item.message;
      li.appendChild(link);
      anchor.before(li);
      addedEntries++;
    });
    if (addedEntries > 0) {
      const divider = document.createElement('li');
      divider.setAttribute('data-attention-generated', '');
      divider.innerHTML = '<hr class="dropdown-divider">';
      anchor.before(divider);
    }

    toggle.querySelector('[data-attention-dot]').classList.toggle('d-none', items.length === 0);
    const base = toggle.getAttribute('data-base-label');
    const n = items.length;
    toggle.setAttribute('aria-label',
        n === 0 ? base : base + ', ' + n + (n === 1 ? ' item needs' : ' items need') + ' attention');
  }

  function refresh() {
    return fetch('/api/attention', { credentials: 'same-origin' })
        .then(function (response) { return response.ok ? response.json() : null; })
        .then(function (data) { render((data && data.items) || []); })
        .catch(function () { /* silent: the dot is non-critical */ });
  }

  menu.querySelectorAll('a.dropdown-item:not(.account-identity)').forEach(function (link) {
    if (link.getAttribute('href') === window.location.pathname) link.setAttribute('aria-current', 'page');
  });

  window.DmsAttention = { refresh: refresh };
  refresh();
})();
