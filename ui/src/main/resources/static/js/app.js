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
 * Copy text to the clipboard and show brief "copied" feedback on the triggering button: an inner
 * <span class="material-symbols-outlined"> icon turns into a check mark, otherwise the button's
 * own text reads "Copied".
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
        if (btn.classList.contains('text-success')) return; // feedback already showing
        const icon = btn.querySelector('.material-symbols-outlined');
        const label = icon || btn;
        const original = label.textContent;
        label.textContent = icon ? 'check' : 'Copied';
        btn.classList.add('text-success');
        btn.setAttribute('title', 'Copied!');
        setTimeout(() => {
            label.textContent = original;
            btn.classList.remove('text-success');
            btn.setAttribute('title', btn.dataset.copyTitle || 'Copy');
        }, 1500);
    }).catch((err) => {
        console.error('Error copying to clipboard:', err);
    });
}

/**
 * Show or clear an in-button spinner for a pending action. The spinner replaces the button's
 * Material icon if it has one, otherwise it is prepended to the label. Locks the button's width so
 * it doesn't jump, disables it and sets aria-busy. Call with `false` to restore (error paths, or
 * bfcache restores); on success paths that reload the page, leave it spinning.
 * @param {HTMLElement|null} btn
 * @param {boolean} loading
 * @param {string} [srText='Working…'] - Screen-reader text announced while busy.
 */
function setButtonLoading(btn, loading, srText = 'Working…') {
    if (!btn) return;
    const isLink = btn.tagName === 'A';
    if (loading) {
        if (btn.dataset.dmsLoading) return;
        btn.dataset.dmsLoading = 'true';
        btn.style.minWidth = btn.getBoundingClientRect().width + 'px';
        btn.setAttribute('aria-busy', 'true');
        if (isLink) {
            btn.classList.add('disabled');
            btn.setAttribute('aria-disabled', 'true');
        } else {
            btn.disabled = true;
        }
        const spinner = document.createElement('span');
        spinner.className = 'spinner-border spinner-border-sm';
        spinner.setAttribute('aria-hidden', 'true');
        spinner.dataset.dmsSpinner = 'true';
        const sr = document.createElement('span');
        sr.className = 'visually-hidden';
        sr.setAttribute('role', 'status');
        sr.dataset.dmsSpinner = 'true';
        sr.textContent = srText;
        const icon = btn.querySelector('.material-symbols-outlined');
        if (icon) {
            icon.classList.add('d-none');
            icon.dataset.dmsHidden = 'true';
            icon.after(spinner, sr);
        } else {
            spinner.classList.add('me-1');
            btn.prepend(spinner, sr);
        }
    } else {
        btn.querySelectorAll('[data-dms-spinner]').forEach((el) => el.remove());
        const icon = btn.querySelector('[data-dms-hidden]');
        if (icon) {
            icon.classList.remove('d-none');
            delete icon.dataset.dmsHidden;
        }
        btn.style.minWidth = '';
        btn.removeAttribute('aria-busy');
        if (isLink) {
            btn.classList.remove('disabled');
            btn.removeAttribute('aria-disabled');
        } else {
            btn.disabled = false;
        }
        delete btn.dataset.dmsLoading;
    }
}

// Full-page form posts: spin the submit button. Opt out with data-no-loading on the form.
document.addEventListener('submit', (e) => {
    setTimeout(() => {
        const form = e.target;
        if (e.defaultPrevented || form.hasAttribute('data-no-loading')) return;
        if (form.target && form.target !== '_self') return;
        setButtonLoading(e.submitter || form.querySelector('button[type="submit"], button:not([type])'), true, 'Saving…');
    }, 0);
});

// Back/forward cache restores the page mid-spin; clear any stuck buttons.
window.addEventListener('pageshow', (e) => {
    if (e.persisted) {
        document.querySelectorAll('[data-dms-loading]').forEach((btn) => setButtonLoading(btn, false));
    }
});

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
/**
 * Bootstrap confirm modal. Resolves true when the confirm button is clicked, false on cancel/close.
 * All text is set via textContent.
 * @param {{title?: string, message?: string, details?: string[], confirmLabel?: string, danger?: boolean}} opts
 * @returns {Promise<boolean>}
 */
function dmsConfirm(opts) {
    return new Promise((resolve) => {
        const el = (tag, className, text) => {
            const node = document.createElement(tag);
            if (className) node.className = className;
            if (text) node.textContent = text;
            return node;
        };
        const button = (className, text, dismiss) => {
            const btn = el('button', className, text);
            btn.type = 'button';
            if (dismiss) btn.setAttribute('data-bs-dismiss', 'modal');
            return btn;
        };

        let confirmed = false;
        const confirmBtn = button('btn btn-' + (opts.danger ? 'danger' : 'primary'), opts.confirmLabel || 'Confirm');
        const closeBtn = button('btn-close', '', true);
        closeBtn.setAttribute('aria-label', 'Close');

        const header = el('div', 'modal-header');
        header.append(el('h5', 'modal-title', opts.title || 'Are you sure?'), closeBtn);
        const body = el('div', 'modal-body');
        body.append(el('p', 'mb-0', opts.message || ''));
        (opts.details || []).forEach((d) => body.append(el('p', 'small text-muted mb-0 mt-2', d)));
        const footer = el('div', 'modal-footer');
        footer.append(button('btn btn-outline-secondary', 'Cancel', true), confirmBtn);

        const content = el('div', 'modal-content');
        content.append(header, body, footer);
        const dialog = el('div', 'modal-dialog');
        dialog.append(content);
        const root = el('div', 'modal fade');
        root.tabIndex = -1;
        root.setAttribute('aria-hidden', 'true');
        root.append(dialog);
        document.body.appendChild(root);

        const modal = new bootstrap.Modal(root);
        confirmBtn.addEventListener('click', () => {
            confirmed = true;
            modal.hide();
        });
        root.addEventListener('hidden.bs.modal', () => {
            modal.dispose();
            root.remove();
            resolve(confirmed);
        });
        modal.show();
    });
}
