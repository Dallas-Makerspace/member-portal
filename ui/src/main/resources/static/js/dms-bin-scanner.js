// QR scanner for personal storage bin decommissioning.
// Reads @<username> codes and looks up each member's active status.

const DEBOUNCE_MS = 3000;
const USERNAME_REGEX = /^([A-Za-z0-9._-]+)$/;
const STORAGE_KEY = 'dms-bin-scanner-v1';

const scanList     = document.getElementById('scan-list');
const toastStrip   = document.getElementById('toast-strip');
const countBadge   = document.getElementById('scan-count-badge');
const clearBtn     = document.getElementById('clear-btn');
const emailBtn     = document.getElementById('email-btn'); // null for non-privileged users
const emptyState   = document.getElementById('empty-state');
const cameraContainer = document.getElementById('camera-container');
const startBtn     = document.getElementById('start-btn');
const pauseBtn     = document.getElementById('pause-btn');
const privileged   = document.getElementById('privileged-meta')?.dataset.privileged === 'true';

let scanCount = 0;
let lastScannedUsername = null;
let lastScanTime = 0;
let audioCtx = null;
let cameraStarted = false;

// Tracks scanner-status results keyed by username; includes all fields needed to restore rows.
const scannedData = new Map();

function escHtml(str) {
    return String(str ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function setToast(msg, cls = 'text-muted') {
    toastStrip.className = `mb-3 ps-1 ${cls}`;
    toastStrip.textContent = msg;
}

function playBeep() {
    try {
        if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.connect(gain);
        gain.connect(audioCtx.destination);
        osc.frequency.value = 880;
        osc.type = 'sine';
        gain.gain.setValueAtTime(0.4, audioCtx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.15);
        osc.start(audioCtx.currentTime);
        osc.stop(audioCtx.currentTime + 0.15);
    } catch (_) {}
}

function vibrate() {
    if (navigator.vibrate) navigator.vibrate(150);
}

// Build the placeholder row immediately after scan — dms-member-card loads itself.
function buildPlaceholderRow(username, animate = true) {
    const row = document.createElement('div');
    row.className = `card mb-2 scan-row${animate ? ' new-scan' : ''}`;
    row.id = `row-${escHtml(username)}`;
    row.innerHTML = `
    <div class="card-body py-2 d-flex align-items-center gap-3 flex-wrap">
      <dms-member-card username="${escHtml(username)}" state="mini" openinnewwindow></dms-member-card>
      <span class="badge bg-secondary status-badge">Loading…</span>
      <span class="text-muted small duration-text"></span>
      <span class="contact-area d-flex gap-3 ms-auto"></span>
      <span class="spinner-border spinner-border-sm text-secondary scanner-spinner"></span>
    </div>`;
    return row;
}

// Update status/duration/contact in the existing row without replacing dms-member-card.
function populateRow(username, data) {
    const row = document.getElementById(`row-${username}`);
    if (!row) return;

    const isActive = data.isActive === true;

    const badge = row.querySelector('.status-badge');
    if (badge) {
        badge.className = `badge ${isActive ? 'bg-success' : 'bg-secondary'} status-badge`;
        badge.textContent = isActive ? 'Active' : 'Inactive';
    }

    const duration = row.querySelector('.duration-text');
    if (duration) {
        if (data.daysInCurrentStatus != null) {
            duration.textContent = `${data.daysInCurrentStatus} days`;
        } else if (data.regDate) {
            const [yr, mo, dy] = data.regDate.split('-').map(Number);
            const d = new Date(Date.UTC(yr, mo - 1, dy));
            const days = Math.floor((Date.now() - d) / 86400000);
            const fmt = d.toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' });
            duration.textContent = `since ${fmt} (${days} days)`;
        }
    }

    const contact = row.querySelector('.contact-area');
    if (contact) {
        const parts = [];
        if (privileged) {
            if (data.email) parts.push(`<a href="mailto:${escHtml(data.email)}" class="btn btn-sm btn-outline-secondary" target="_blank">Email</a>`);
            if (data.phone) parts.push(`<a href="sms:${escHtml(data.phone)}" class="btn btn-sm btn-outline-secondary">Text</a>`);
        }
        if (data.discourseUsername) parts.push(`<a href="https://talk.dallasmakerspace.org/new-message?username=${encodeURIComponent(data.discourseUsername)}" class="btn btn-sm btn-outline-secondary d-inline-flex align-items-center gap-1" target="_blank"><img src="/static/images/discourse.svg" style="height:14px;width:14px" alt="">Talk</a>`);
        if (data.discordUserId) parts.push(`<a href="https://discord.com/users/${escHtml(data.discordUserId)}" class="btn btn-sm btn-outline-secondary d-inline-flex align-items-center gap-1" target="_blank"><img src="/static/images/discord.svg" style="height:14px;width:14px" alt="">Discord</a>`);
        contact.innerHTML = parts.join('');
    }

    row.querySelector('.scanner-spinner')?.remove();
    setTimeout(() => row.classList.remove('new-scan'), 1000);
}

function handleError(username, errMsg) {
    const row = document.getElementById(`row-${username}`);
    if (!row) return;
    const badge = row.querySelector('.status-badge');
    if (badge) { badge.className = 'badge bg-danger status-badge'; badge.textContent = 'Error'; }
    const duration = row.querySelector('.duration-text');
    if (duration) duration.textContent = errMsg;
    row.querySelector('.scanner-spinner')?.remove();
}

// Enable the email button when at least one scanned member is inactive for > 15 days.
function updateEmailButton() {
    if (!emailBtn) return;
    const hasEligible = [...scannedData.values()].some(
        d => !d.isActive && d.daysInCurrentStatus != null && d.daysInCurrentStatus > 15,
    );
    emailBtn.disabled = !hasEligible;
}

// Persist the current scan list to localStorage.
function saveToStorage() {
    try {
        const entries = [...scannedData.entries()].map(([username, d]) => ({ username, ...d }));
        localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
    } catch (_) {}
}

// Restore the scan list from localStorage on page load.
function loadFromStorage() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw) return;
        const entries = JSON.parse(raw);
        if (!Array.isArray(entries) || entries.length === 0) return;
        entries.forEach(({ username, ...data }) => {
            if (!username) return;
            scannedData.set(username, data);
            scanList.appendChild(buildPlaceholderRow(username, false));
            populateRow(username, data);
        });
        scanCount = scannedData.size;
        countBadge.textContent = `${scanCount} scanned`;
        clearBtn.style.display = '';
        if (emailBtn) emailBtn.style.display = '';
        if (emptyState) emptyState.style.display = 'none';
        updateEmailButton();
    } catch (_) {
        localStorage.removeItem(STORAGE_KEY);
    }
}

