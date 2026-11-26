const MAX_RECENTS = 6;
const NEW_TIP_DAYS = 90;
const NEW_TIP_VIEWS = 3;

function recentsKey(username) {
    return `dms.search.recents:${username || 'anon'}`;
}

function read(key, fallback) {
    try {
        const v = JSON.parse(localStorage.getItem(key));
        return v ?? fallback;
    } catch {
        return fallback;
    }
}

function write(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
    } catch { /* storage full or blocked */ }
}

export function getRecents(username) {
    const list = read(recentsKey(username), []);
    return Array.isArray(list) ? list.filter(r => r && r.type && r.id && r.title && r.url) : [];
}

export function addRecent(username, r) {
    const entry = {
        type: r.type,
        id: r.id,
        title: r.title,
        subtitle: r.subtitle || '',
        imageUrl: r.imageUrl || null,
        url: r.url,
        external: !!r.external,
    };
    const rest = getRecents(username).filter(x => !(x.type === entry.type && x.id === entry.id));
    write(recentsKey(username), [entry, ...rest].slice(0, MAX_RECENTS));
}

export function clearRecents(username) {
    try {
        localStorage.removeItem(recentsKey(username));
    } catch { /* ignore */ }
}

// A type's tip shows "New" for its first few views within NEW_TIP_DAYS of `since`.
export function isNewTip(type, since) {
    if (!since) return false;
    const age = (Date.now() - Date.parse(since)) / 86400000;
    if (!(age >= 0 && age <= NEW_TIP_DAYS)) return false;
    const seen = read('dms.search.tipsSeen', {});
    return (seen[type] || 0) < NEW_TIP_VIEWS;
}

export function markTipsSeen(types) {
    const seen = read('dms.search.tipsSeen', {});
    for (const t of types) seen[t] = (seen[t] || 0) + 1;
    write('dms.search.tipsSeen', seen);
}
