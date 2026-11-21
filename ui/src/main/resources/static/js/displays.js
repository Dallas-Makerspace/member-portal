/* Filters the TVs / Displays table in the browser (templates/displays.html). */
(function () {
  'use strict';
  var table = document.getElementById('disp-table');
  if (!table) { return; }
  var filter = document.getElementById('disp-filter');
  var problems = document.getElementById('disp-problems');
  var scope = document.getElementById('disp-scope');
  var none = document.getElementById('disp-none');
  var rows = Array.prototype.slice.call(table.querySelectorAll('tbody tr'));

  function apply() {
    var text = filter.value.trim().toLowerCase();
    var onlyProblems = problems.checked;
    var which = scope.value;
    var shown = 0;
    rows.forEach(function (row) {
      var match = (!text || (row.getAttribute('data-search') || '').indexOf(text) >= 0) &&
          (!onlyProblems || row.getAttribute('data-problem') === 'true') &&
          (which === 'all' || row.getAttribute('data-managed') === (which === 'managed' ? 'true' : 'false'));
      row.hidden = !match;
      if (match) { shown += 1; }
    });
    none.classList.toggle('d-none', shown > 0);
  }

  filter.addEventListener('input', apply);
  problems.addEventListener('change', apply);
  scope.addEventListener('change', apply);
})();
