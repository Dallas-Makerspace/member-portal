// Autosave for forms marked [data-autosave]: each change is posted on its own, so there is no Save
// button to forget. Options on the form:
//   data-autosave-scope="<selector>"   post only the changed control's closest match (e.g. "tr"),
//                                      plus hidden inputs outside any match; default: the whole form
//   data-autosave-message="<text>"     toast text after a save (default "Saved")
//   data-autosave-undo                 offer Undo in the toast (back to the previously saved choice)
//   data-autosave-refresh-attention    refresh the account-menu attention dot after a save
// A [data-autosave-status] element (inside the scope, else the form) shows Saving / Saved / errors;
// a toast backs it up only when that element is off screen. Radio changes wait briefly so arrowing
// through a group saves once. Saves run one at a time, per scope in order. A failed save puts that
// scope back to what the server last accepted. Links marked [data-autosave-wait] hold until pending
// saves finish, and leaving while a save is pending asks for confirmation.
(function () {
  'use strict';

  const SAVED_VISIBLE_MS = 3000;
  const RADIO_DEBOUNCE_MS = 500;
  const GENERIC_FAILURE = 'Couldn’t save. Check your connection and try again.';
  const states = new Map();
  let restoring = false;

  function stateFor(form) {
    if (!states.has(form)) {
      states.set(form, { running: false, queue: new Map(), timers: new Map(), snapshots: new Map() });
    }
    return states.get(form);
  }

  function scopeOf(form, control) {
    const sel = form.dataset.autosaveScope;
    return (sel && control.closest(sel)) || form;
  }

  function controls(scope) {
    return Array.from(scope.querySelectorAll('input, select, textarea')).filter(function (el) {
      return el.name && el.type !== 'hidden' && !el.disabled;
    });
  }

  function snapshot(scope) {
    return controls(scope).map(function (el) { return { el: el, checked: el.checked, value: el.value }; });
  }

  // Puts the controls back; a radio group's focus follows its checked option.
  function restore(snap) {
    const active = document.activeElement;
    restoring = true;
    snap.forEach(function (s) {
      if (s.el.type === 'checkbox' || s.el.type === 'radio') s.el.checked = s.checked;
      else s.el.value = s.value;
      s.el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    restoring = false;
    if (active && active.type === 'radio') {
      const now = snap.find(function (s) { return s.checked && s.el.name === active.name; });
      if (now) now.el.focus();
    }
  }

  function payload(form, scope) {
    const sel = form.dataset.autosaveScope;
    const params = new URLSearchParams();
    // Hidden fields of this scope, plus those outside every scope (e.g. the form name).
    form.querySelectorAll('input[type=hidden]').forEach(function (el) {
      if (el.name && (scope.contains(el) || !(sel && el.closest(sel)))) params.append(el.name, el.value);
    });
    controls(scope).forEach(function (el) {
      if ((el.type === 'checkbox' || el.type === 'radio') && !el.checked) return;
      params.append(el.name, el.value);
    });
    return params;
  }

  function statusEl(form, scope) {
    return scope.querySelector('[data-autosave-status]') || form.querySelector('[data-autosave-status]');
  }

  function inView(el) {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.top >= 0 && r.bottom <= window.innerHeight;
  }

  function setStatus(el, text, isError) {
    if (!el) return;
    el.textContent = text;
    el.classList.toggle('text-danger', !!isError);
    if (el._clear) clearTimeout(el._clear);
    if (text && !isError && text !== 'Saving…') {
      el._clear = setTimeout(function () { el.textContent = ''; }, SAVED_VISIBLE_MS);
    }
  }

  // A failure the server (or the expired session) explained; anything else is a network error.
  function SaveError(message) { this.message = message; }

  function failureMessage(response) {
    if (response.type === 'opaqueredirect') {
      return Promise.resolve('Your session expired. Reload the page to sign in again.');
    }
    return response.json().then(function (data) {
      return (data && data.message) || GENERIC_FAILURE;
    }).catch(function () { return GENERIC_FAILURE; });
  }

  function saveOne(form, scope, control) {
    const state = stateFor(form);
    const status = statusEl(form, scope);
    const previous = state.snapshots.get(scope) || [];
    const sent = snapshot(scope);
    form.setAttribute('aria-busy', 'true');
    setStatus(status, 'Saving…');

    return fetch(form.getAttribute('action') || window.location.pathname, {
      method: 'POST',
      headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
      body: payload(form, scope).toString(),
      credentials: 'same-origin',
      redirect: 'manual'
    }).then(function (response) {
      if (response.type === 'opaqueredirect' || !response.ok) {
        return failureMessage(response).then(function (message) { throw new SaveError(message); });
      }
      state.snapshots.set(scope, sent);
      onSaved(form, scope, status, previous, sent);
    }).catch(function (err) {
      const message = err instanceof SaveError ? err.message : GENERIC_FAILURE;
      // Later changes in this scope were made on top of the one that failed.
      state.queue.delete(scope);
      restore(state.snapshots.get(scope) || []);
      setStatus(status, message, true);
      if (!inView(status) && typeof showToast === 'function') showToast(message, 'danger');
    });
  }

  function onSaved(form, scope, status, previous, sent) {
    setStatus(status, 'Saved');
    if (form.hasAttribute('data-autosave-refresh-attention') && window.DmsAttention) {
      window.DmsAttention.refresh();
    }
    // Undo only returns to a choice the member had actually made before.
    const before = previous.find(function (s) { return s.checked && s.el.type === 'radio'; });
    const after = sent.find(function (s) { return s.checked && s.el.type === 'radio'; });
    const canUndo = form.hasAttribute('data-autosave-undo') && before && after && before.el !== after.el;
    if (typeof showToast !== 'function' || (inView(status) && !canUndo)) return;
    const message = form.dataset.autosaveMessage || 'Saved';
    showToast(message, 'success', canUndo ? {
      action: {
        label: 'Undo',
        onClick: function () {
          restore(previous);
          enqueue(form, before.el);
        }
      }
    } : {});
  }

  function enqueue(form, control) {
    const state = stateFor(form);
    state.queue.set(scopeOf(form, control), control);
    drain(form);
  }

  function drain(form) {
    const state = stateFor(form);
    if (state.running) return;
    const next = state.queue.entries().next();
    if (next.done) { form.removeAttribute('aria-busy'); notifyIdle(); return; }
    const scope = next.value[0];
    const control = next.value[1];
    state.queue.delete(scope);
    state.running = true;
    saveOne(form, scope, control).then(function () {
      state.running = false;
      drain(form);
    });
  }

  function schedule(form, control) {
    const state = stateFor(form);
    const scope = scopeOf(form, control);
    if (control.type !== 'radio') { enqueue(form, control); return; }
    // Arrowing through a radio group fires a change per option; save where it stops.
    if (state.timers.has(scope)) clearTimeout(state.timers.get(scope));
    state.timers.set(scope, setTimeout(function () {
      state.timers.delete(scope);
      enqueue(form, control);
    }, RADIO_DEBOUNCE_MS));
  }

  function pending() {
    let any = false;
    states.forEach(function (s) { if (s.running || s.queue.size || s.timers.size) any = true; });
    return any;
  }

  const idleWaiters = [];
  function notifyIdle() {
    if (pending()) return;
    idleWaiters.splice(0).forEach(function (fn) { fn(); });
  }

  document.querySelectorAll('form[data-autosave]').forEach(function (form) {
    // What is on the page now is what is saved; a failed save goes back to this.
    const sel = form.dataset.autosaveScope;
    const scopes = sel ? Array.from(form.querySelectorAll(sel)) : [form];
    scopes.forEach(function (scope) { stateFor(form).snapshots.set(scope, snapshot(scope)); });
    form.addEventListener('change', function (e) {
      if (restoring || !e.target || !e.target.name) return;
      schedule(form, e.target);
    });
  });

  let waiting = false;
  document.addEventListener('click', function (e) {
    const link = e.target.closest && e.target.closest('a[data-autosave-wait]');
    if (!link || !pending()) return;
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    if (waiting) return;
    waiting = true;
    link.setAttribute('aria-disabled', 'true');
    idleWaiters.push(function () { window.location.href = link.href; });
  });

  window.addEventListener('beforeunload', function (e) {
    if (!pending()) return;
    e.preventDefault();
    e.returnValue = '';
    // The page loader starts its bar on any unload; if the member stays, clear it.
    setTimeout(function () { if (window.mockCompletePage) window.mockCompletePage(); }, 500);
  });

  window.DmsAutosave = { pending: pending };
})();
