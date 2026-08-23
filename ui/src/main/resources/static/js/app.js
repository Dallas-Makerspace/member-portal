// Seasonal effect date ranges (modify dates here)
// Hash overrides: #snow, #hearts, #fireworks
window.SEASONAL_DATE_RANGES = {
    winter: [
        { startMonth: 12, startDay: 24, endMonth: 12, endDay: 31 },
        { startMonth: 1, startDay: 1, endMonth: 1, endDay: 1 }
    ],
    valentines: [
        { startMonth: 2, startDay: 13, endMonth: 2, endDay: 14 }
    ],
    independence: [
        { startMonth: 7, startDay: 3, endMonth: 7, endDay: 4 }
    ]
};

function shouldLoadSeasonalEffect() {
    // Check hash overrides
    if (['#snow', '#hearts', '#fireworks'].includes(window.location.hash)) {
        return true;
    }
    // Check date ranges
    const now = new Date();
    const month = now.getMonth() + 1;
    const day = now.getDate();
    for (const ranges of Object.values(window.SEASONAL_DATE_RANGES)) {
        for (const r of ranges) {
            if (r.startMonth === r.endMonth) {
                if (month === r.startMonth && day >= r.startDay && day <= r.endDay) return true;
            } else {
                if ((month === r.startMonth && day >= r.startDay) ||
                (month === r.endMonth && day <= r.endDay)) return true;
            }
        }
    }
    return false;
}

// Helper function to get platform/browser info for PWA tracking
function getPWAContext() {
    const ua = navigator.userAgent;
    const isStandalone = window.matchMedia('(display-mode: standalone)').matches
    || window.navigator.standalone
    || document.referrer.includes('android-app://');

    return {
        platform: /Android/i.test(ua) ? 'android'
            : /iPhone|iPad|iPod/i.test(ua) ? 'ios'
            : /Windows/i.test(ua) ? 'windows'
            : /Mac/i.test(ua) ? 'macos'
            : /Linux/i.test(ua) ? 'linux'
            : 'unknown',
        browser: /Chrome/i.test(ua) && !/Edg/i.test(ua) ? 'chrome'
            : /Edg/i.test(ua) ? 'edge'
            : /Firefox/i.test(ua) ? 'firefox'
            : /Safari/i.test(ua) && !/Chrome/i.test(ua) ? 'safari'
            : 'other',
        isStandalone: isStandalone,
        displayMode: isStandalone ? 'standalone' : 'browser'
    };
}

document.addEventListener("DOMContentLoaded", (event) => {
    printDMSItBanner();
    consumePendingToast();
    // Hide toast after 20 seconds. Only affects the legacy #liveToast and a server-rendered
    // #dmsToast (marked via data-server-rendered) - never a JS-fired showToast(), which manages
    // its own ~5s auto-hide.
    setTimeout(() => {
        hideToast();
        const dmsToast = document.getElementById('dmsToast');
        if (dmsToast && dmsToast.dataset.serverRendered === 'true') {
            bootstrap.Toast.getOrCreateInstance(dmsToast).hide();
        }
    }, 20000);
    if (shouldLoadSeasonalEffect()) {
        loadScript('/static/js/falling-effect-v2.js');
    }
    initFeaturedProjectsScroll();
});

function initFeaturedProjectsScroll() {
    document.querySelectorAll('.featured-projects-track-wrap').forEach(function (wrap) {
        var row = wrap.querySelector('.featured-projects-row');
        if (!row) return;
        var btnLeft = wrap.querySelector('.featured-scroll-btn--left');
        var btnRight = wrap.querySelector('.featured-scroll-btn--right');
        if (!btnLeft || !btnRight) return;
        var STEP = 640;

        function update() {
            var atStart = row.scrollLeft <= 4;
            var atEnd = row.scrollLeft + row.clientWidth >= row.scrollWidth - 4;
            btnLeft.style.opacity = atStart ? '0' : '1';
            btnLeft.style.pointerEvents = atStart ? 'none' : 'auto';
            btnRight.style.opacity = atEnd ? '0' : '1';
            btnRight.style.pointerEvents = atEnd ? 'none' : 'auto';
            wrap.classList.toggle('fade-left', !atStart);
            wrap.classList.toggle('fade-right', !atEnd);
        }

        btnLeft.addEventListener('click', function () { row.scrollBy({ left: -STEP, behavior: 'smooth' }); });
        btnRight.addEventListener('click', function () { row.scrollBy({ left: STEP, behavior: 'smooth' }); });
        row.addEventListener('scroll', update, { passive: true });

        row.addEventListener('wheel', function (e) {
            if (row.scrollWidth <= row.clientWidth) return;
            e.preventDefault();
            row.scrollBy({ left: e.deltaY !== 0 ? e.deltaY : e.deltaX, behavior: 'auto' });
        }, { passive: false });

        update();
    });
}

