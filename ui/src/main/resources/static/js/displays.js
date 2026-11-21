/* Filters the TVs / Displays table in the browser (templates/displays.html). */
(function () {
  'use strict';
  var table = document.getElementById('disp-table');
  if (!table) { return; }
  var filter = document.getElementById('disp-filter');
  var scopes = Array.prototype.slice.call(document.querySelectorAll('input[name="disp-scope"]'));
  var none = document.getElementById('disp-none');
  var rows = Array.prototype.slice.call(table.querySelectorAll('tbody tr'));

  function inScope(row, which) {
    if (which === 'attention') { return row.getAttribute('data-problem') === 'true'; }
    if (which === 'managed') { return row.getAttribute('data-managed') === 'true'; }
    if (which === 'unmanaged') { return row.getAttribute('data-managed') === 'false'; }
    return true;
  }

  function apply() {
    var text = filter.value.trim().toLowerCase();
    var which = (scopes.filter(function (s) { return s.checked; })[0] || { value: 'all' }).value;
    var shown = 0;
    rows.forEach(function (row) {
      var match = (!text || (row.getAttribute('data-search') || '').indexOf(text) >= 0) && inScope(row, which);
      row.hidden = !match;
      if (match) { shown += 1; }
    });
    none.classList.toggle('d-none', shown > 0);
  }

  filter.addEventListener('input', apply);
  scopes.forEach(function (s) { s.addEventListener('change', apply); });
})();
