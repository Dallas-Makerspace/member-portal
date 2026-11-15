/*
 * TV dashboard renderer (templates/tv-receiver.html).
 *
 * mode "tv":      a Cast receiver. The cast agent sends {type:"token"} and {type:"reload"} on
 *                 NAMESPACE; the token is kept in memory only and sent as a Bearer header.
 * mode "preview": the committee editor's preview; authenticated by the signed-in session.
 *
 * Nothing polls: the page reloads on a "reload" message and on one deadline timer set from the
 * payload's nextChangeAtMs. Plain ES5-style code, since Chromecast browsers can be old.
 */
(function () {
  'use strict';

  var NAMESPACE = 'urn:x-cast:org.dallasmakerspace.dashboard';
  var NEWS_ROTATE_MS = 9000;
  var MAX_TIMER_MS = 2147483000;
  var RETRY_START_MS = 15000;
  var RETRY_MAX_MS = 300000;
  var SVG_NS = 'http://www.w3.org/2000/svg';

  var mode = document.body.getAttribute('data-mode');
  var source = document.body.getAttribute('data-source');
  var imageBase = document.body.getAttribute('data-images') || '';
  // The editor preview doesn't auto-rotate for people who asked for less motion.
  var still = mode === 'preview' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var token = null;
  var pages = [];
  var pageIndex = 0;
  var pageTimer = null;
  var changeTimer = null;
  var newsTimer = null;
  var retryTimer = null;
  var retryMs = RETRY_START_MS;
  var qrUrl = null;
  var imageUrls = [];

  function byId(id) { return document.getElementById(id); }

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }

  function waiting(text) {
    var overlay = byId('tv-waiting');
    if (text === null) { overlay.hidden = true; return; }
    byId('tv-waiting-text').textContent = text;
    overlay.hidden = false;
  }

  // ---- loading ----

  function load() {
    clearTimeout(retryTimer);
    var headers = {};
    if (mode === 'tv') {
      if (!token) { waiting('Waiting for the dashboard…'); return; }
      headers.Authorization = 'Bearer ' + token;
    }
    fetch(source, { headers: headers, cache: 'no-store', credentials: mode === 'preview' ? 'same-origin' : 'omit' })
      .then(function (response) {
        if (response.status === 401 || response.status === 403) {
          token = null;
          waiting(mode === 'tv' ? 'Reconnecting…' : 'You can’t preview this dashboard.');
          return null;
        }
        if (!response.ok) { throw new Error('HTTP ' + response.status); }
        return response.json();
      })
      .then(function (json) {
        if (json && json.data) { retryMs = RETRY_START_MS; apply(json.data); }
      })
      .catch(function () {
        if (!pages.length) { waiting('Waiting for the dashboard…'); }
        retryTimer = setTimeout(load, retryMs);
        retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
      });
  }

  function apply(data) {
    waiting(null);
    byId('tv-committee').textContent = data.committeeName || '';
    renderQr(data.qrUrl);
    renderUpdated(data.generatedAtMs);
    pages = data.pages || [];
    pageIndex = 0;
    showPage();
    renderNews(data.announcements || []);
    clearTimeout(changeTimer);
    var wait = (data.nextChangeAtMs || 0) - Date.now();
    changeTimer = setTimeout(load, Math.min(Math.max(wait, 5000), MAX_TIMER_MS));
  }

  function renderUpdated(ms) {
    var node = byId('tv-updated');
    if (!ms) { node.textContent = ''; return; }
    var time;
    try {
      time = new Date(ms).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: 'America/Chicago' });
    } catch (e) {
      time = new Date(ms).toLocaleTimeString();
    }
    node.textContent = 'Updated ' + time;
  }

  // ---- pages ----

  function showPage() {
    clearTimeout(pageTimer);
    var stage = byId('tv-stage');
    clear(stage);
    imageUrls.forEach(function (url) { URL.revokeObjectURL(url); });
    imageUrls = [];
    renderDots();
    if (!pages.length) {
      stage.setAttribute('data-layout', 'FULL');
      var empty = el('div', 'tv-w');
      empty.appendChild(el('div', 'tv-empty', 'Nothing to show yet.'));
      stage.appendChild(empty);
      byId('tv-page-title').textContent = '';
      return;
    }
    var page = pages[pageIndex];
    byId('tv-page-title').textContent = page.title || '';
    stage.setAttribute('data-layout', page.layout);
    (page.widgets || []).forEach(function (widget) { stage.appendChild(renderWidget(widget)); });
    Array.prototype.forEach.call(stage.querySelectorAll('.tv-classes'), fitRows);
    Array.prototype.forEach.call(stage.querySelectorAll('.tv-text-body'), fitText);
    stage.classList.remove('fade');
    void stage.offsetWidth;
    stage.classList.add('fade');
    if (pages.length > 1 && !still) {
      pageTimer = setTimeout(function () {
        pageIndex = (pageIndex + 1) % pages.length;
        showPage();
      }, Math.max(10, page.durationSeconds || 20) * 1000);
    }
  }

  function renderDots() {
    var dots = byId('tv-dots');
    clear(dots);
    if (pages.length < 2) { return; }
    pages.forEach(function (page, index) {
      dots.appendChild(el('span', index === pageIndex ? 'tv-dot on' : 'tv-dot'));
    });
  }

  // ---- widgets ----

  function renderWidget(widget) {
    var box = el('section', 'tv-w');
    if (widget.type === 'GROUP_COUNT') { renderCounts(box, widget); }
    else if (widget.type === 'UPCOMING_CLASSES') { renderClasses(box, widget); }
    else { renderText(box, widget); }
    return box;
  }

  function renderCounts(box, widget) {
    var groups = widget.groups || [];
    box.appendChild(el('div', 'tv-w-head', widget.heading || 'Members'));
    if (groups.length === 1) { box.classList.add('solo'); }
    var grid = el('div', 'tv-counts');
    grid.setAttribute('data-n', String(groups.length));
    groups.forEach(function (group) {
      var tile = el('div', 'tv-count');
      var top = el('div');
      var known = group.count !== null && group.count !== undefined;
      top.appendChild(el('div', known ? 'tv-count-num' : 'tv-count-num unknown', known ? String(group.count) : 'No data'));
      top.appendChild(el('div', 'tv-count-name', group.name));
      tile.appendChild(top);
      if (group.trend && group.trend.length > 1) { tile.appendChild(sparkline(group.trend)); }
      grid.appendChild(tile);
    });
    box.appendChild(grid);
  }

  function sparkline(points) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'tv-count-trend');
    svg.setAttribute('viewBox', '0 0 100 30');
    svg.setAttribute('preserveAspectRatio', 'none');
    var min = Math.min.apply(null, points);
    var max = Math.max.apply(null, points);
    var span = Math.max(max - min, 1);
    var coords = points.map(function (value, index) {
      var x = (index / (points.length - 1)) * 100;
      var y = 27 - ((value - min) / span) * 24;
      return x.toFixed(2) + ',' + y.toFixed(2);
    });
    var line = document.createElementNS(SVG_NS, 'polyline');
    line.setAttribute('points', coords.join(' '));
    svg.appendChild(line);
    return svg;
  }

  function renderClasses(box, widget) {
    box.appendChild(el('div', 'tv-w-head', widget.heading || 'Upcoming classes'));
    var list = el('div', 'tv-classes');
    var rows = widget.classes || [];
    if (!rows.length) { list.appendChild(el('div', 'tv-empty', 'No classes scheduled yet.')); }
    rows.forEach(function (row) {
      var item = el('div', 'tv-class');
      item.appendChild(el('div', 'tv-class-name', row.name));
      item.appendChild(el('div', 'tv-class-when', row.startLabel));
      list.appendChild(item);
    });
    box.appendChild(list);
  }

  // Shows as many lines of a text card as fit, ending in an ellipsis instead of a half-cut line.
  function fitText(node) {
    node.style.webkitLineClamp = '100';
    node.style.maxHeight = '';
    node.style.alignSelf = '';
    var line = parseFloat(window.getComputedStyle(node).lineHeight);
    if (!line || !node.clientHeight) { return; }
    var lines = Math.max(1, Math.floor(node.clientHeight / line));
    node.style.webkitLineClamp = String(lines);
    node.style.maxHeight = (lines * line) + 'px';
    node.style.alignSelf = 'start';
  }

  // Drops rows that would be cut off, so the TV never shows a half-visible class.
  function fitRows(list) {
    while (list.children.length > 1 && list.scrollHeight > list.clientHeight + 1) { list.removeChild(list.lastChild); }
  }

  function renderText(box, widget) {
    var wrap = el('div', 'tv-text');
    if (widget.imageKey) { wrap.className = 'tv-text has-image' + (widget.body ? '' : ' no-body'); }
    if (widget.heading) { wrap.appendChild(el('div', 'tv-text-head', widget.heading)); }
    if (widget.body) { wrap.appendChild(el('div', 'tv-text-body', widget.body)); }
    if (widget.imageKey) { loadImage(wrap, imageBase + widget.imageKey); }
    box.appendChild(wrap);
  }

  function loadImage(wrap, url) {
    var headers = token ? { Authorization: 'Bearer ' + token } : {};
    fetch(url, { headers: headers, cache: 'force-cache', credentials: mode === 'preview' ? 'same-origin' : 'omit' })
      .then(function (response) { return response.ok ? response.blob() : null; })
      .then(function (blob) {
        if (!blob) { return; }
        var objectUrl = URL.createObjectURL(blob);
        imageUrls.push(objectUrl);
        var image = document.createElement('img');
        image.src = objectUrl;
        image.alt = '';
        wrap.appendChild(image);
      })
      .catch(function () { /* the card just shows its text */ });
  }

  // ---- bottom row ----

  function renderNews(items) {
    clearInterval(newsTimer);
    var node = byId('tv-news-text');
    var index = 0;
    function show() {
      node.classList.remove('fade');
      void node.offsetWidth;
      node.classList.add('fade');
      node.textContent = items[index];
      index = (index + 1) % items.length;
    }
    if (!items.length) {
      node.className = 'tv-news-text idle';
      node.textContent = 'Nothing new right now.';
      return;
    }
    node.className = 'tv-news-text';
    show();
    if (items.length > 1 && !still) { newsTimer = setInterval(show, NEWS_ROTATE_MS); }
  }

  function renderQr(url) {
    if (!url || url === qrUrl || typeof QRCode === 'undefined') { return; }
    qrUrl = url;
    var holder = byId('tv-qr-code');
    clear(holder);
    new QRCode(holder, { text: url, width: 256, height: 256, correctLevel: QRCode.CorrectLevel.M });
  }

  // ---- Cast / start-up ----

  function startCast() {
    var query = /[?&]token=([^&]+)/.exec(location.search);
    if (query) { token = decodeURIComponent(query[1]); }
    if (window.cast && cast.framework) {
      var context = cast.framework.CastReceiverContext.getInstance();
      context.addCustomMessageListener(NAMESPACE, function (event) {
        var message = event.data;
        if (typeof message === 'string') { try { message = JSON.parse(message); } catch (e) { return; } }
        if (message.type === 'token' && message.token) { token = message.token; load(); }
        else if (message.type === 'reload') { load(); }
      });
      var options = new cast.framework.CastReceiverOptions();
      options.disableIdleTimeout = true;
      options.skipPlayersLoad = true;
      context.start(options);
    }
    load();
  }

  window.DmsTv = { render: apply };
  if (mode === 'tv') { startCast(); } else { load(); }
})();
