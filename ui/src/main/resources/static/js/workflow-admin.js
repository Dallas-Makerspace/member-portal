/* Admin page: workflows in progress and "safe to deploy" banner, live over /api/workflows/admin/stream. */
(function () {
    'use strict';
    var W = window.DmsWorkflows;
    var root = document.getElementById('wf-admin-root');
    if (!W || !root) return;
    var el = W.el;

    var banner = document.getElementById('wf-admin-banner');
    var tbody = document.getElementById('wf-admin-rows');
    var empty = document.getElementById('wf-admin-empty');
    var messageBox = document.getElementById('wf-admin-message');
    var conn = W.indicator();
    document.getElementById('wf-admin-conn').appendChild(conn.element);

    var IN_PROGRESS = {RUNNING: 1, WAITING_RETRY: 1, ROLLING_BACK: 1, PAUSED: 1, ROLLBACK_FAILED: 1, PENDING: 1};
    var BLOCKS_DEPLOY = {RUNNING: 1, ROLLING_BACK: 1};
    var runs = {};
    var generatedAt = null;
    var safeOverride = null;
    var defs = {};
    var scheduled = false;

    function schedule() {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(function () {
            scheduled = false;
            render();
        });
    }

    function count(pred) {
        return Object.keys(runs).filter(function (k) { return pred(runs[k]); }).length;
    }

    function safe() {
        return count(function (r) { return BLOCKS_DEPLOY[r.status]; }) === 0;
    }

    function renderBanner() {
        var blocking = count(function (r) { return BLOCKS_DEPLOY[r.status]; });
        var waiting = count(function (r) { return r.status === 'WAITING_RETRY'; });
        var attention = count(function (r) { return r.status === 'PAUSED' || r.status === 'ROLLBACK_FAILED'; });
        var isSafe = safeOverride === null ? safe() : safeOverride && safe();
        banner.className = 'alert mb-3 ' + (isSafe ? 'alert-success' : 'alert-warning');
        banner.textContent = '';
        banner.appendChild(el('div', {class: 'fw-bold fs-5', text: isSafe
            ? 'Safe to deploy'
            : blocking + (blocking === 1 ? ' workflow' : ' workflows') + ' in progress'}));
        var notes = [];
        if (!isSafe) notes.push('Wait for running workflows to finish before restarting or deploying.');
        if (waiting) notes.push(waiting + ' waiting to retry (survive a restart).');
        if (attention) notes.push(attention + ' paused and need attention (survive a restart).');
        if (isSafe && !notes.length) notes.push('No workflows are running or waiting.');
        banner.appendChild(el('div', {class: 'small', text: notes.join(' ')}));
    }

    function stepName(run) {
        var d = defs[run.workflowId];
        var n = run.stepCount || 0;
        var idx = Math.max(0, Math.min(run.currentStepIndex || 0, Math.max(n - 1, 0)));
        var step = d && d.steps && d.steps[idx];
        return n ? (idx + 1) + '/' + n + (step ? ' ' + step.displayName : '') : '';
    }

    function statusCell(run) {
        var info = W.RUN_STATUS[run.status] || {label: run.status, cls: 'secondary'};
        var parts = [W.badge(info)];
        if (run.status === 'WAITING_RETRY' && run.nextAttemptAt) {
            parts.push(el('div', {class: 'small text-muted', text: 'retry ' + W.fmtTime(run.nextAttemptAt)}));
        }
        return el('td', {}, parts);
    }

    function put(run) {
        run.rxAt = Date.now();
        runs[run.id] = run;
    }

    function leaseAge(run) {
        return run.leaseAgeSeconds + Math.floor((Date.now() - run.rxAt) / 1000);
    }

    function leaseStale(run, age) {
        return age > 120 && (run.status === "RUNNING" || run.status === "ROLLING_BACK");
    }

    function leaseCell(run) {
        if (run.leaseAgeSeconds === null || run.leaseAgeSeconds === undefined) return el("td", {class: "text-muted", text: "—"});
        var age = leaseAge(run);
        var stale = leaseStale(run, age);
        return el("td", {class: "wf-lease" + (stale ? " text-danger fw-bold" : ""), "data-run-id": run.id, text: W.fmtAge(age), title: stale ? "Heartbeat looks stale" : ""});
    }

    // Local clock only: advances the displayed lease age between server messages (no network).
    setInterval(function () {
        Array.prototype.forEach.call(tbody.querySelectorAll("td.wf-lease"), function (td) {
            var run = runs[td.getAttribute("data-run-id")];
            if (!run) return;
            var age = leaseAge(run);
            var stale = leaseStale(run, age);
            td.textContent = W.fmtAge(age);
            td.className = "wf-lease" + (stale ? " text-danger fw-bold" : "");
            td.title = stale ? "Heartbeat looks stale" : "";
        });
    }, 1000);

    function render() {
        renderBanner();
        var list = Object.keys(runs).map(function (k) { return runs[k]; }).sort(function (a, b) {
            return (Date.parse(a.createdAt) || 0) - (Date.parse(b.createdAt) || 0);
        });
        tbody.textContent = '';
        empty.hidden = list.length > 0;
        list.forEach(function (run) {
            var d = defs[run.workflowId];
            tbody.appendChild(el('tr', {class: run.status === 'PAUSED' || run.status === 'ROLLBACK_FAILED' ? 'table-danger' : ''}, [
                el('td', {}, [el('div', {class: 'fw-semibold', text: (d && d.displayName) || run.displayName}),
                    el('div', {class: 'small text-muted', text: '#' + run.id})]),
                el('td', {text: W.subjectText(run)}),
                el('td', {text: run.actor}),
                statusCell(run),
                el('td', {text: stepName(run)}),
                el('td', {text: W.fmtTime(run.startedAt || run.createdAt)}),
                el('td', {class: 'font-monospace small', text: run.lockedBy || '—'}),
                leaseCell(run),
                el('td', {class: 'text-end'}, [el('a', {class: 'btn btn-sm btn-outline-primary', href: W.runUrl(run.id), text: 'Details'})])
            ]));
        });
        document.getElementById('wf-admin-generated').textContent = generatedAt ? 'Updated ' + W.fmtTime(generatedAt) : '';
    }

    function applyAdmin(admin) {
        runs = {};
        (admin.runs || []).forEach(function (r) { put(r); });
        generatedAt = admin.generatedAt;
        safeOverride = admin.safeToDeploy;
    }

    W.definitions().then(function (list) {
        list.forEach(function (d) { defs[d.id] = d; });
        schedule();
    });

    W.openStream('/api/workflows/admin/stream', {
        onState: conn.set,
        onFatal: function (status, message) { W.notice(messageBox, 'danger', message); },
        onMessage: function (type, msg) {
            if (msg.admin) {
                applyAdmin(msg.admin);
            } else if (type === 'snapshot' && msg.runs) {
                runs = {};
                msg.runs.filter(function (r) { return IN_PROGRESS[r.status]; }).forEach(put);
                safeOverride = null;
            } else if (type === 'run' && msg.run) {
                if (IN_PROGRESS[msg.run.status]) put(msg.run); else delete runs[msg.run.id];
                safeOverride = null;
            } else {
                return;
            }
            schedule();
        }
    });
})();
