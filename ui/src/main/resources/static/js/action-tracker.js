/**
 * User Action Tracker
 * Logs external link clicks and button actions to Elasticsearch
 */
(function() {
    'use strict';

    const TRACK_ENDPOINT = '/api/track';
    // Short links have their own server-side click tracking (short_link_clicks table), so
    // '/go/*' is excluded here to avoid a redundant, less-detailed tracking path.
    const EXCLUDED_PATHS = ['/go/'];

    /**
     * Check if current page should be excluded from tracking
     */
    function isExcludedPage() {
        const path = window.location.pathname;
        return EXCLUDED_PATHS.some(excluded => path.startsWith(excluded));
    }

    /**
     * Send tracking data to server (non-blocking)
     */
    function track(actionType, actionDetails) {
        const payload = JSON.stringify({
            actionType: actionType,
            ...actionDetails,
            uri: window.location.pathname
        });

        if (navigator.sendBeacon) {
            const blob = new Blob([payload], { type: 'application/json' });
            navigator.sendBeacon(TRACK_ENDPOINT, blob);
        } else {
            fetch(TRACK_ENDPOINT, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: payload,
                keepalive: true
            }).catch(function() {});
        }

        // Bridge to PostHog
        if (window.posthog && typeof window.posthog.capture === 'function') {
            window.posthog.capture(actionType, actionDetails || {});
        }
    }

    // Expose track function globally for PWA and other tracking
    window.track = track;

    /**
     * Track external link click
     */
    function trackExternalLink(element) {
        var href = element.getAttribute('href') || '';
        var linkText = (element.textContent || '').trim().substring(0, 100);

        track('external_link_click', {
            destinationUrl: href,
            linkText: linkText
        });
    }

    /**
     * Track button action
     */
    function trackButtonAction(element) {
        var actionName = element.getAttribute('data-track-action') ||
                        (element.textContent || '').trim().substring(0, 50) ||
                        'unknown';
        var actionCategory = element.getAttribute('data-track-category') || 'button';
        var actionLabel = element.getAttribute('data-track-label') || '';

        var actionDetails = {
            actionName: actionName,
            actionCategory: actionCategory
        };

        if (actionLabel) {
            actionDetails.actionLabel = actionLabel;
        }

        track('button_action', actionDetails);
    }

    /**
     * Initialize click tracking
     */
    function init() {
        if (isExcludedPage()) {
            return;
        }

        document.addEventListener('click', function(event) {
            var target = event.target.closest('a[target="_blank"], [data-track-action]');
            if (!target) return;

            if (target.tagName === 'A' && target.getAttribute('target') === '_blank') {
                trackExternalLink(target);
            }

            if (target.hasAttribute('data-track-action')) {
                trackButtonAction(target);
            }
        }, { capture: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
