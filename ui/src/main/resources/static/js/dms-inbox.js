// Notifications inbox: "Load more" reveals the next page of cards that the server already rendered
// (hidden), and the digest chevron follows its disclosure state.
(function () {
  'use strict';

  const button = document.getElementById('inboxLoadMore');
  const count = document.getElementById('inboxCount');
  const status = document.getElementById('inboxStatus');

  function updateCount(shown, total) {
    if (count) count.textContent = 'Showing ' + shown + ' of ' + total + ' updates';
    return 'Showing ' + shown + ' of ' + total + ' updates';
  }

  if (button) {
    const pageSize = parseInt(button.dataset.pageSize, 10);
    const total = parseInt(button.dataset.total, 10);
    let shown = pageSize;

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
      const message = updateCount(shown, total);
      if (status) status.textContent = reveal.length + ' more loaded. ' + message + '.';
      const first = reveal.length ? reveal[0].querySelector('a') : null;
      if (first) first.focus();
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
