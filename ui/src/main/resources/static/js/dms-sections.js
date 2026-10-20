// Collapsible cards on the settings page (templates/fragments/settings-section.html).
// Any card marked [data-settings-section] opens and scrolls into view when the URL hash is its id,
// so a new card gets a working deep link without being registered anywhere.
(function () {
  'use strict';

  const SECTION = '[data-settings-section]';

  function parts(section) {
    return {
      toggle: section.querySelector('.settings-section-toggle'),
      body: section.querySelector('.collapse'),
      icon: section.querySelector('.settings-section-icon')
    };
  }

  function setIcon(section, open) {
    const icon = parts(section).icon;
    if (icon) icon.textContent = open ? 'expand_less' : 'expand_more';
  }

  function targetSection() {
    let id = '';
    try { id = decodeURIComponent(window.location.hash.slice(1)); } catch (e) { return null; }
    if (!id) return null;
    const el = document.getElementById(id);
    // A hash on something inside a card opens that card too.
    return el ? el.closest(SECTION) : null;
  }

  // No animation: the card should already be open when the member lands on it.
  function reveal() {
    const section = targetSection();
    if (!section) return;
    const p = parts(section);
    if (p.body && !p.body.classList.contains('show')) {
      p.body.classList.add('show');
      if (p.toggle) p.toggle.setAttribute('aria-expanded', 'true');
      setIcon(section, true);
    }
    section.scrollIntoView({ block: 'start' });
  }

  document.querySelectorAll(SECTION).forEach(function (section) {
    const body = parts(section).body;
    if (!body) return;
    body.addEventListener('show.bs.collapse', function (e) {
      if (e.target === body) setIcon(section, true);
    });
    body.addEventListener('hide.bs.collapse', function (e) {
      if (e.target === body) setIcon(section, false);
    });
  });

  function clearLoader() {
    // The page loader treats a same-page hash link as a navigation and would keep its bar up.
    setTimeout(function () {
      if (window.mockCompletePage) window.mockCompletePage();
    }, 100);
  }

  window.addEventListener('hashchange', function () {
    reveal();
    clearLoader();
  });

  // A link to the hash the page is already at fires no hashchange, so a card the member has
  // collapsed since would stay shut.
  document.addEventListener('click', function (e) {
    const link = e.target.closest && e.target.closest('a[href*="#"]');
    if (!link || link.pathname !== window.location.pathname || !link.hash) return;
    if (link.hash !== window.location.hash) return;
    reveal();
    clearLoader();
  });

  reveal();
})();
