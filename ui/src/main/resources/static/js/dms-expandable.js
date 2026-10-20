// Cards that show a few items and hide the rest until expanded.
//   [data-expandable]            the card; its id is the deep link
//   [data-expandable-extra]      items hidden until expanded (also given the `hidden` attribute)
//   [data-expandable-toggle]     the button (aria-expanded/aria-controls); data-more-label and
//                                data-less-label are its text when collapsed / expanded
// Opening the page at #<id> (or an id inside the card) expands the card and scrolls to it.
(function () {
  'use strict';

  function setOpen(card, open) {
    card.querySelectorAll('[data-expandable-extra]').forEach(function (el) { el.hidden = !open; });
    const toggle = card.querySelector('[data-expandable-toggle]');
    if (!toggle) return;
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    toggle.textContent = open ? toggle.dataset.lessLabel : toggle.dataset.moreLabel;
  }

  document.querySelectorAll('[data-expandable]').forEach(function (card) {
    const toggle = card.querySelector('[data-expandable-toggle]');
    if (!toggle) return;
    toggle.addEventListener('click', function () {
      setOpen(card, toggle.getAttribute('aria-expanded') !== 'true');
    });
  });

  function reveal() {
    let id = '';
    try { id = decodeURIComponent(window.location.hash.slice(1)); } catch (e) { return; }
    if (!id) return;
    const el = document.getElementById(id);
    const card = el && el.closest('[data-expandable]');
    if (!card) return;
    setOpen(card, true);
    card.scrollIntoView({ block: 'start' });
  }

  window.addEventListener('hashchange', function () {
    reveal();
    // The page loader treats a same-page hash change as a navigation and would keep its bar up.
    setTimeout(function () { if (window.mockCompletePage) window.mockCompletePage(); }, 100);
  });

  reveal();
})();
