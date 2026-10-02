/**
 * Page Loading Indicator
 * A lightweight, self-contained loading bar that displays at the top of the page
 * during navigation events.
 */
(function() {
    // Hard upper bound: no matter what, the bar never outlives this.
    const MAX_DURATION_MS = 30000;

    let loader = null;
    let navigating = false;
    let stepTimers = [];
    let watchdogTimer = null;
    let hideTimer = null;

    const getLoader = () => {
        if (loader && loader.isConnected) return loader;
        loader = document.getElementById('yt-style-loader');
        if (!loader) {
            loader = document.createElement('div');
            loader.id = 'yt-style-loader';
            loader.style.position = 'fixed';
            loader.style.top = '0';
            loader.style.left = '0';
            loader.style.height = '3px';
            loader.style.width = '0%';
            loader.style.backgroundColor = '#ff0000'; // YouTube red
            loader.style.zIndex = '9999';
            loader.style.transition = 'width 0.2s ease-out';
            loader.style.boxShadow = '0 1px 2px rgba(0,0,0,0.1)';
            loader.style.display = 'none';
            document.body.appendChild(loader);
        }
        return loader;
    };

    const clearTimers = () => {
        stepTimers.forEach(clearTimeout);
        stepTimers = [];
        clearTimeout(watchdogTimer);
        clearTimeout(hideTimer);
    };

    const setNavigatingState = (value) => {
        try {
            if (value) {
                sessionStorage.setItem('yt_loader_navigating', 'true');
            } else {
                sessionStorage.removeItem('yt_loader_navigating');
            }
        } catch (e) {
            // sessionStorage may be unavailable
        }
    };

    const getNavigatingState = () => {
        try {
            return sessionStorage.getItem('yt_loader_navigating') === 'true';
        } catch (e) {
            return false;
        }
    };

    const step = (delay, width) => {
        stepTimers.push(setTimeout(() => {
            getLoader().style.width = width;
        }, delay));
    };

    const start = () => {
        if (navigating) return;
        navigating = true;
        setNavigatingState(true);
        clearTimers();

        const el = getLoader();
        el.style.display = 'block';
        step(50, '30%');
        step(300, '50%');
        step(1000, '70%');
        step(2000, '85%');

        // Covers navigations that never happen or never unload the page
        // (preventDefault, downloads, cancelled beforeunload, fetch-driven forms).
        watchdogTimer = setTimeout(complete, MAX_DURATION_MS);
    };

    const complete = () => {
        const wasNavigating = navigating;
        navigating = false;
        setNavigatingState(false);
        clearTimers();
        if (!wasNavigating && !(loader && loader.style.display !== 'none')) return;

        const el = getLoader();
        el.style.width = '100%';
        hideTimer = setTimeout(() => {
            el.style.display = 'none';
            el.style.width = '0%';
        }, 300);
    };

    // Start only if the event was not cancelled by any handler once dispatch finished.
    const startUnlessCancelled = (e) => {
        setTimeout(() => {
            if (!e.defaultPrevented) start();
        }, 0);
    };

    const opensElsewhere = (el) => el.target && el.target !== '_self';

    const setup = () => {
        if (getNavigatingState()) complete();

        document.addEventListener('click', (e) => {
            if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
            const link = e.target.closest && e.target.closest('a');
            const rawHref = link && link.getAttribute('href');
            if (link && link.href && rawHref && !rawHref.startsWith('#') &&
                !link.href.startsWith('javascript:') &&
                !link.hasAttribute('download') &&
                !opensElsewhere(link) &&
                link.hostname === window.location.hostname) {
                startUnlessCancelled(e);
            }
        });

        document.addEventListener('submit', (e) => {
            if (!opensElsewhere(e.target)) startUnlessCancelled(e);
        });

        window.addEventListener('popstate', start);
        window.addEventListener('beforeunload', start);

        // Any return to a visible, loaded page means no navigation is in flight.
        window.addEventListener('pageshow', complete);
        window.addEventListener('load', complete);
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') complete();
        });
    };

    // Manual completion for fetch-driven flows.
    window.mockCompletePage = complete;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', setup);
    } else {
        setup();
    }
})();