// Service Worker Registration
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/sw.js')
            .then(registration => {
            console.log('ServiceWorker registered:', registration.scope);

            // Track successful registration
            if (typeof track === 'function') {
                track('pwa_service_worker_registered', {
                    scope: registration.scope,
                    ...getPWAContext()
                });
            }

            // Check for updates every hour
            setInterval(() => {
                registration.update();
            }, 60 * 60 * 1000);

            // Listen for updates
            registration.addEventListener('updatefound', () => {
                const newWorker = registration.installing;

                if (typeof track === 'function') {
                    track('pwa_service_worker_update_found', {
                        state: newWorker.state
                    });
                }

                newWorker.addEventListener('statechange', () => {
                    if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                        console.log('New service worker available');

                        if (typeof track === 'function') {
                            track('pwa_service_worker_update_available', {
                                previousController: !!navigator.serviceWorker.controller
                            });
                        }
                        // Optionally notify user about update
                    }
                });
            });
        })
            .catch(err => {
            console.log('ServiceWorker registration failed:', err);

            // Track registration failures
            if (typeof track === 'function') {
                track('pwa_service_worker_registration_failed', {
                    error: err.message || 'Unknown error',
                    ...getPWAContext()
                });
            }
        });
    });
}

// PWA Install Tracking
let deferredPrompt;
let installPromptShownAt = null;

window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    installPromptShownAt = Date.now();

    if (typeof track === 'function') {
        track('pwa_install_prompt_shown', {
            source: 'browser_native',
            ...getPWAContext()
        });
    }

    // Track user's choice (accept or dismiss)
    e.userChoice.then((choiceResult) => {
        if (typeof track === 'function') {
            track('pwa_install_prompt_result', {
                outcome: choiceResult.outcome, // 'accepted' or 'dismissed'
                source: 'browser_native',
                ...getPWAContext()
            });
        }
    });
});

window.addEventListener('appinstalled', () => {
    const installDuration = installPromptShownAt
        ? Date.now() - installPromptShownAt
        : null;

    if (typeof track === 'function') {
        track('pwa_installed', {
            source: 'browser_native',
            installDurationMs: installDuration,
            ...getPWAContext()
        });
    }

    deferredPrompt = null;
    installPromptShownAt = null;
});

// Track PWA launch source (once per session)
window.addEventListener('load', () => {
    const context = getPWAContext();

    if (context.isStandalone && !sessionStorage.getItem('pwa_launch_tracked')) {
        if (typeof track === 'function') {
            track('pwa_launched', {
                displayMode: 'standalone',
                launchSource: 'pwa_icon',
                ...context
            });
            sessionStorage.setItem('pwa_launch_tracked', 'true');
        }
    }
});

function loadScript(src) {
    const script = document.createElement('script');
    script.src = src;
    document.head.appendChild(script);
}

function printDMSItBanner() {
    var titleCss = "font-size: 40px;color: red;font-weight: bold;";

    console.log("%cDMS IT Infra", titleCss);
    console.log("%cThis site is built by DMS IT Infra volunteers. Come say hi at one of our meetings - https://calendar.dallasmakerspace.org/?category=103", 'color: green');
    console.log("%cWe're always looking for IT volunteers to help with the infrastructure, networking and more.", 'color: green');
}

