/* Subscribe/unsubscribe buttons for notification topics (fragments/subscribe-button.html).
 * Needs app.js (setButtonLoading, showToast, dmsConfirm) and Bootstrap on the page. */
(function () {
    'use strict';

    function iconOnly(btn) {
        return btn.dataset.iconOnly === 'true';
    }

    function tooltipFor(btn) {
        return iconOnly(btn) && typeof bootstrap !== 'undefined'
            ? bootstrap.Tooltip.getOrCreateInstance(btn, {title: btn.getAttribute('aria-label') || ''})
            : null;
    }

    function render(btn, copy, subscribed) {
        const label = subscribed ? copy.unsubscribeLabel : copy.subscribeLabel;
        btn.dataset.subscribed = String(subscribed);
        btn.setAttribute('aria-pressed', String(subscribed));
        btn.classList.toggle('btn-primary', !subscribed);
        btn.classList.toggle('btn-outline-secondary', subscribed);
        const icon = btn.querySelector('.material-symbols-outlined');
        if (icon) icon.textContent = subscribed ? copy.unsubscribeIcon : copy.subscribeIcon;
        if (iconOnly(btn)) {
            btn.setAttribute('aria-label', label);
            const tip = tooltipFor(btn);
            if (tip) tip.setContent({'.tooltip-inner': label});
        } else {
            const text = btn.querySelector('.dms-subscribe-label');
            if (text) text.textContent = label;
        }
    }

    async function onClick(btn) {
        const topic = btn.dataset.topic;
        const args = JSON.parse(btn.dataset.args || '{}');
        const copy = JSON.parse(btn.dataset.copy || '{}');
        const wasSubscribed = btn.dataset.subscribed === 'true';

        const confirm = wasSubscribed ? copy.unsubscribeConfirm : copy.subscribeConfirm;
        if (confirm && !(await dmsConfirm(confirm))) return;

        setButtonLoading(btn, true, wasSubscribed ? copy.unsubscribeBusy : copy.subscribeBusy);
        try {
            const url = '/backend-api/subscriptions/' + encodeURIComponent(topic) + '?' + new URLSearchParams(args);
            const response = await fetch(url, {method: wasSubscribed ? 'DELETE' : 'POST'});
            const result = await response.json().catch(() => ({}));
            if (response.ok && result.status === 'SUCCESS' && result.data) {
                const subscribed = result.data.subscribed === true;
                render(btn, copy, subscribed);
                showToast(subscribed ? copy.subscribedToast : copy.unsubscribedToast);
                btn.dispatchEvent(new CustomEvent('dms:subscription-changed', {
                    bubbles: true,
                    detail: {topic: topic, args: args, subscribed: subscribed}
                }));
            } else {
                showToast(result.message || 'Could not update. Please try again.', 'danger');
            }
        } catch (error) {
            console.error('Error updating subscription:', error);
            showToast('Could not update. Please try again.', 'danger');
        } finally {
            setButtonLoading(btn, false);
        }
    }

    function init() {
        document.querySelectorAll('[data-dms-subscribe]').forEach(function (btn) {
            tooltipFor(btn);
            btn.addEventListener('click', function () {
                onClick(btn);
            });
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
