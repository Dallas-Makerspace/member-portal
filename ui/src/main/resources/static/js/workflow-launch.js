/* Workflow launch button: live status from the subject-scoped SSE stream, and a preflight/confirm
 * dialog. Markup: <span class="wf-launch" data-workflow-id="..." data-subject="type:key"
 * data-input='{"fieldName":"x"}' data-label="Start" data-btn-class="btn-outline-danger">.
 * Hidden unless the user may use that workflow and the feature is on. */
(function () {
    'use strict';
    var W = window.DmsWorkflows;
    var F = window.DmsWorkflowFields;
    if (!W || !F || window.DmsWorkflowLaunch) return;
    var el = W.el;

    var modalEl = null;
    var modal = null;

    function newToken() {
        if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
        return 'wf-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
    }

    function parseJson(text) {
        try {
            var v = JSON.parse(text || '{}');
            return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
        } catch (e) {
            return {};
        }
    }

    function stepText(run, def) {
        var n = run.stepCount || 0;
        if (!n) return '';
        var idx = Math.max(0, Math.min(run.currentStepIndex || 0, n - 1));
        var step = def && def.steps && def.steps[idx];
        return 'step ' + (idx + 1) + ' of ' + n + (step ? ': ' + step.displayName : '');
    }

    function ensureModal() {
        if (modalEl) return;
        modalEl = el('div', {class: 'modal fade', id: 'wfLaunchModal', tabindex: '-1', 'aria-labelledby': 'wfLaunchTitle', 'aria-hidden': 'true'}, [
            el('div', {class: 'modal-dialog modal-lg modal-dialog-scrollable'}, [
                el('div', {class: 'modal-content'}, [
                    el('div', {class: 'modal-header'}, [
                        el('h5', {class: 'modal-title', id: 'wfLaunchTitle'}),
                        el('button', {type: 'button', class: 'btn-close', 'data-bs-dismiss': 'modal', 'aria-label': 'Close'})
                    ]),
                    el('div', {class: 'modal-body'}),
                    el('div', {class: 'modal-footer'}, [
                        el('button', {type: 'button', class: 'btn btn-outline-secondary', 'data-bs-dismiss': 'modal', text: 'Cancel'}),
                        el('button', {type: 'button', class: 'btn btn-outline-secondary d-none', 'data-role': 'back', text: 'Back'}),
                        el('button', {type: 'button', class: 'btn btn-primary d-none', 'data-role': 'primary'})
                    ])
                ])
            ])
        ]);
        document.body.appendChild(modalEl);
        modal = new bootstrap.Modal(modalEl);
    }

    function openDialog(cfg, def, onStarted) {
        ensureModal();
        var title = modalEl.querySelector('.modal-title');
        var body = modalEl.querySelector('.modal-body');
        var backBtn = modalEl.querySelector('[data-role=back]');
        var primary = modalEl.querySelector('[data-role=primary]');
        // Replace buttons to drop listeners from a previous open.
        [backBtn, primary].forEach(function (b) {
            var c = b.cloneNode(true);
            b.parentNode.replaceChild(c, b);
        });
        backBtn = modalEl.querySelector('[data-role=back]');
        primary = modalEl.querySelector('[data-role=primary]');

        var fields = def.inputFields || [];
        var input = Object.assign({}, cfg.input);
        var needForm = fields.some(function (f) {
            return input[f.name] === undefined || input[f.name] === null || String(input[f.name]).trim() === '';
        });
        var token = null;
        var typedConfirm = '';

        title.textContent = def.displayName;

        function setPrimary(label, cls, enabled, handler) {
            primary.className = 'btn btn-' + cls;
            primary.textContent = label;
            primary.disabled = !enabled;
            primary.onclick = handler;
        }

        function showBack(handler) {
            if (!handler) {
                backBtn.classList.add('d-none');
                return;
            }
            backBtn.classList.remove('d-none');
            backBtn.onclick = handler;
        }

        function showForm(error) {
            token = null;
            typedConfirm = '';
            body.textContent = '';
            if (def.description) body.appendChild(el('p', {class: 'text-muted', text: def.description}));
            var form = el('form', {novalidate: ''});
            var inputs = {};
            var touched = {};
            var errorBoxes = {};
            var counters = {};
            fields.forEach(function (f, i) {
                var id = 'wf-field-' + i;
                // Values the launcher prefilled are shown but not editable.
                var locked = cfg.input[f.name] != null && String(cfg.input[f.name]).trim() !== '';
                var attrs = {class: 'form-control', id: id, name: f.name, required: f.required !== false,
                    autocomplete: 'off', readonly: locked};
                var control = f.multiline
                    ? el('textarea', Object.assign({rows: 4}, attrs))
                    : el('input', Object.assign({type: 'text'}, attrs));
                control.value = input[f.name] == null ? '' : String(input[f.name]);
                inputs[f.name] = control;
                var counter = el('small', {class: 'text-muted text-nowrap wf-counter'});
                var fieldErr = el('div', {class: 'invalid-feedback d-block small'});
                counters[f.name] = counter;
                errorBoxes[f.name] = fieldErr;
                var hint = f.hint ? el('div', {class: 'form-text mt-1', id: id + '-hint', text: f.hint}) : el('span');
                if (f.hint) control.setAttribute('aria-describedby', id + '-hint');
                control.addEventListener('input', function () {
                    touched[f.name] = true;
                    refresh();
                });
                form.appendChild(el('div', {class: 'mb-3'}, [
                    el('label', {class: 'form-label', for: id, text: f.label + (f.required !== false ? '' : ' (optional)')}),
                    control,
                    el('div', {class: 'd-flex justify-content-between gap-2'}, [hint, counter]),
                    fieldErr
                ]));
            });
            var errorBox = el('div');
            form.appendChild(errorBox);
            if (error) W.notice(errorBox, 'danger', error);
            body.appendChild(form);
            showBack(null);

            function refresh() {
                var valid = true;
                fields.forEach(function (f) {
                    var value = inputs[f.name].value;
                    var err = F.fieldError(f, value);
                    if (err) valid = false;
                    var show = !!err && !!touched[f.name];
                    errorBoxes[f.name].textContent = show ? err : '';
                    inputs[f.name].classList.toggle('is-invalid', show);
                    counters[f.name].textContent = F.counterText(f, value);
                    counters[f.name].classList.toggle('text-danger', show);
                    counters[f.name].classList.toggle('text-muted', !show);
                });
                primary.disabled = !valid;
                return valid;
            }

            function submit(ev) {
                if (ev) ev.preventDefault();
                fields.forEach(function (f) {
                    touched[f.name] = true;
                });
                if (!refresh()) return;
                fields.forEach(function (f) {
                    var v = inputs[f.name].value.trim();
                    if (v) input[f.name] = v; else delete input[f.name];
                });
                preflight();
            }
            form.addEventListener('submit', submit);
            setPrimary('Preview plan', 'primary', true, submit);
            refresh();
            var first = form.querySelector('input:not([readonly]), textarea:not([readonly])');
            if (first) setTimeout(function () { first.focus(); }, 300);
        }

        function preflight() {
            body.textContent = '';
            body.appendChild(el('div', {class: 'text-center py-4'}, [
                el('div', {class: 'spinner-border text-primary', role: 'status'}, [el('span', {class: 'visually-hidden', text: 'Checking'})]),
                el('p', {class: 'text-muted mt-2 mb-0', text: 'Checking what this would do…'})
            ]));
            showBack(needForm ? function () { showForm(); } : null);
            setPrimary('Start', 'primary', false, null);
            W.api('POST', '/' + encodeURIComponent(def.id) + '/preflight', {input: input}).then(function (r) {
                if (r.ok) renderPlan(r.data);
                else showError(F.errorText(r.status, r.data, 'Could not preview this workflow.'));
            }).catch(function (e) {
                showError(e.message);
            });
        }

        function showError(message) {
            body.textContent = '';
            W.notice(body, 'danger', message);
            setPrimary('Start', 'primary', false, null);
            showBack(needForm ? function () { showForm(); } : null);
        }

        function reversibilityOf(stepId) {
            var s = (def.steps || []).filter(function (x) {
                return x.id === stepId;
            })[0];
            return s ? s.reversibility : null;
        }

        function renderPlan(plan, startError) {
            token = newToken();
            body.textContent = '';
            if (startError) body.appendChild(el('div', {class: 'alert alert-danger', role: 'alert', text: startError}));
            if (plan.activeRun) {
                body.appendChild(el('div', {class: 'alert alert-warning', role: 'alert'}, [
                    'Another run is already active for this item. ',
                    el('a', {href: W.runUrl(plan.activeRun.id), text: 'Open run #' + plan.activeRun.id})
                ]));
            }
            var blocked = F.blockedItems(plan);
            if (!plan.canStart || blocked.length) {
                var blockBox = el('div', {class: 'alert alert-danger wf-blocked', role: 'alert'}, [
                    el('strong', {text: plan.canStart ? 'Attention' : 'This cannot start yet.'})
                ]);
                blocked.forEach(function (b) {
                    var item = el('div', {class: 'mt-2'}, [el('div', {class: 'fw-semibold', text: b.step})]);
                    b.messages.forEach(function (m) {
                        item.appendChild(el('div', {class: 'small', text: m}));
                    });
                    blockBox.appendChild(item);
                });
                body.appendChild(blockBox);
            }
            var inputKeys = Object.keys(input);
            if (inputKeys.length) {
                body.appendChild(el('dl', {class: 'row small mb-3'}, inputKeys.reduce(function (acc, k) {
                    var field = fields.filter(function (f) { return f.name === k; })[0];
                    acc.push(el('dt', {class: 'col-sm-3', text: field ? field.label : k}));
                    acc.push(el('dd', {class: 'col-sm-9 text-break wf-prewrap', text: String(input[k])}));
                    return acc;
                }, [])));
            }
            var list = el('ol', {class: 'wf-plan list-unstyled'});
            var willRun = 0, noop = 0;
            (plan.steps || []).forEach(function (s) {
                if (s.status === 'WILL_RUN') willRun++;
                if (s.status === 'NO_OP') noop++;
                var info = W.PLAN_STATUS[s.status] || {label: s.status, cls: 'secondary'};
                var rev = reversibilityOf(s.stepId);
                var head = el('div', {class: 'd-flex flex-wrap align-items-center gap-2'}, [
                    W.badge(info),
                    el('strong', {text: s.displayName}),
                    rev && rev !== 'REVERSIBLE' ? W.badge(W.REVERSIBILITY[rev], 'ms-1') : null
                ]);
                var item = el('li', {class: 'wf-plan-step wf-plan-' + s.status.toLowerCase()}, [head]);
                (s.messages || []).forEach(function (m) {
                    item.appendChild(el('div', {class: 'small text-muted ms-1 mt-1', text: m}));
                });
                list.appendChild(item);
            });
            body.appendChild(list);
            (plan.notes || []).forEach(function (n) {
                body.appendChild(el('div', {
                    class: 'alert ' + (def.confirmTypingField ? 'alert-warning' : 'alert-info') + ' small py-2 mb-2',
                    text: n
                }));
            });
            body.appendChild(el('p', {class: 'small text-muted mb-0', text:
                plan.canStart
                    ? willRun + ' step(s) will run' + (noop ? ', ' + noop + ' need no change' : '') + '.'
                    : 'This cannot start until the blocked items are resolved.'}));
            showBack(needForm ? function () { showForm(); } : null);
            var startable = !!plan.canStart && !plan.activeRun;
            var confirmField = def.confirmTypingField;
            var expected = confirmField ? input[confirmField] : null;
            function confirmed() {
                return !confirmField || F.confirmMatches(typedConfirm, expected);
            }
            if (confirmField) {
                var field = fields.filter(function (f) { return f.name === confirmField; })[0];
                var box = el('input', {type: 'text', class: 'form-control', id: 'wf-confirm-typing',
                    autocomplete: 'off', spellcheck: 'false', disabled: !startable, 'data-role': 'confirm-typing'});
                box.value = typedConfirm;
                box.addEventListener('input', function () {
                    typedConfirm = box.value;
                    primary.disabled = !(startable && confirmed());
                });
                body.appendChild(el('div', {class: 'mt-3'}, [
                    el('label', {class: 'form-label', for: 'wf-confirm-typing'}, [
                        'Type the ' + (field ? field.label.toLowerCase() : 'value') + ' ',
                        el('code', {class: 'user-select-all text-break', text: String(expected)}),
                        ' to confirm'
                    ]),
                    box
                ]));
            }
            setPrimary(confirmField ? cfg.label : 'Start ' + def.displayName.toLowerCase(),
                confirmField ? 'danger' : 'primary', startable && confirmed(), function () {
                    if (!confirmed()) return;
                    start(plan);
                });
        }

        function start(plan) {
            primary.disabled = true;
            primary.textContent = 'Starting…';
            W.api('POST', '/' + encodeURIComponent(def.id) + '/runs', {input: input, idempotencyToken: token}).then(function (r) {
                if (r.ok && r.data && r.data.run) {
                    modal.hide();
                    onStarted(r.data.run);
                } else if (r.status === 422 && r.data && r.data.steps) {
                    renderPlan(r.data);
                } else if (r.status === 409 && r.data && r.data.activeRun) {
                    plan.activeRun = r.data.activeRun;
                    renderPlan(plan, r.data.message);
                } else {
                    renderPlan(plan, F.errorText(r.status, r.data, 'Could not start the workflow.'));
                }
            }).catch(function (e) {
                renderPlan(plan, e.message);
            });
        }

        if (needForm) showForm(); else preflight();
        modal.show();
    }

    function mount(root, options) {
        if (root.__wfMounted) return;
        root.__wfMounted = true;
        var d = root.dataset || {};
        var cfg = Object.assign({
            workflowId: d.workflowId,
            subject: d.subject || '',
            label: d.label || 'Start',
            btnClass: d.btnClass || 'btn-primary',
            input: parseJson(d.input),
            recentMinutes: parseInt(d.recentMinutes || '60', 10),
            openOnStart: d.openOnStart === 'true'
        }, options || {});
        if (!cfg.workflowId) return;

        root.hidden = true;
        root.classList.add('wf-launch');
        var idle = el('button', {type: 'button', class: 'btn ' + cfg.btnClass, text: cfg.label});
        var active = el('a', {class: 'btn wf-launch-active'});
        var done = el('span', {class: 'wf-launch-done'});
        var conn = W.indicator();
        conn.element.classList.add('wf-conn-compact');
        [idle, active, done, conn.element].forEach(function (n) {
            root.appendChild(n);
        });

        var def = null;
        var runs = {};

        function matches(run) {
            if (run.workflowId !== cfg.workflowId) return false;
            if (!cfg.subject) return true;
            var want = cfg.subject.toLowerCase();
            return (run.subjects || []).some(function (s) {
                return (s.type + ':' + s.key).toLowerCase() === want;
            });
        }

        function ts(run) {
            return Date.parse(run.createdAt) || 0;
        }

        function render() {
            var list = Object.keys(runs).map(function (k) { return runs[k]; }).sort(function (a, b) { return ts(b) - ts(a); });
            var current = list.filter(function (r) { return !W.isTerminal(r.status); })[0];
            var recent = list.filter(function (r) {
                return r.status === 'SUCCEEDED' && r.finishedAt &&
                    Date.now() - Date.parse(r.finishedAt) < cfg.recentMinutes * 60000;
            })[0];
            idle.hidden = active.hidden = done.hidden = true;
            if (current) {
                var info = W.RUN_STATUS[current.status] || {label: current.status, cls: 'secondary'};
                var running = current.status === 'RUNNING' || current.status === 'PENDING' || current.status === 'ROLLING_BACK';
                var cls = {primary: 'btn-primary', warning: 'btn-warning', danger: 'btn-danger', secondary: 'btn-secondary'}[info.cls] || 'btn-secondary';
                active.className = 'btn wf-launch-active ' + cls;
                active.href = W.runUrl(current.id);
                active.textContent = '';
                if (running) active.appendChild(el('span', {class: 'spinner-border spinner-border-sm me-2', 'aria-hidden': 'true'}));
                active.appendChild(document.createTextNode(info.label + (stepText(current, def) ? ' — ' + stepText(current, def) : '')));
                active.hidden = false;
            } else if (recent) {
                done.textContent = '';
                done.appendChild(el('a', {class: 'btn btn-outline-success btn-sm', href: W.runUrl(recent.id),
                    text: '✓ Completed ' + W.fmtTime(recent.finishedAt) + ' — view run'}));
                done.appendChild(el('button', {type: 'button', class: 'btn btn-link btn-sm', text: 'Run again', onclick: open}));
                done.hidden = false;
            } else {
                idle.hidden = false;
            }
        }

        function open() {
            openDialog(cfg, def, function (run) {
                runs[run.id] = run;
                render();
                if (cfg.openOnStart) window.location.href = W.runUrl(run.id);
            });
        }
        idle.addEventListener('click', open);

        W.definitions().then(function (defs) {
            def = defs.filter(function (x) { return x.id === cfg.workflowId; })[0];
            if (!def) return;
            root.hidden = false;
            render();
            var url = '/api/workflows/stream?workflow=' + encodeURIComponent(cfg.workflowId) +
                (cfg.subject ? '&subject=' + encodeURIComponent(cfg.subject) : '');
            W.openStream(url, {
                onState: conn.set,
                onFatal: function () { conn.set('failed'); },
                onMessage: function (type, msg) {
                    if (type === 'snapshot') {
                        runs = {};
                        (msg.runs || []).filter(matches).forEach(function (r) { runs[r.id] = r; });
                    } else if (type === 'run' && msg.run && matches(msg.run)) {
                        runs[msg.run.id] = msg.run;
                    } else {
                        return;
                    }
                    render();
                }
            });
        });
    }

    function mountAll() {
        Array.prototype.forEach.call(document.querySelectorAll('.wf-launch[data-workflow-id]'), function (n) {
            mount(n);
        });
    }

    window.DmsWorkflowLaunch = {mount: mount, mountAll: mountAll};
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountAll);
    else mountAll();
})();
