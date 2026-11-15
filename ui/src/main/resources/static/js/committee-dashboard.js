/* Committee TV dashboard editor (templates/committee-dashboard.html). */
(function () {
  'use strict';

  var app = document.getElementById('dash-app');
  var base = app.getAttribute('data-api');
  var previewUrl = app.getAttribute('data-preview');
  var groupNames = window.DASH_GROUPS || [];

  var LAYOUTS = [
    { id: 'FULL', name: 'One wide', slots: ['Full screen'] },
    { id: 'TWO_COL', name: 'Two columns', slots: ['Left', 'Right'] },
    { id: 'HERO_PLUS_2', name: 'One large, two small', slots: ['Large (left)', 'Top right', 'Bottom right'] },
    { id: 'GRID_2X2', name: 'Four tiles', slots: ['Top left', 'Top right', 'Bottom left', 'Bottom right'] }
  ];
  var WIDGET_TYPES = [
    { id: '', name: 'Empty' },
    { id: 'GROUP_COUNT', name: 'Member counts' },
    { id: 'UPCOMING_CLASSES', name: 'Upcoming classes' },
    { id: 'TEXT_IMAGE', name: 'Text card' }
  ];
  var MAX_GROUPS = 4;
  var MIN_SECONDS = 10;
  var MAX_SECONDS = 300;
  var DEFAULT_SECONDS = 20;
  var uid = 0;

  var state = { pages: [], selected: -1, dirty: false, news: [], tvs: [] };
  var currentTab = 'tab-pages';

  // ---- small helpers ----

  function h(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      var value = attrs[key];
      if (value === null || value === undefined || value === false) { return; }
      if (key === 'text') { node.textContent = value; }
      else if (key.indexOf('on') === 0) { node.addEventListener(key.slice(2), value); }
      else if (key === 'value') { node.value = value; }
      else if (key === 'checked' || key === 'disabled' || key === 'selected') { node[key] = !!value; }
      else { node.setAttribute(key, value === true ? '' : value); }
    });
    (children || []).forEach(function (child) {
      if (child) { node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child); }
    });
    return node;
  }

  function byId(id) { return document.getElementById(id); }
  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }
  function nextId(prefix) { uid += 1; return prefix + uid; }

  function icon(name) { return h('span', { 'class': 'material-symbols-outlined align-middle', style: 'font-size:20px;', 'aria-hidden': 'true', text: name }); }

  /** Runs [render], then puts keyboard focus back on the control that had it (matched by data-fk or id). */
  function keepFocus(render) {
    var active = document.activeElement;
    var key = active && active !== document.body ? (active.getAttribute('data-fk') || '') : '';
    var id = active && active.id ? active.id : '';
    render();
    var target = (key && document.querySelector('[data-fk="' + key + '"]')) || (id && byId(id));
    if (target && target.focus) { target.focus(); }
  }

  function api(method, path, body) {
    return fetch(base + path, {
      method: method,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (json) {
        if (!response.ok) { throw new Error(json.message || 'Something went wrong. Please try again.'); }
        return json;
      });
    });
  }

  function showError(message) {
    var box = byId('dash-alert');
    box.textContent = message;
    box.classList.remove('d-none');
    box.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }
  function clearError() { byId('dash-alert').classList.add('d-none'); }
  function setStatus(text) { byId('dash-status').textContent = text || ''; }

  function setPreviewNote() {
    var note = byId('dash-preview-note');
    if (state.dirty) {
      note.textContent = 'You have unsaved changes — save to update this preview.';
      note.className = 'small mt-2 fw-bold dash-note-warn';
    } else {
      note.textContent = 'Shows the last saved version, as a TV would.';
      note.className = 'text-muted small mt-2';
    }
  }

  // "Save changes" only covers the Pages tab; it stays visible elsewhere while page edits are unsaved.
  function syncSaveBar() {
    var button = byId('dash-save');
    var onPages = currentTab === 'tab-pages';
    button.classList.toggle('d-none', !onPages && !state.dirty);
    button.textContent = onPages ? 'Save changes' : 'Save page changes';
  }

  function markDirty() {
    state.dirty = true;
    byId('dash-save').disabled = false;
    setStatus('Unsaved changes');
    setPreviewNote();
    syncSaveBar();
  }

  function layoutOf(page) {
    return LAYOUTS.filter(function (l) { return l.id === page.layout; })[0] || LAYOUTS[0];
  }

  function pageName(page, index) { return page.title || ('Page ' + (index + 1)); }

  function whenText(iso) {
    if (!iso) { return ''; }
    var date = new Date(iso);
    if (isNaN(date.getTime())) { return iso.replace('T', ' '); }
    return date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  // ---- pages ----

  function loadPages() {
    return api('GET', '/dashboard').then(function (json) {
      state.pages = (json.data && json.data.pages) || [];
      state.selected = state.pages.length ? 0 : -1;
      state.dirty = false;
      byId('dash-save').disabled = true;
      setPreviewNote();
      renderPages();
    });
  }

  function renderPages() {
    keepFocus(function () {
      renderPageList();
      renderPageCard();
      renderWidgets();
    });
  }

  function renderPageList() {
    var list = byId('dash-page-list');
    clear(list);
    if (!state.pages.length) {
      list.appendChild(h('div', { 'class': 'text-muted py-3', text: 'No pages yet. Add a page to get started.' }));
      return;
    }
    state.pages.forEach(function (page, index) {
      var active = index === state.selected;
      var name = pageName(page, index);
      var row = h('div', { 'class': 'list-group-item dash-page-row' + (active ? ' active' : '') }, [
        h('button', {
          type: 'button', 'class': 'dash-page-pick', 'aria-current': active ? 'true' : null, 'data-fk': 'pick-' + index,
          onclick: function () { state.selected = index; renderPages(); }
        }, [
          h('span', { 'class': 'dash-page-num', text: String(index + 1) }),
          h('span', { 'class': 'dash-page-name' }, [
            h('span', { 'class': 'dash-page-title-text', text: page.title || 'Untitled page' }),
            h('span', { 'class': 'dash-page-meta', text: layoutOf(page).name + ' · ' + page.durationSeconds + 's' + (page.enabled ? '' : ' · hidden') })
          ])
        ]),
        h('span', { 'class': 'dash-page-actions' }, [
          iconButton('arrow_upward', 'Move ' + name + ' up', 'up-' + index, index === 0, function () { movePage(index, -1); }),
          iconButton('arrow_downward', 'Move ' + name + ' down', 'down-' + index, index === state.pages.length - 1, function () { movePage(index, 1); }),
          iconButton('delete', 'Delete ' + name, 'del-' + index, false, function () { deletePage(index); })
        ])
      ]);
      list.appendChild(row);
    });
  }

  function iconButton(name, label, fk, disabled, onclick) {
    return h('button', { type: 'button', 'class': 'btn dash-icon-btn', 'aria-label': label, title: label, 'data-fk': fk, disabled: disabled, onclick: onclick }, [icon(name)]);
  }

  function addPage() {
    state.pages.push({ title: '', layout: 'TWO_COL', durationSeconds: DEFAULT_SECONDS, enabled: true, widgets: [] });
    state.selected = state.pages.length - 1;
    markDirty();
    renderPages();
    byId('dash-page-title').focus();
  }

  function movePage(index, delta) {
    var target = index + delta;
    var moved = state.pages.splice(index, 1)[0];
    state.pages.splice(target, 0, moved);
    state.selected = target;
    markDirty();
    setStatus('Moved ' + pageName(moved, target) + ' to position ' + (target + 1) + '. Unsaved changes.');
    // the button that was pressed moved with its page
    var next = delta < 0 ? 'up-' + target : 'down-' + target;
    renderPages();
    var button = document.querySelector('[data-fk="' + next + '"]');
    if (button && !button.disabled) { button.focus(); } else { var pick = document.querySelector('[data-fk="pick-' + target + '"]'); if (pick) { pick.focus(); } }
  }

  function deletePage(index) {
    var page = state.pages[index];
    if (!window.confirm('Delete "' + pageName(page, index) + '"?')) { return; }
    state.pages.splice(index, 1);
    state.selected = Math.min(state.selected, state.pages.length - 1);
    markDirty();
    renderPages();
    var pick = document.querySelector('[data-fk="pick-' + Math.max(state.selected, 0) + '"]');
    if (pick) { pick.focus(); } else { byId('dash-add-page').focus(); }
  }

  function renderPageCard() {
    var card = byId('dash-page-card');
    var page = state.pages[state.selected];
    card.classList.toggle('d-none', !page);
    if (!page) { return; }
    var title = byId('dash-page-title');
    if (document.activeElement !== title) { title.value = page.title || ''; }
    var seconds = byId('dash-page-seconds');
    if (document.activeElement !== seconds) { seconds.value = page.durationSeconds; seconds.classList.remove('is-invalid'); seconds.removeAttribute('aria-invalid'); }
    byId('dash-page-enabled').checked = page.enabled;
    var holder = byId('dash-layouts');
    clear(holder);
    var group = nextId('layout-group-');
    LAYOUTS.forEach(function (layout) {
      var checked = layout.id === page.layout;
      var input = h('input', { type: 'radio', name: group, 'class': 'dash-layout-input', value: layout.id, checked: checked, 'data-fk': 'layout-' + layout.id });
      input.addEventListener('change', function () { setLayout(layout.id); });
      holder.appendChild(h('label', { 'class': 'dash-layout' + (checked ? ' on' : '') }, [
        input,
        miniLayout(layout),
        h('span', { 'class': 'dash-layout-name', text: layout.name }),
        checked ? h('span', { 'class': 'dash-layout-check material-symbols-outlined', 'aria-hidden': 'true', text: 'check_circle' }) : null
      ]));
    });
  }

  function miniLayout(layout) {
    var cells = [];
    for (var i = 0; i < layout.slots.length; i++) { cells.push(h('i')); }
    return h('span', { 'class': 'dash-mini dash-mini-' + layout.id, 'aria-hidden': 'true' }, cells);
  }

  function setLayout(id) {
    var page = state.pages[state.selected];
    page.layout = id;
    var slots = layoutOf(page).slots.length;
    var dropped = page.widgets.filter(function (w) { return w.slot >= slots; }).length;
    page.widgets = page.widgets.filter(function (w) { return w.slot < slots; });
    markDirty();
    if (dropped) { setStatus('Unsaved changes — ' + dropped + ' widget' + (dropped > 1 ? 's' : '') + ' removed because the new layout has fewer slots'); }
    renderPages();
  }

  // ---- widgets ----

  function widgetAt(page, slot) {
    return page.widgets.filter(function (w) { return w.slot === slot; })[0] || null;
  }

  function renderWidgets() {
    var holder = byId('dash-widgets');
    clear(holder);
    var page = state.pages[state.selected];
    if (!page) { return; }
    var names = layoutOf(page).slots;
    for (var slot = 0; slot < names.length; slot++) { holder.appendChild(slotCard(page, slot, names)); }
  }

  function slotCard(page, slot, names) {
    var widget = widgetAt(page, slot);
    var selectId = 'dash-slot-' + slot;
    var titleId = 'dash-slot-title-' + slot;
    var select = h('select', { 'class': 'form-select', id: selectId, 'aria-label': names[slot] + ' slot: what to show', onchange: function () { setWidgetType(page, slot, select.value); } },
      WIDGET_TYPES.map(function (t) { return h('option', { value: t.id, text: t.name, selected: (widget ? widget.type : '') === t.id }); }));
    var body = h('div', { 'class': 'dash-slot-body' });
    if (widget) { fillWidgetForm(body, widget); }
    return h('div', { 'class': 'dms-card shadow-sm mb-3', role: 'group', 'aria-labelledby': titleId }, [
      h('div', { 'class': 'card-body' }, [
        h('div', { 'class': 'd-flex align-items-center justify-content-between gap-3 mt-3 mb-2' }, [
          h('h6', { 'class': 'dms-card-title mb-0 dash-slot-title', id: titleId }, [names[slot], h('span', { 'class': 'dash-slot-sub', text: ' · slot ' + (slot + 1) })]),
          h('div', { 'class': 'dash-slot-type' }, [select])
        ]),
        body
      ])
    ]);
  }

  function setWidgetType(page, slot, type) {
    page.widgets = page.widgets.filter(function (w) { return w.slot !== slot; });
    if (type) { page.widgets.push({ slot: slot, type: type, config: { groupNames: [], showTrend: true, limit: 5 } }); }
    markDirty();
    renderWidgets();
    var select = byId('dash-slot-' + slot);
    if (select) { select.focus(); }
  }

  function textField(label, value, maxlength, onchange, multiline) {
    var id = nextId('f');
    var input = h(multiline ? 'textarea' : 'input', { 'class': 'form-control', id: id, maxlength: maxlength, rows: multiline ? 3 : null, value: value || '' });
    input.addEventListener('input', function () { onchange(input.value); markDirty(); });
    return h('div', { 'class': 'mb-3' }, [h('label', { 'class': 'form-label', 'for': id, text: label }), input]);
  }

  function groupPicker(widget, max, legend, help) {
    var wrap = h('fieldset', { 'class': 'mb-2' }, [h('legend', { 'class': 'form-label fs-6', text: legend })]);
    if (!groupNames.length) {
      wrap.appendChild(h('div', { 'class': 'text-muted small', text: 'No groups found for this committee.' }));
      return wrap;
    }
    var boxes = [];
    function sync() {
      var count = boxes.filter(function (b) { return b.checked; }).length;
      boxes.forEach(function (b) { b.disabled = !!max && !b.checked && count >= max; });
    }
    var list = h('div', { 'class': 'dash-groups' });
    groupNames.forEach(function (name) {
      var id = nextId('g');
      var box = h('input', { 'class': 'form-check-input', type: 'checkbox', id: id, checked: widget.config.groupNames.indexOf(name) >= 0 });
      box.addEventListener('change', function () {
        var chosen = widget.config.groupNames.filter(function (g) { return g !== name; });
        if (box.checked) { chosen.push(name); }
        widget.config.groupNames = groupNames.filter(function (g) { return chosen.indexOf(g) >= 0; });
        sync();
        markDirty();
      });
      boxes.push(box);
      list.appendChild(h('div', { 'class': 'form-check' }, [box, h('label', { 'class': 'form-check-label', 'for': id, text: name })]));
    });
    wrap.appendChild(list);
    if (help) { wrap.appendChild(h('div', { 'class': 'form-text', text: help })); }
    sync();
    return wrap;
  }

  function fillWidgetForm(body, widget) {
    var config = widget.config;
    if (widget.type === 'GROUP_COUNT') {
      body.appendChild(textField('Heading (optional)', config.heading, 80, function (v) { config.heading = v || null; }));
      body.appendChild(groupPicker(widget, MAX_GROUPS, 'Groups to count', 'Choose up to ' + MAX_GROUPS + ' groups. The TV shows how many members are in each.'));
      var trendId = nextId('f');
      var trend = h('input', { 'class': 'form-check-input', type: 'checkbox', role: 'switch', id: trendId, checked: config.showTrend });
      trend.addEventListener('change', function () { config.showTrend = trend.checked; markDirty(); });
      body.appendChild(h('div', { 'class': 'form-check form-switch mt-3' }, [trend, h('label', { 'class': 'form-check-label', 'for': trendId, text: 'Show the 12-week trend line' })]));
    } else if (widget.type === 'UPCOMING_CLASSES') {
      body.appendChild(textField('Heading (optional)', config.heading, 80, function (v) { config.heading = v || null; }));
      var limitId = nextId('f');
      var limit = h('input', { 'class': 'form-control', id: limitId, type: 'number', min: 1, max: 8, value: String(config.limit || 5) });
      limit.addEventListener('input', function () {
        var n = parseInt(limit.value, 10);
        var valid = n >= 1 && n <= 8;
        limit.classList.toggle('is-invalid', !valid);
        if (valid) { limit.removeAttribute('aria-invalid'); config.limit = n; markDirty(); }
        else { limit.setAttribute('aria-invalid', 'true'); setStatus('Classes to list must be 1 to 8.'); }
      });
      body.appendChild(h('div', { 'class': 'mb-3 dash-narrow' }, [h('label', { 'class': 'form-label', 'for': limitId, text: 'Classes to list (1–8)' }), limit]));
      body.appendChild(groupPicker(widget, 0, 'Only classes that lead to these groups', 'Leave all unchecked to list every upcoming class for the committee.'));
    } else {
      body.appendChild(textField('Heading', config.heading, 80, function (v) { config.heading = v || null; }));
      body.appendChild(textField('Text', config.body, 600, function (v) { config.body = v || null; }, true));
      body.appendChild(pictureField(widget));
    }
  }

  // ---- pictures ----

  var MAX_PICTURE_BYTES = 5 * 1024 * 1024;

  function uploadPicture(file) {
    return fetch(base + '/dashboard/images', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': file.type },
      body: file
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (json) {
        if (!response.ok || !json.data || !json.data.key) { throw new Error(json.message || 'The picture could not be saved.'); }
        return json.data.key;
      });
    });
  }

  function pictureField(widget) {
    var config = widget.config;
    var id = nextId('f');
    var note = h('div', { 'class': 'form-text', id: id + '-note', role: 'status', text: 'JPEG or PNG up to 5 MB. It is resized for the TV.' });
    var input = h('input', { 'class': 'form-control', type: 'file', id: id, accept: 'image/jpeg,image/png', 'aria-describedby': id + '-note' });
    input.addEventListener('change', function () {
      var file = input.files && input.files[0];
      if (!file) { return; }
      if (file.size > MAX_PICTURE_BYTES) { note.textContent = 'That picture is over 5 MB.'; input.value = ''; return; }
      note.textContent = 'Uploading…';
      uploadPicture(file).then(function (key) {
        config.imageKey = key;
        markDirty();
        renderWidgets();
        var again = byId('dash-slot-' + widget.slot);
        if (again) { again.focus(); }
      }).catch(function (e) { note.textContent = e.message; input.value = ''; });
    });
    var children = [h('label', { 'class': 'form-label', 'for': id, text: config.imageKey ? 'Replace picture' : 'Picture (optional)' })];
    if (config.imageKey) {
      children.push(h('div', { 'class': 'dash-thumb-row' }, [
        h('img', { 'class': 'dash-thumb', src: base + '/dashboard/images/' + config.imageKey, alt: 'Picture on this card' }),
        h('button', { type: 'button', 'class': 'btn btn-outline-secondary btn-sm', 'data-fk': 'rm-pic-' + widget.slot, text: 'Remove picture', onclick: function () {
          config.imageKey = null;
          markDirty();
          renderWidgets();
          setStatus('Picture removed. Unsaved changes.');
        } })
      ]));
    }
    children.push(input, note);
    return h('div', { 'class': 'mb-2' }, children);
  }

  // ---- announcements ----

  function loadNews() {
    return api('GET', '/dashboard/announcements').then(function (json) {
      state.news = json.data || [];
      renderNews();
    });
  }

  function renderNews() {
    keepFocus(function () {
      var list = byId('dash-news-list');
      clear(list);
      if (!state.news.length) { list.appendChild(h('div', { 'class': 'text-muted py-2', text: 'No announcements yet.' })); }
      state.news.forEach(function (item, index) { list.appendChild(newsRow(item, index)); });
    });
  }

  function newsRow(item, index) {
    var n = index + 1;
    var text = h('input', { 'class': 'form-control', id: 'news-text-' + n, maxlength: 120, value: item.text || '', placeholder: 'What should the TV say?', 'aria-describedby': 'news-help-' + n });
    var starts = h('input', { 'class': 'form-control', id: 'news-start-' + n, type: 'datetime-local', value: item.startsAt || '' });
    var ends = h('input', { 'class': 'form-control', id: 'news-end-' + n, type: 'datetime-local', value: item.endsAt || '' });
    var save = h('button', { type: 'button', 'class': 'btn btn-primary', 'aria-label': 'Save announcement ' + n, 'data-fk': 'news-save-' + n, text: 'Save', onclick: function () {
      var body = { text: text.value, startsAt: starts.value || null, endsAt: ends.value || null };
      var request = item.id ? api('PUT', '/dashboard/announcements/' + item.id, body) : api('POST', '/dashboard/announcements', body);
      request.then(function () { clearError(); setStatus('Announcement ' + n + ' saved'); return loadNews(); }).catch(function (e) { showError(e.message); });
    } });
    var remove = h('button', { type: 'button', 'class': 'btn btn-outline-danger', 'aria-label': 'Delete announcement ' + n, 'data-fk': 'news-del-' + n, text: 'Delete', onclick: function () {
      if (!item.id) { state.news.splice(index, 1); renderNews(); return; }
      if (!window.confirm('Delete this announcement?')) { return; }
      api('DELETE', '/dashboard/announcements/' + item.id).then(function () { clearError(); setStatus('Announcement deleted'); return loadNews(); }).then(function () { byId('dash-add-news').focus(); }).catch(function (e) { showError(e.message); });
    } });
    return h('div', { 'class': 'dash-row' }, [
      h('div', { 'class': 'mb-2' }, [
        h('label', { 'class': 'form-label', 'for': 'news-text-' + n, text: 'Announcement ' + n }),
        text,
        h('div', { 'class': 'form-text', id: 'news-help-' + n, text: 'Up to 120 characters, so it fits on the screen.' })
      ]),
      h('div', { 'class': 'row g-2 align-items-end' }, [
        h('div', { 'class': 'col-sm-4' }, [h('label', { 'class': 'form-label small', 'for': 'news-start-' + n, text: 'Show from (optional)' }), starts]),
        h('div', { 'class': 'col-sm-4' }, [h('label', { 'class': 'form-label small', 'for': 'news-end-' + n, text: 'Show until (optional)' }), ends]),
        h('div', { 'class': 'col-sm-4 d-flex gap-2 justify-content-sm-end' }, [save, remove])
      ]),
      h('div', { 'class': 'form-text mt-1', text: 'Times are Central. Leave both blank to show it right away until you delete it.' })
    ]);
  }

  // ---- TVs ----

  function loadTvs() {
    return api('GET', '/cast-targets').then(function (json) {
      state.tvs = json.data || [];
      renderTvs();
    });
  }

  function renderTvs() {
    keepFocus(function () {
      var list = byId('dash-tv-list');
      clear(list);
      if (!state.tvs.length) { list.appendChild(h('div', { 'class': 'text-muted py-2', text: 'No TVs yet.' })); }
      state.tvs.forEach(function (tv, index) { list.appendChild(tvRow(tv, index)); });
    });
  }

  function field(id, label, input, help, helpId) {
    return h('div', { 'class': 'mb-2' }, [h('label', { 'class': 'form-label small', 'for': id, text: label }), input, help ? h('div', { 'class': 'form-text', id: helpId, text: help }) : null]);
  }

  function tvRow(tv, index) {
    var n = index + 1;
    var label = tv.name || ('TV ' + n);
    var name = h('input', { 'class': 'form-control', id: 'tv-name-' + n, maxlength: 80, value: tv.name || '', placeholder: 'e.g. Shop floor TV' });
    var host = h('input', { 'class': 'form-control', id: 'tv-host-' + n, maxlength: 255, value: tv.host || '', placeholder: 'e.g. tv-1.example.internal', 'aria-describedby': 'tv-host-help-' + n });
    var port = h('input', { 'class': 'form-control', id: 'tv-port-' + n, type: 'number', min: 1, max: 65535, value: String(tv.port || 8009) });
    var from = h('input', { 'class': 'form-control', id: 'tv-from-' + n, type: 'time', value: tv.displayStart || '', 'aria-describedby': 'tv-hours-help-' + n });
    var to = h('input', { 'class': 'form-control', id: 'tv-to-' + n, type: 'time', value: tv.displayEnd || '', 'aria-describedby': 'tv-hours-help-' + n });
    var reclaim = h('input', { 'class': 'form-control', id: 'tv-reclaim-' + n, type: 'number', min: 1, max: 1440, value: tv.reclaimAfterMinutes ? String(tv.reclaimAfterMinutes) : '', placeholder: 'Never', 'aria-describedby': 'tv-reclaim-help-' + n });
    var enabled = h('input', { 'class': 'form-check-input', type: 'checkbox', role: 'switch', id: 'tv-on-' + n, checked: tv.enabled !== false });
    function body() {
      return {
        name: name.value, host: host.value.trim(), port: parseInt(port.value, 10) || 8009, enabled: enabled.checked,
        displayStart: from.value || null, displayEnd: to.value || null,
        reclaimAfterMinutes: reclaim.value ? parseInt(reclaim.value, 10) : null
      };
    }
    var save = h('button', { type: 'button', 'class': 'btn btn-primary', 'aria-label': 'Save ' + label, 'data-fk': 'tv-save-' + n, text: 'Save', onclick: function () {
      var request = tv.id ? api('PUT', '/cast-targets/' + tv.id, body()) : api('POST', '/cast-targets', body());
      request.then(function () { clearError(); setStatus(label + ' saved'); return loadTvs(); }).catch(function (e) { showError(e.message); });
    } });
    var actions = [save];
    if (tv.id) {
      actions.push(h('button', { type: 'button', 'class': 'btn btn-outline-secondary', 'aria-label': 'Revoke access for ' + label, title: 'Cuts off the key this TV is using; it gets a new one the next time it connects', 'data-fk': 'tv-revoke-' + n, text: 'Revoke access', onclick: function () {
        if (!window.confirm('Cut off ' + label + '? It gets a new key the next time it connects.')) { return; }
        api('POST', '/cast-targets/' + tv.id + '/revoke').then(function () { clearError(); setStatus('Access revoked for ' + label); return loadTvs(); }).catch(function (e) { showError(e.message); });
      } }));
    }
    actions.push(h('button', { type: 'button', 'class': 'btn btn-outline-danger', 'aria-label': 'Remove ' + label, 'data-fk': 'tv-remove-' + n, text: 'Remove', onclick: function () {
      if (!tv.id) { state.tvs.splice(index, 1); renderTvs(); return; }
      if (!window.confirm('Remove "' + label + '" from this dashboard?')) { return; }
      api('DELETE', '/cast-targets/' + tv.id).then(function () { clearError(); setStatus(label + ' removed'); return loadTvs(); }).then(function () { byId('dash-add-tv').focus(); }).catch(function (e) { showError(e.message); });
    } }));
    var seen = tv.lastSeenAt ? 'Last seen ' + whenText(tv.lastSeenAt) + (tv.lastStatus ? ' · ' + tv.lastStatus : '') : (tv.id ? 'Not seen yet' : '');
    return h('div', { 'class': 'dash-row', role: 'group', 'aria-label': label }, [
      h('div', { 'class': 'row g-3' }, [
        h('div', { 'class': 'col-md-4' }, [field('tv-name-' + n, 'Name', name)]),
        h('div', { 'class': 'col-md-5' }, [field('tv-host-' + n, 'Host or address', host, 'Ask IT if you are not sure.', 'tv-host-help-' + n)]),
        h('div', { 'class': 'col-md-3' }, [field('tv-port-' + n, 'Port', port)])
      ]),
      h('div', { 'class': 'row g-3' }, [
        h('div', { 'class': 'col-6 col-md-3' }, [field('tv-from-' + n, 'Turn on at', from)]),
        h('div', { 'class': 'col-6 col-md-3' }, [field('tv-to-' + n, 'Turn off at', to)]),
        h('div', { 'class': 'col-md-4' }, [field('tv-reclaim-' + n, 'If someone else casts, switch back after (minutes)', reclaim, 'Leave blank to leave the TV alone.', 'tv-reclaim-help-' + n)]),
        h('div', { 'class': 'col-md-2 d-flex align-items-center' }, [h('div', { 'class': 'form-check form-switch dash-switch-row' }, [enabled, h('label', { 'class': 'form-check-label', 'for': 'tv-on-' + n, text: 'Enabled' })])])
      ]),
      h('div', { 'class': 'form-text', id: 'tv-hours-help-' + n, text: 'Times are Central. Leave both blank to keep the dashboard on all day.' }),
      h('div', { 'class': 'd-flex flex-wrap align-items-center justify-content-between gap-2 mt-2' }, [
        h('span', { 'class': 'small text-muted', text: seen }),
        h('div', { 'class': 'd-flex flex-wrap gap-2' }, actions)
      ])
    ]);
  }

  // ---- wiring ----

  function savePages() {
    clearError();
    var button = byId('dash-save');
    button.disabled = true;
    setStatus('Saving…');
    api('PUT', '/dashboard', { pages: state.pages }).then(function (json) {
      state.pages = (json.data && json.data.pages) || [];
      state.selected = Math.min(state.selected, state.pages.length - 1);
      state.dirty = false;
      setStatus('Saved. TVs will update shortly.');
      setPreviewNote();
      syncSaveBar();
      renderPages();
      refreshPreview();
    }).catch(function (e) {
      button.disabled = false;
      setStatus('Unsaved changes');
      showError(e.message);
    });
  }

  function refreshPreview() { byId('dash-preview').src = previewUrl; }

  function onSeconds(e) {
    var input = e.target;
    var value = parseInt(input.value, 10);
    var valid = value >= MIN_SECONDS && value <= MAX_SECONDS;
    input.classList.toggle('is-invalid', !valid);
    if (valid) { input.removeAttribute('aria-invalid'); state.pages[state.selected].durationSeconds = value; markDirty(); renderPageList(); }
    else { input.setAttribute('aria-invalid', 'true'); }
  }

  byId('dash-add-page').addEventListener('click', addPage);
  byId('dash-save').addEventListener('click', savePages);
  byId('dash-refresh-preview').addEventListener('click', refreshPreview);
  byId('dash-add-news').addEventListener('click', function () { state.news.push({ text: '' }); renderNews(); byId('news-text-' + state.news.length).focus(); });
  byId('dash-add-tv').addEventListener('click', function () { state.tvs.push({ name: '', host: '', port: 8009, enabled: true }); renderTvs(); byId('tv-name-' + state.tvs.length).focus(); });
  byId('dash-page-title').addEventListener('input', function (e) { state.pages[state.selected].title = e.target.value; markDirty(); renderPageList(); });
  byId('dash-page-seconds').addEventListener('input', onSeconds);
  byId('dash-page-seconds').addEventListener('change', function (e) {
    var page = state.pages[state.selected];
    if (e.target.classList.contains('is-invalid')) { setStatus('Show time must be 10 to 300 seconds; kept ' + page.durationSeconds + '.'); }
    e.target.value = page.durationSeconds; e.target.classList.remove('is-invalid'); e.target.removeAttribute('aria-invalid');
  });
  byId('dash-page-enabled').addEventListener('change', function (e) { state.pages[state.selected].enabled = e.target.checked; markDirty(); renderPageList(); });
  document.getElementById('dash-tabs').addEventListener('shown.bs.tab', function (e) {
    currentTab = e.target.id;
    syncSaveBar();
  });
  window.addEventListener('beforeunload', function (e) { if (state.dirty) { e.preventDefault(); e.returnValue = ''; } });

  loadPages().then(refreshPreview).catch(function (e) { showError(e.message); });
  loadNews().catch(function (e) { showError(e.message); });
  loadTvs().catch(function (e) { showError(e.message); });
})();