/**
 * Copy text to the clipboard and show brief "copied" feedback on the triggering button.
 * The button should contain a <span class="material-symbols-outlined"> icon.
 * @param {string} text - The text to copy
 * @param {HTMLElement} btn - The button element that was clicked
 */
function copyUsernameToClipboard(text, btn) {
    const writeText = (t) => {
        if (navigator.clipboard && window.isSecureContext) {
            return navigator.clipboard.writeText(t);
        }
        // Fallback for non-secure contexts
        return new Promise((resolve, reject) => {
            const ta = document.createElement('textarea');
            ta.value = t;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            try { document.execCommand('copy') ? resolve() : reject(); }
            catch (e) { reject(e); }
            finally { document.body.removeChild(ta); }
        });
    };

    writeText(text).then(() => {
        const icon = btn.querySelector('.material-symbols-outlined');
        if (!icon) return;
        const original = icon.textContent;
        icon.textContent = 'check';
        btn.classList.add('text-success');
        btn.setAttribute('title', 'Copied!');
        setTimeout(() => {
            icon.textContent = original;
            btn.classList.remove('text-success');
            btn.setAttribute('title', btn.dataset.copyTitle || 'Copy');
        }, 1500);
    }).catch((err) => {
        console.error('Error copying to clipboard:', err);
    });
}

function hideToast() {
    const toastLive = document.getElementById('liveToast');
    if (toastLive) {
        const toastBootstrap = bootstrap.Toast.getOrCreateInstance(toastLive);
        toastBootstrap.hide();
    }
}

/**
 * Show the shared toast (templates/fragments/toast.html). No-op if the page has no toast host
 * (i.e. doesn't include the fragment) or Bootstrap hasn't loaded yet.
 * @param {string} message - Plain text; set via textContent, never innerHTML.
 * @param {'success'|'danger'|'warning'|'info'} [type='success']
 */
function showToast(message, type = 'success') {
    const el = document.getElementById('dmsToast');
    if (!el || typeof bootstrap === 'undefined') return;
    const body = document.getElementById('dmsToastMessage');
    if (body) body.textContent = message;
    el.className = 'toast align-items-center border-0 text-bg-' + type;
    delete el.dataset.serverRendered;
    bootstrap.Toast.getOrCreateInstance(el, { delay: 5000, autohide: true }).show();
}

const PENDING_TOAST_KEY = 'dms_pending_toast';

/**
 * Stash a toast to show after the *next* page load instead of right now - use this immediately
 * before a client-triggered `window.location.reload()` (same URL, no server redirect) so the
 * toast appears once the reloaded page's UI already reflects the change, rather than flashing on
 * the stale pre-reload page. Call showToast() directly when nothing is about to reload.
 * For a server-driven redirect-after-POST instead, use the model's `toast_message` variable
 * (see fragments/toast.html) - that flows through the server render, not sessionStorage.
 * Consumed once by consumePendingToast() on the next DOMContentLoaded, wherever that lands; if
 * that page doesn't include the toast fragment, the message is dropped rather than shown later.
 * @param {string} message
 * @param {'success'|'danger'|'warning'|'info'} [type='success']
 */
function showToastAfterReload(message, type = 'success') {
    try {
        sessionStorage.setItem(PENDING_TOAST_KEY, JSON.stringify({ message, type }));
    } catch (e) {
        // sessionStorage unavailable (e.g. private browsing) - the toast is simply skipped.
    }
}

// Show + clear any toast stashed by showToastAfterReload() before this page load.
function consumePendingToast() {
    let pending;
    try {
        pending = sessionStorage.getItem(PENDING_TOAST_KEY);
        if (pending) sessionStorage.removeItem(PENDING_TOAST_KEY);
    } catch (e) {
        return;
    }
    if (!pending) return;
    try {
        const { message, type } = JSON.parse(pending);
        showToast(message, type);
    } catch (e) {
        console.error('Failed to parse pending toast:', e);
    }
}

/**
 * Escape HTML to prevent XSS attacks
 * @param {string} text - The text to escape
 * @returns {string} The escaped HTML string
 */
function escapeHtml(text) {
    if (text == null) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}