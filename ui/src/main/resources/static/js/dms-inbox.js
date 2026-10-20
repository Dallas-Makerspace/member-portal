// Notifications inbox: "Load more" reveals the next page of cards that the server already rendered
// (hidden), and the digest chevron follows its disclosure state.
(function () {
  'use strict';

  const button = document.getElementById('inboxLoadMore');
  const count = document.getElementById('inboxCount');
  const status = document.getElementById('inboxStatus');

  if (button) {
    const pageSize = parseInt(button.dataset.pageSize, 10);
    const total = parseInt(button.dataset.total, 10);
    const capped = count && count.dataset.capped === 'true';
    let shown = pageSize;

    function countText() {
      return 'Showing ' + shown + ' of ' + (capped ? 'your latest ' : '') + total + ' updates';
    }

    button.addEventListener('click', function () {
      // Day groups that start past the first page are hidden as a whole.
      const hiddenItems = Array.from(document.querySelectorAll('.inbox-group .inbox-item.inbox-extra[hidden]'));
      const reveal = hiddenItems.slice(0, pageSize);
      reveal.forEach(function (li) {
        li.hidden = false;
        const group = li.closest('.inbox-group');
        if (group) group.hidden = false;
      });
      shown = Math.min(total, shown + reveal.length);
      if (count) count.textContent = countText();
      if (status) status.textContent = reveal.length + ' more loaded. ' + countText() + '.';
      // A digest's links are inside its collapsed panel, so focus its toggle instead.
      const target = reveal.length ? reveal[0].querySelector('a, .inbox-digest-toggle') : null;
      (target || count || button).focus();
      if (shown >= total) button.hidden = true;
    });
  }

  document.querySelectorAll('.inbox-digest-toggle').forEach(function (toggle) {
    const panel = document.getElementById(toggle.getAttribute('aria-controls'));
    const chevron = toggle.querySelector('.inbox-chevron');
    if (!panel || !chevron) return;
    panel.addEventListener('show.bs.collapse', function () { chevron.textContent = 'expand_less'; });
    panel.addEventListener('hide.bs.collapse', function () { chevron.textContent = 'expand_more'; });
  });
})();
