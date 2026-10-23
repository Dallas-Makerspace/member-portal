/* One-shot Bambu print watch buttons on the printer status page (bambu-printer-status.html).
 * Needs app.js (setButtonLoading, showToast) and Bootstrap on the page.
 * A button is either un-armed ("Notify me…" — click POSTs /bambu-api/watches) or armed
 * ("Watching · Cancel" — click DELETEs /bambu-api/watches/{id}). Service errors carry
 * member-facing messages (409/422 from the arm validation) and are shown verbatim as toasts. */
(function () {
    'use strict';

    function setArmed(btn, watchId) {
        btn.dataset.watchId = watchId || '';
        var armed = !!watchId;
        var label = btn.querySelector('.bambu-watch-label');
        var icon = btn.querySelector('.material-symbols-outlined');
        if (label) {
            label.textContent = armed
                ? 'Watching · Cancel'
                : (btn.dataset.scope === 'PRINTER_AVAILABLE' ? 'Notify me when free' : 'Notify me when this print finishes');
        }
        if (icon) icon.textContent = armed ? 'notifications_active' : 'notifications';
        btn.classList.toggle('btn-outline-primary', armed);
        btn.classList.toggle('btn-primary', !armed && btn.dataset.scope === 'OWN_JOB');
        btn.classList.toggle('btn-outline-secondary', !armed && btn.dataset.scope === 'PRINTER_AVAILABLE');
    }

    async function arm(btn) {
        var body = {scope: btn.dataset.scope, printerId: Number(btn.dataset.printerId)};
        if (btn.dataset.queueId) body.queueId = Number(btn.dataset.queueId);
        setButtonLoading(btn, true, 'Setting reminder…');
        try {
            var response = await fetch('/bambu-api/watches', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
            });
            var result = await response.json().catch(function () { return {}; });
            if (response.ok && result.data && result.data.id) {
                setArmed(btn, result.data.id);
                showToast('Reminder set — we\'ll notify you about this print.', 'success');
            } else {
                showToast(result.message || 'Could not set the reminder. Please try again.', 'danger');
            }
        } catch (error) {
            console.error('Error arming Bambu watch:', error);
            showToast('Could not set the reminder. Please try again.', 'danger');
        } finally {
            setButtonLoading(btn, false);
        }
    }

    async function cancel(btn) {
        var watchId = btn.dataset.watchId;
        if (!watchId) return;
        setButtonLoading(btn, true, 'Cancelling…');
        try {
            var response = await fetch('/bambu-api/watches/' + encodeURIComponent(watchId), {method: 'DELETE'});
            var result = await response.json().catch(function () { return {}; });
            if (response.ok) {
                setArmed(btn, null);
                showToast('Reminder cancelled.', 'success');
            } else {
                showToast(result.message || 'Could not cancel the reminder. Please try again.', 'danger');
            }
        } catch (error) {
            console.error('Error cancelling Bambu watch:', error);
            showToast('Could not cancel the reminder. Please try again.', 'danger');
        } finally {
            setButtonLoading(btn, false);
        }
    }

    function bind(btn) {
        btn.addEventListener('click', function () {
            if (btn.dataset.watchId) cancel(btn);
            else arm(btn);
        });
    }

    function init() {
        document.querySelectorAll('.bambu-watch-btn').forEach(bind);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
