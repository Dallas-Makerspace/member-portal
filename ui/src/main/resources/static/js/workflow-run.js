/* Run detail page: step timeline, operator actions and event log, live over the single-run SSE stream. */
(function () {
    'use strict';
    var W = window.DmsWorkflows;
    var root = document.getElementById('wf-run-root');
    if (!W || !root) return;
    var el = W.el;

    var runId = root.dataset.runId;
    var canOperate = root.dataset.canOperate === 'true';
    var run = null;
    var def = null;
    var events = {};
    var expanded = {};
    var pending = false;
    var rendering = false;

    var header = document.getElementById('wf-run-header');
    var conn = W.indicator();
    document.getElementById('wf-run-conn').appendChild(conn.element);
    var messageBox = document.getElementById('wf-run-message');
    var actionsBox = document.getElementById('wf-run-actions');
    var timelineBox = document.getElementById('wf-run-timeline');
    var logBox = document.getElementById('wf-run-log');

    function schedule() {
        if (rendering) return;
        rendering = true;
        requestAnimationFrame(function () {
            rendering = false;
            render();
        });
    }

    function stepDef(stepId) {
        return def && (def.steps || []).filter(function (s) { return s.id === stepId; })[0];
    }

    function sortedEvents() {
        return Object.keys(events).map(function (k) { return events[k]; }).sort(function (a, b) { return a.id - b.id; });
    }

    function stepEvents(index) {
        return sortedEvents().filter(function (e) { return e.stepIndex === index; });
    }

    function row(label, value) {
        return el('div', {class: 'col-sm-6 col-lg-3 mb-2'}, [
            el('div', {class: 'small text-muted', text: label}),
            el('div', {text: value || '—'})
        ]);
    }

    function renderHeader() {
        header.textContent = '';
        var info = W.RUN_STATUS[run.status] || {label: run.status, cls: 'secondary'};
        header.appendChild(el('div', {class: 'd-flex flex-wrap align-items-center gap-2 mb-3'}, [
            W.badge(info, 'fs-6'),
            run.status === 'WAITING_RETRY' && run.nextAttemptAt
                ? el('span', {class: 'text-muted', text: 'Next attempt ' + W.fmtTime(run.nextAttemptAt)}) : null,
            el('span', {class: 'text-muted small', text: run.workflowId + ' v' + run.workflowVersion})
        ]));
        header.appendChild(el('div', {class: 'row'}, [
            row('Subject', W.subjectText(run)),
            row('Started by', run.actor + (run.triggeredBy ? ' (' + run.triggeredBy + ')' : '')),
            row('Started', W.fmtTime(run.startedAt || run.createdAt)),
            row(W.isTerminal(run.status) ? 'Finished' : 'Progress',
                W.isTerminal(run.status) ? W.fmtTime(run.finishedAt)
                    : 'step ' + Math.min((run.currentStepIndex || 0) + 1, run.stepCount) + ' of ' + run.stepCount)
        ]));
        if (run.errorSummary) {
            header.appendChild(el('div', {class: 'alert alert-danger mb-0', role: 'alert', text: run.errorSummary}));
        }
    }

    function actionsFor() {
        var list = [];
        var step = (run.steps || [])[run.currentStepIndex];
        var sd = step && stepDef(step.stepId);
        if (run.status === 'PAUSED') {
            list.push('retry');
            if (sd && sd.skippable) list.push('skip');
            list.push('rollback', 'abandon');
        } else if (run.status === 'ROLLBACK_FAILED') {
            list.push('retry', 'abandon');
        } else if (run.status === 'PENDING' || run.status === 'RUNNING' || run.status === 'WAITING_RETRY') {
            list.push('cancel');
        }
        return list;
    }

    var ACTION_UI = {
        retry: {label: 'Retry', cls: 'btn-primary', title: 'Retry', confirm: 'Retry'},
        skip: {label: 'Skip step', cls: 'btn-outline-secondary', title: 'Skip this step', confirm: 'Skip step'},
        rollback: {label: 'Roll back', cls: 'btn-outline-warning', title: 'Roll back this run', confirm: 'Roll back', danger: true},
        abandon: {label: 'Abandon', cls: 'btn-outline-danger', title: 'Abandon this run', confirm: 'Abandon', danger: true},
        cancel: {label: 'Cancel', cls: 'btn-outline-danger', title: 'Cancel this run', confirm: 'Cancel run', danger: true}
    };

    function actionMessage(action) {
        var step = (run.steps || [])[run.currentStepIndex];
        var name = step ? '“' + step.displayName + '”' : 'the current step';
        switch (action) {
            case 'retry':
                return {message: 'Run ' + name + ' again now.'};
            case 'skip':
                return {message: 'Mark ' + name + ' as skipped and continue with the next step.',
                    details: ['Only skip a step if its effect is not needed or was done by hand.']};
            case 'rollback':
                var risky = (run.steps || []).filter(function (s) {
                    return s.reversibility !== 'REVERSIBLE' && (s.status === 'SUCCEEDED' || s.status === 'FAILED' || s.status === 'RUNNING');
                }).map(function (s) {
                    return s.displayName + ' (' + (W.REVERSIBILITY[s.reversibility] || {label: s.reversibility}).label.toLowerCase() + ')';
                });
                return {message: 'Undo the steps that already ran, newest first.',
                    details: risky.length ? ['These steps cannot be fully undone: ' + risky.join(', ') + '.'] : []};
            case 'abandon':
                return {message: 'Stop this run and leave everything exactly as it is now. The item is unlocked.',
                    details: ['This is recorded in the audit trail and cannot be resumed.']};
            default:
                return {message: 'Stop after the current step finishes.',
                    details: ['Steps that already completed are not undone.']};
        }
    }

    function perform(action) {
        var ui = ACTION_UI[action];
        var m = actionMessage(action);
        W.confirmDialog({title: ui.title, message: m.message, details: m.details, confirmLabel: ui.confirm, danger: ui.danger}).then(function (ok) {
            if (!ok) return;
            pending = true;
            render();
            W.notice(messageBox, 'info', ui.label + '…');
            W.api('POST', '/runs/' + encodeURIComponent(runId) + '/' + action, {}).then(function (r) {
                pending = false;
                if (r.ok && r.data && r.data.id) {
                    var steps = r.data.steps && r.data.steps.length ? r.data.steps : run.steps;
                    run = Object.assign({}, run, r.data, {steps: steps});
                    W.notice(messageBox, 'success', ui.label + ' requested.');
                    setTimeout(function () { W.notice(messageBox, 'info', ''); }, 4000);
                } else {
                    W.notice(messageBox, 'danger', (r.data && r.data.message) || 'That action was not accepted.');
                }
                render();
            }).catch(function (e) {
                pending = false;
                W.notice(messageBox, 'danger', e.message);
                render();
            });
        });
    }

    function renderActions() {
        actionsBox.textContent = '';
        if (!canOperate) return;
        actionsFor().forEach(function (a) {
            var ui = ACTION_UI[a];
            actionsBox.appendChild(el('button', {
                type: 'button', class: 'btn btn-sm ' + ui.cls + ' me-2 mb-2', text: ui.label,
                disabled: pending, onclick: function () { perform(a); }
            }));
        });
    }

    function stepDuration(s) {
        if (!s.startedAt || !s.finishedAt) return '';
        return W.fmtDuration(Date.parse(s.finishedAt) - Date.parse(s.startedAt));
    }

    function logList(list) {
        var box = el('div', {class: 'wf-log'});
        if (!list.length) box.appendChild(el('div', {class: 'text-muted small', text: 'No log entries yet.'}));
        list.forEach(function (e) {
            var lvl = {INFO: 'secondary', WARN: 'warning', ERROR: 'danger'}[e.level] || 'secondary';
            box.appendChild(el('div', {class: 'wf-log-line small'}, [
                el('span', {class: 'text-muted me-2', text: W.fmtTime(e.timestamp)}),
                e.level === 'INFO' ? null : el('span', {class: 'badge text-bg-' + lvl + ' me-2', text: e.level}),
                el('span', {text: e.message}),
                e.actor ? el('span', {class: 'text-muted ms-2', text: '— ' + e.actor}) : null
            ]));
        });
        return box;
    }

    function details(key, summaryText, content, open) {
        var d = el('details', {class: 'wf-details', open: open || expanded[key]}, [
            el('summary', {class: 'small', text: summaryText}), content
        ]);
        d.addEventListener('toggle', function () { expanded[key] = d.open; });
        return d;
    }

    function renderTimeline() {
        timelineBox.textContent = '';
        var list = el('ol', {class: 'wf-timeline list-unstyled mb-0'});
        (run.steps || []).forEach(function (s) {
            var info = W.STEP_STATUS[s.status] || {label: s.status, cls: 'secondary', icon: 'help'};
            var rev = W.REVERSIBILITY[s.reversibility];
            var current = !W.isTerminal(run.status) && s.index === run.currentStepIndex;
            var spin = s.status === 'RUNNING' || s.status === 'ROLLING_BACK';
            var evs = stepEvents(s.index);
            var body = el('div', {class: 'wf-step-body'}, [
                el('div', {class: 'd-flex flex-wrap align-items-center gap-2'}, [
                    el('strong', {text: s.displayName}),
                    W.badge(info),
                    rev ? el('span', {class: 'badge border text-' + rev.cls, text: rev.label}) : null,
                    s.attempts > 0 ? el('span', {class: 'small text-muted', text: s.attempts === 1 ? '1 attempt' : s.attempts + ' attempts'}) : null,
                    stepDuration(s) ? el('span', {class: 'small text-muted', text: stepDuration(s)}) : null
                ]),
                s.outputSummary ? el('div', {class: 'small text-muted mt-1', text: s.outputSummary}) : null,
                s.error ? el('pre', {class: 'wf-error small mt-2 mb-1', text: s.error}) : null,
                evs.length ? details('step-' + s.index, 'Log (' + evs.length + ')', logList(evs)) : null
            ]);
            list.appendChild(el('li', {class: 'wf-step wf-step-' + s.status.toLowerCase() + (current ? ' wf-step-current' : '')}, [
                el('span', {class: 'wf-step-icon text-' + info.cls, 'aria-hidden': 'true'}, [
                    el('span', {class: 'material-symbols-outlined' + (spin ? ' wf-spin' : ''), text: info.icon})
                ]),
                body
            ]));
        });
        timelineBox.appendChild(list);
    }

    function renderLog() {
        logBox.textContent = '';
        var all = sortedEvents().map(function (e) {
            var s = e.stepIndex !== null && e.stepIndex !== undefined && (run.steps || [])[e.stepIndex];
            return Object.assign({}, e, {message: (s ? '[' + s.displayName + '] ' : '') + e.message});
        });
        logBox.appendChild(details('all', 'Activity log (' + all.length + ')', logList(all), true));
    }

    function render() {
        if (!run) return;
        renderHeader();
        renderActions();
        renderTimeline();
        renderLog();
    }

    function addEvents(list) {
        (list || []).forEach(function (e) { events[e.id] = e; });
        schedule();
    }

    function ensureDef() {
        if (def || !run) return;
        W.definitions().then(function (defs) {
            def = defs.filter(function (d) { return d.id === run.workflowId; })[0] || null;
            schedule();
        });
    }

    W.openStream('/api/workflows/runs/' + encodeURIComponent(runId) + '/stream', {
        onState: conn.set,
        onFatal: function (status, message) { W.notice(messageBox, 'danger', message); },
        onMessage: function (type, msg) {
            if ((type === 'snapshot' || type === 'run') && msg.run) {
                run = msg.run;
                ensureDef();
            } else if (type === 'event' && msg.event) {
                events[msg.event.id] = msg.event;
            } else {
                return;
            }
            schedule();
        }
    });

    W.api('GET', '/runs/' + encodeURIComponent(runId) + '/events?after=0').then(function (r) {
        if (r.ok && Array.isArray(r.data)) addEvents(r.data);
    }).catch(function () { /* the stream still delivers new events */ });

    W.api('GET', '/runs/' + encodeURIComponent(runId)).then(function (r) {
        if (r.ok && !run) {
            run = r.data;
            ensureDef();
            schedule();
        }
    }).catch(function () { /* the stream snapshot is authoritative */ });
})();
