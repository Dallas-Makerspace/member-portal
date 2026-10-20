// Autosave for forms marked [data-autosave]: each change is posted on its own, so there is no Save
// button to forget. Options on the form:
//   data-autosave-scope="<selector>"   post only the changed control's closest match (e.g. "tr"),
//                                      plus hidden inputs outside any match; default: the whole form
//   data-autosave-refresh-attention    refresh the account-menu attention dot after a save
// A [data-autosave-status] element (inside the scope, else the form) shows Saving / Saved / errors.
// Links marked [data-autosave-wait] hold until pending saves finish. Leaving while a save is still
// running asks for confirmation. A failed save puts the control back to what was last saved.
(function () {
  'use strict';

  const SAVED_VISIBLE_MS = 3000;
  const states = new Map();
  let restoring = false;

  function stateFor(form) {
    if (!states.has(form)) states.set(form, { inFlight: false, queued: null, snapshots: new Map() });
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

  function restore(snap) {
    restoring = true;
    snap.forEach(function (s) {
      if (s.el.type === 'checkbox' || s.el.type === 'radio') s.el.checked = s.checked;
      else s.el.value = s.value;
      s.el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    restoring = false;
  }

  function payload(form, control) {
    const sel = form.dataset.autosaveScope;
    const scope = scopeOf(form, control);
    const params = new URLSearchParams();
    // Hidden fields of the changed scope, plus those outside every scope (e.g. the form name).
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

  function setStatus(el, text, isError) {
    if (!el) return;
    el.textContent = text;
    el.classList.toggle('text-danger', !!isError);
    if (el._clear) clearTimeout(el._clear);
    if (text === 'Saved') {
      el._clear = setTimeout(function () { el.textContent = ''; }, SAVED_VISIBLE_MS);
    }
  }

  const GENERIC_FAILURE = 'Couldn’t save. Check your connection and try again.';

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

  function save(form, control) {
    const state = stateFor(form);
    if (state.inFlight) { state.queued = control; return; }
    const scope = scopeOf(form, control);
    const status = statusEl(form, scope);
    if (!state.snapshots.has(scope)) state.snapshots.set(scope, snapshot(scope));
    state.inFlight = true;
    form.setAttribute('aria-busy', 'true');
    setStatus(status, 'Saving…');

    fetch(form.getAttribute('action') || window.location.pathname, {
      method: 'POST',
      headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
      body: payload(form, control).toString(),
      credentials: 'same-origin',
      redirect: 'manual'
    }).then(function (response) {
      if (response.type === 'opaqueredirect' || !response.ok) {
        return failureMessage(response).then(function (message) { throw new SaveError(message); });
      }
      state.snapshots.set(scope, snapshot(scope));
      setStatus(status, 'Saved');
      if (form.hasAttribute('data-autosave-refresh-attention') && window.DmsAttention) {
        window.DmsAttention.refresh();
      }
    }).catch(function (err) {
      state.queued = null;
      restore(state.snapshots.get(scope) || []);
      const message = err instanceof SaveError ? err.message : GENERIC_FAILURE;
      setStatus(status, message, true);
      if (typeof showToast === 'function') showToast(message, 'danger');
    }).then(function () {
      state.inFlight = false;
      form.removeAttribute('aria-busy');
      const next = state.queued;
      state.queued = null;
      if (next) save(form, next);
      else notifyIdle();
    });
  }

  function pending() {
    let any = false;
    states.forEach(function (s) { if (s.inFlight || s.queued) any = true; });
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
      save(form, e.target);
    });
  });

  document.addEventListener('click', function (e) {
    const link = e.target.closest && e.target.closest('a[data-autosave-wait]');
    if (!link || !pending()) return;
    e.preventDefault();
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
