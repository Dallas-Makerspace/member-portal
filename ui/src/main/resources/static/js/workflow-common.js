/* Shared helpers for the workflow UI (launch button, run page, admin page). No polling: live data
 * comes from SSE streams relayed by /api/workflows/... */
(function () {
    'use strict';
    if (window.DmsWorkflows) return;

    var TZ = 'America/Chicago';
    var API = '/api/workflows';

    var RUN_STATUS = {
        PENDING: {label: 'Queued', cls: 'secondary'},
        RUNNING: {label: 'Running', cls: 'primary'},
        WAITING_RETRY: {label: 'Waiting to retry', cls: 'warning'},
        PAUSED: {label: 'Needs attention', cls: 'danger'},
        ROLLING_BACK: {label: 'Rolling back', cls: 'warning'},
        ROLLBACK_FAILED: {label: 'Rollback failed', cls: 'danger'},
        SUCCEEDED: {label: 'Succeeded', cls: 'success'},
        ROLLED_BACK: {label: 'Rolled back', cls: 'secondary'},
        ABANDONED: {label: 'Abandoned', cls: 'secondary'},
        CANCELLED: {label: 'Cancelled', cls: 'secondary'}
    };
    var TERMINAL = {SUCCEEDED: 1, ROLLED_BACK: 1, ABANDONED: 1, CANCELLED: 1};

    var STEP_STATUS = {
        PENDING: {label: 'Pending', cls: 'secondary', icon: 'radio_button_unchecked'},
        RUNNING: {label: 'Running', cls: 'primary', icon: 'progress_activity'},
        SUCCEEDED: {label: 'Succeeded', cls: 'success', icon: 'check_circle'},
        SKIPPED: {label: 'No change needed', cls: 'secondary', icon: 'remove_circle_outline'},
        FAILED: {label: 'Failed', cls: 'danger', icon: 'error'},
        ROLLING_BACK: {label: 'Rolling back', cls: 'warning', icon: 'undo'},
        ROLLED_BACK: {label: 'Rolled back', cls: 'secondary', icon: 'undo'},
        ROLLBACK_FAILED: {label: 'Rollback failed', cls: 'danger', icon: 'report'}
    };

    var REVERSIBILITY = {
        REVERSIBLE: {label: 'Reversible', cls: 'success'},
        BEST_EFFORT: {label: 'Best-effort undo', cls: 'warning'},
        IRREVERSIBLE: {label: 'Irreversible', cls: 'danger'}
    };

    var PLAN_STATUS = {
        WILL_RUN: {label: 'Will run', cls: 'primary'},
        NO_OP: {label: 'No change', cls: 'secondary'},
        BLOCKED: {label: 'Blocked', cls: 'danger'},
        WARNING: {label: 'Warning', cls: 'warning'},
        UNKNOWN: {label: 'Unknown yet', cls: 'light'}
    };

    function el(tag, attrs, children) {
        var node = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (k) {
            var v = attrs[k];
            if (v === null || v === undefined || v === false) return;
            if (k === 'class') node.className = v;
            else if (k === 'text') node.textContent = v;
            else if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.slice(2), v);
            else node.setAttribute(k, v === true ? '' : v);
        });
        (children || []).forEach(function (c) {
            if (c === null || c === undefined || c === false) return;
            node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
        });
        return node;
    }

    function badge(info, extra) {
        return el('span', {
            class: 'badge text-bg-' + info.cls + (extra ? ' ' + extra : ''),
            text: info.label
        });
    }

    function isTerminal(status) {
        return !!TERMINAL[status];
    }

    function fmtTime(iso) {
        if (!iso) return '';
        var d = new Date(iso);
        if (isNaN(d.getTime())) return iso;
        return new Intl.DateTimeFormat('en-US', {
            timeZone: TZ, month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit'
        }).format(d);
    }

    function fmtDuration(ms) {
        if (ms === null || ms === undefined || isNaN(ms) || ms < 0) return '';
        var s = Math.round(ms / 1000);
        if (s < 1) return '<1s';
        var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
        if (h) return h + 'h ' + m + 'm';
        if (m) return m + 'm ' + sec + 's';
        return sec + 's';
    }

    function fmtAge(seconds) {
        if (seconds === null || seconds === undefined) return '';
        return fmtDuration(seconds * 1000) || '0s';
    }

    function runUrl(id) {
        return '/workflows/runs/' + encodeURIComponent(id);
    }

    function subjectText(run) {
        return (run.subjects || []).map(function (s) {
            return s.type + ':' + s.key;
        }).join(', ');
    }

    /** JSON call against /api/workflows. Rejects with .sessionExpired when bounced to login. */
    function api(method, path, body) {
        var opts = {method: method, credentials: 'same-origin', headers: {'Accept': 'application/json'}};
        if (method !== 'GET') {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body || {});
        }
        return fetch(API + path, opts).then(function (resp) {
            var ct = resp.headers.get('content-type') || '';
            if (resp.redirected) {
                var e = new Error('Your session has expired. Reload the page to sign in again.');
                e.sessionExpired = true;
                throw e;
            }
            if (ct.indexOf('json') < 0) {
                throw new Error('Unexpected response from the server (' + resp.status + ').');
            }
            return resp.json().then(function (data) {
                return {status: resp.status, ok: resp.ok, data: data};
            });
        });
    }

    var definitionsPromise = null;

    /** Definitions the current user may use (cached per page). Resolves to [] when the feature is off. */
    function definitions() {
        if (!definitionsPromise) {
            definitionsPromise = api('GET', '/definitions').then(function (r) {
                return r.ok && Array.isArray(r.data) ? r.data : [];
            }).catch(function () {
                definitionsPromise = null;
                return [];
            });
        }
        return definitionsPromise;
    }

    var STATE_LABEL = {
        connecting: 'Connecting…',
        connected: 'Live',
        reconnecting: 'Reconnecting…',
        paused: 'Paused (tab hidden)',
        failed: 'Disconnected',
        ended: 'Signed out'
    };

    function indicator() {
        var dot = el('span', {class: 'wf-conn-dot', 'aria-hidden': 'true'});
        var text = el('span', {class: 'wf-conn-text'});
        var root = el('span', {class: 'wf-conn wf-conn-connecting', role: 'status', 'aria-live': 'polite'}, [dot, text]);
        text.textContent = STATE_LABEL.connecting;
        return {
            element: root,
            set: function (state) {
                root.className = 'wf-conn wf-conn-' + state;
                text.textContent = STATE_LABEL[state] || state;
            }
        };
    }

    /**
     * Opens an SSE stream with reconnect (resuming from the last event id), and closes it when the tab has
     * been hidden for a while. opts: onMessage(type, data), onState(state), onFatal(status, message).
     */
    function openStream(url, opts) {
        var es = null, lastId = null, attempt = 0, timer = null, hiddenTimer = null;
        var stopped = false, paused = false;
        var HIDDEN_MS = 60000;
        var TYPES = ['snapshot', 'run', 'event', 'admin'];

        function state(s) {
            if (opts.onState) opts.onState(s);
        }

        function parse(ev) {
            try {
                return JSON.parse(ev.data);
            } catch (e) {
                return null;
            }
        }

        function closeSource() {
            if (es) {
                es.close();
                es = null;
            }
        }

        function schedule() {
            if (stopped || paused) return;
            state('reconnecting');
            attempt++;
            var delay = Math.min(30000, 1000 * Math.pow(2, Math.min(attempt, 5)));
            timer = setTimeout(connect, delay);
        }

        function connect() {
            if (stopped || paused) return;
            clearTimeout(timer);
            closeSource();
            var sep = url.indexOf('?') >= 0 ? '&' : '?';
            var full = lastId ? url + sep + 'lastEventId=' + encodeURIComponent(lastId) : url;
            state(attempt ? 'reconnecting' : 'connecting');
            es = new EventSource(full);
            es.onopen = function () {
                attempt = 0;
                state('connected');
            };
            TYPES.forEach(function (t) {
                es.addEventListener(t, function (ev) {
                    if (ev.lastEventId) lastId = ev.lastEventId;
                    var data = parse(ev);
                    if (data) opts.onMessage(t, data);
                });
            });
            es.addEventListener('stream-error', function (ev) {
                var d = parse(ev) || {};
                closeSource();
                if (d.status >= 400 && d.status < 500) {
                    stopped = true;
                    state('failed');
                    if (opts.onFatal) opts.onFatal(d.status, 'The server refused the live connection (' + d.status + ').');
                } else {
                    schedule();
                }
            });
            es.addEventListener('bye', function () {
                closeSource();
                stopped = true;
                state('ended');
                if (opts.onFatal) opts.onFatal(401, 'Your session ended. Reload the page to sign in again.');
            });
            es.onerror = function () {
                if (!es) return;
                if (es.readyState === EventSource.CLOSED) {
                    closeSource();
                    schedule();
                } else {
                    state('reconnecting');
                }
            };
        }

        function onVisibility() {
            if (stopped) return;
            if (document.hidden) {
                clearTimeout(hiddenTimer);
                hiddenTimer = setTimeout(function () {
                    paused = true;
                    clearTimeout(timer);
                    closeSource();
                    state('paused');
                }, HIDDEN_MS);
            } else {
                clearTimeout(hiddenTimer);
                if (paused) {
                    paused = false;
                    attempt = 0;
                    connect();
                }
            }
        }

        document.addEventListener('visibilitychange', onVisibility);
        connect();
        return {
            close: function () {
                stopped = true;
                clearTimeout(timer);
                clearTimeout(hiddenTimer);
                document.removeEventListener('visibilitychange', onVisibility);
                closeSource();
            }
        };
    }

    /** Bootstrap confirm modal; resolves true when confirmed. Shared implementation lives in app.js. */
    function confirmDialog(opts) {
        return window.dmsConfirm(opts);
    }

    function notice(container, kind, message) {
        container.textContent = '';
        if (!message) return;
        container.appendChild(el('div', {class: 'alert alert-' + kind + ' mb-2', role: 'alert', text: message}));
    }

    window.DmsWorkflows = {
        el: el, badge: badge, api: api, definitions: definitions, openStream: openStream, indicator: indicator,
        confirmDialog: confirmDialog, notice: notice, isTerminal: isTerminal, fmtTime: fmtTime,
        fmtDuration: fmtDuration, fmtAge: fmtAge, runUrl: runUrl, subjectText: subjectText,
        RUN_STATUS: RUN_STATUS, STEP_STATUS: STEP_STATUS, REVERSIBILITY: REVERSIBILITY, PLAN_STATUS: PLAN_STATUS
    };
})();
