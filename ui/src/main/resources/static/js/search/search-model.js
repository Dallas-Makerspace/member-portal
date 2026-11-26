// Every source returns results in this shape:
// { type, id, title, subtitle?, imageUrl?, url, external?, badges?: string[], keys: string[], exact?: string[] }
// keys/exact are for matching only and are never displayed or stored.

export function fromPreload(item) {
    switch (item.type) {
        case 'member':
            return {
                type: 'member',
                id: item.username,
                title: item.displayName || item.username,
                subtitle: `@${item.username}`,
                imageUrl: item.avatarUrl || `/avatar/@${encodeURIComponent(item.username)}`,
                url: `/profile/@${encodeURIComponent(item.username)}`,
                badges: item.enabled === false ? ['Inactive'] : [],
                inactive: item.enabled === false,
                keys: [item.displayName, item.username, item.discourseUsername],
                exact: [item.badgeNumber, item.personalEmail, item.phoneNumber],
            };
        case 'group':
            return {
                type: 'group',
                id: item.username,
                title: item.displayName,
                subtitle: item.description || '',
                url: `/groups/${encodeURIComponent(item.username)}`,
                keys: [item.displayName, item.username, item.description],
            };
        case 'course':
            return {
                type: 'course',
                id: item.username,
                title: item.displayName,
                subtitle: item.description || '',
                url: item.url,
                external: true,
                keys: [item.displayName, item.description],
            };
        default:
            return fromGeneric(item);
    }
}

// Fallback for types the client doesn't know yet.
export function fromGeneric(item) {
    return {
        type: item.type || 'other',
        id: String(item.id ?? item.username ?? item.url ?? item.title ?? item.displayName),
        title: item.title || item.displayName || '',
        subtitle: item.subtitle || item.description || '',
        imageUrl: item.imageUrl || item.avatarUrl || null,
        url: item.url || null,
        external: !!item.external,
        badges: Array.isArray(item.badges) ? item.badges : [],
        keys: [item.title, item.displayName, item.subtitle, item.description, ...(item.keywords || [])],
    };
}

export function isSafeUrl(url, external) {
    if (!url) return false;
    if (external) return /^https:\/\//i.test(url);
    return url.startsWith('/') && !url.startsWith('//');
}

// Higher is better; 0 means no match.
export function score(result, q) {
    if (!q) return 0;
    let best = 0;
    for (const e of result.exact || []) {
        if (e && String(e).toLowerCase() === q) best = Math.max(best, 100);
    }
    const keys = result.keys || [];
    keys.forEach((raw, i) => {
        if (!raw) return;
        const k = String(raw).toLowerCase();
        const primary = i === 0 ? 1 : 0;
        let s = 0;
        if (k === q) s = 90;
        else if (k.startsWith(q)) s = 70;
        else if (k.split(/[\s@._\-·]+/).some(w => w.startsWith(q))) s = 50;
        else if (k.includes(q)) s = 20;
        if (s) best = Math.max(best, s + primary * 5 - Math.min(i, 3));
    });
    return best && result.inactive ? best - 15 : best;
}