async function fetchAndRender(username) {
    try {
        const res = await fetch(`/scanner-api/status/${encodeURIComponent(username)}`);
        if (res.status === 404) { handleError(username, 'Not found'); return; }
        if (!res.ok) { handleError(username, `HTTP ${res.status}`); return; }
        const data = await res.json();
        scannedData.set(username, {
            isActive: data.isActive,
            daysInCurrentStatus: data.daysInCurrentStatus,
            regDate: data.regDate ?? null,
            email: data.email ?? null,
            phone: data.phone ?? null,
            discourseUsername: data.discourseUsername ?? null,
            discordUserId: data.discordUserId ?? null,
        });
        populateRow(username, data);
        updateEmailButton();
        saveToStorage();
    } catch (_) {
        handleError(username, 'Network error');
    }
}

function onScan(decodedText) {
    const trimmed = decodedText.trim();
    const match = trimmed.match(USERNAME_REGEX);
    if (!match) {
        setToast(`Scanned (not a username): ${trimmed.slice(0, 50)}`, 'text-warning');
        return;
    }

    const username = match[1];
    const now = Date.now();
    if (username === lastScannedUsername && now - lastScanTime < DEBOUNCE_MS) return;
    lastScannedUsername = username;
    lastScanTime = now;

    playBeep();
    vibrate();

    if (emptyState) emptyState.style.display = 'none';
    clearBtn.style.display = '';
    if (emailBtn) emailBtn.style.display = '';

    const existingRow = document.getElementById(`row-${username}`);
    if (existingRow) {
        scanList.prepend(existingRow);
        existingRow.classList.add('new-scan');
        setTimeout(() => existingRow.classList.remove('new-scan'), 1000);
        setToast(`Re-scanned: @${username}`, 'text-success');
    } else {
        setToast(`Scanned: @${username}`, 'text-success');
        scanList.prepend(buildPlaceholderRow(username));
        scanCount++;
        countBadge.textContent = `${scanCount} scanned`;
    }

    fetchAndRender(username);
}

clearBtn.addEventListener('click', () => {
    scanList.innerHTML = '';
    scannedData.clear();
    localStorage.removeItem(STORAGE_KEY);
    scanCount = 0;
    countBadge.textContent = '0 scanned';
    clearBtn.style.display = 'none';
    if (emailBtn) { emailBtn.style.display = 'none'; emailBtn.disabled = true; }
    lastScannedUsername = null;
    if (emptyState) emptyState.style.display = '';
    setToast('List cleared.', 'text-muted');
});

if (emailBtn) {
    emailBtn.addEventListener('click', () => {
        const eligible = [...scannedData.values()].filter(
            d => !d.isActive && d.daysInCurrentStatus != null && d.daysInCurrentStatus > 15 && d.email,
        );
        if (!eligible.length) {
            setToast('No email addresses available for eligible members.', 'text-warning');
            return;
        }
        const to = eligible.map(d => d.email).join(',');
        window.location.href = `mailto:${to}?subject=${encodeURIComponent('Personal Storage Bin - Membership Review')}`;
    });
}

const scanner = new Html5Qrcode('camera');
const scanConfig = { fps: 10, qrbox: { width: 250, height: 250 } };

async function startCamera() {
    try {
        await scanner.start({ facingMode: { exact: 'environment' } }, scanConfig, onScan, () => {});
    } catch {
        try {
            await scanner.start({ facingMode: 'user' }, scanConfig, onScan, () => {});
        } catch (err) {
            if (String(err).includes('NotAllowedError') || String(err).includes('Permission')) {
                setToast('Camera permission denied. Please allow camera access and reload.', 'text-danger');
            } else {
                setToast(`Camera error: ${err}`, 'text-danger');
            }
            return false;
        }
    }
    return true;
}

startBtn.addEventListener('click', async () => {
    startBtn.disabled = true;
    cameraContainer.style.display = '';
    if (!cameraStarted) {
        const ok = await startCamera();
        if (!ok) { cameraContainer.style.display = 'none'; startBtn.disabled = false; return; }
        cameraStarted = true;
    } else {
        scanner.resume();
    }
    startBtn.style.display = 'none';
    startBtn.disabled = false;
    pauseBtn.style.display = '';
    setToast('Scanning…', 'text-success');
});

pauseBtn.addEventListener('click', () => {
    scanner.pause(true);
    cameraContainer.style.display = 'none';
    pauseBtn.style.display = 'none';
    startBtn.style.display = '';
    setToast('Scanning paused.', 'text-muted');
});

// Restore list from previous session immediately on load.
loadFromStorage();
