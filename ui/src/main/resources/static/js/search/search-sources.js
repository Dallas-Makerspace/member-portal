import { fromPreload, score } from './search-model.js';

const PRELOAD_TIMEOUT_MS = 35000;

// Loads /search-preload once per page and filters it in the browser.
class PreloadSource {
    constructor() {
        this.id = 'preload';
        this.items = null;
        this.pending = null;
    }

    get ready() {
        return this.items !== null;
    }

    load() {
        if (this.items) return Promise.resolve(this.items);
        if (this.pending) return this.pending;
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), PRELOAD_TIMEOUT_MS);
        this.pending = fetch('/search-preload', {
            signal: ctrl.signal,
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        })
            .then(r => {
                if (!r.ok || r.redirected) throw new Error(`HTTP ${r.status}`);
                return r.json();
            })
            .then(json => {
                this.items = (json.data || []).map(fromPreload).filter(r => r.title && r.url);
                return this.items;
            })
            .finally(() => {
                clearTimeout(timer);
                this.pending = null;
            });
        return this.pending;
    }

    types() {
        return new Set((this.items || []).map(r => r.type));
    }

    async search(query) {
        const items = await this.load();
        const q = normalizeQuery(query);
        if (!q) return [];
        const out = [];
        for (const r of items) {
            const s = score(r, q);
            if (s > 0) out.push({ ...r, score: s });
        }
        return out;
    }
}

export function normalizeQuery(query) {
    return String(query || '').trim().toLowerCase().replace(/^@/, '');
}

export const preloadSource = new PreloadSource();

// Add a server-side source here later; results are merged by type + id.
export const sources = [preloadSource];

export async function searchAll(query, signal) {
    const lists = await Promise.all(sources.map(s => s.search(query, signal)));
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const merged = new Map();
    for (const r of lists.flat()) {
        const key = `${r.type}:${r.id}`;
        const prev = merged.get(key);
        if (!prev || (r.score || 0) > (prev.score || 0)) merged.set(key, r);
    }
    return [...merged.values()].sort(
        (a, b) => (b.score || 0) - (a.score || 0) || a.title.localeCompare(b.title));
}
