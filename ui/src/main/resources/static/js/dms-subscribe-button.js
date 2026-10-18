/* Subscribe/unsubscribe buttons for notification topics (fragments/subscribe-button.html).
 * Needs app.js (setButtonLoading, showToast, dmsConfirm) and Bootstrap on the page. */
(function () {
    'use strict';

    // Must match buttonClass() in SubscriptionButton.kt, which renders the initial state.
    const STYLES = {
        full: {off: 'btn-primary', on: 'btn-outline-primary', hover: 'btn-outline-danger'},
        icon: {off: 'btn-outline-secondary', on: 'btn-outline-primary dms-subscribe-on'}
    };

    const isIconOnly = (btn) => btn.dataset.iconOnly === 'true';
    const isSubscribed = (btn) => btn.dataset.subscribed === 'true';
    const styleFor = (btn) => STYLES[isIconOnly(btn) ? 'icon' : 'full'];
    const classes = (value) => (value || '').split(' ').filter(Boolean);

    function tooltipFor(btn) {
        return isIconOnly(btn) && typeof bootstrap !== 'undefined'
            ? bootstrap.Tooltip.getOrCreateInstance(btn, {title: btn.getAttribute('aria-label') || '', placement: 'bottom'})
            : null;
    }

    /** Sets the look for [subscribed]; [hovering] shows the subscribed full button as its "Unfollow" action. */
    function render(btn, copy, subscribed, hovering) {
        const style = styleFor(btn);
        const unsubscribeAction = subscribed && hovering && !isIconOnly(btn);
        btn.dataset.subscribed = String(subscribed);
        // The wrapper's state picks which of the two stacked hints is visible (see global.css).
        const unit = btn.closest('.dms-subscribe');
        if (unit) unit.dataset.subscribed = String(subscribed);
        Object.values(style).forEach((value) => btn.classList.remove(...classes(value)));
        btn.classList.add(...classes(unsubscribeAction ? style.hover : subscribed ? style.on : style.off));

        const icon = btn.querySelector('.material-symbols-outlined');
        if (icon) {
            icon.textContent = unsubscribeAction ? copy.unsubscribeIcon : subscribed ? copy.subscribedIcon : copy.subscribeIcon;
        }
        if (isIconOnly(btn)) {
            const action = subscribed ? copy.unsubscribeLabel : copy.subscribeLabel;
            btn.setAttribute('aria-label', action);
            const tip = tooltipFor(btn);
            if (tip) tip.setContent({'.tooltip-inner': action});
            return;
        }
        btn.setAttribute('aria-pressed', String(subscribed));
        const text = btn.querySelector('.dms-subscribe-label');
        if (text) text.textContent = unsubscribeAction ? copy.unsubscribeLabel : subscribed ? copy.subscribedLabel : copy.subscribeLabel;
    }

    async function onClick(btn) {
        const topic = btn.dataset.topic;
        const args = JSON.parse(btn.dataset.args || '{}');
        const copy = JSON.parse(btn.dataset.copy || '{}');
        const wasSubscribed = isSubscribed(btn);
        // Otherwise the tooltip stays visible over the dialog's backdrop.
        const tip = bootstrap.Tooltip.getInstance(btn);
        if (tip) tip.hide();

        const confirm = wasSubscribed ? copy.unsubscribeConfirm : copy.subscribeConfirm;
        if (confirm && !(await dmsConfirm(confirm))) return;

        setButtonLoading(btn, true, wasSubscribed ? copy.unsubscribeBusy : copy.subscribeBusy);
        try {
            const url = '/backend-api/subscriptions/' + encodeURIComponent(topic) + '?' + new URLSearchParams(args);
            const response = await fetch(url, {method: wasSubscribed ? 'DELETE' : 'POST'});
            const result = await response.json().catch(() => ({}));
            if (response.ok && result.status === 'SUCCESS' && result.data) {
                const subscribed = result.data.subscribed === true;
                // The pointer is still over the button; don't flash "Unfollow" until it leaves and returns.
                btn.dataset.holdRest = 'true';
                render(btn, copy, subscribed, false);
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

    function bind(btn) {
        tooltipFor(btn);
        btn.addEventListener('click', () => onClick(btn));
        if (isIconOnly(btn)) return;

        // Hover only: keyboard focus keeps "Following" (aria-pressed), and touch has no hover.
        const canHover = window.matchMedia('(hover: hover)');
        btn.addEventListener('mouseenter', () => {
            if (!canHover.matches || !isSubscribed(btn) || btn.dataset.holdRest || btn.dataset.dmsLoading) return;
            render(btn, JSON.parse(btn.dataset.copy || '{}'), true, true);
        });
        btn.addEventListener('mouseleave', () => {
            delete btn.dataset.holdRest;
            if (isSubscribed(btn) && !btn.dataset.dmsLoading) render(btn, JSON.parse(btn.dataset.copy || '{}'), true, false);
        });
    }

    function init() {
        document.querySelectorAll('[data-dms-subscribe]').forEach(bind);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
