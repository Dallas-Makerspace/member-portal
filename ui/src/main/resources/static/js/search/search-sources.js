import { fromPreload, fromGeneric, score } from './search-model.js';
import { words, WordIndex, fuzzySearch, corrections, relatedTerms, setRelated } from './search-fuzzy.js';

const PRELOAD_TIMEOUT_MS = 35000;
const FUZZY_BELOW = 8;
const RELATED_MIN_SCORE = 50;
const RELATED_PENALTY = 25;
const REMOTE_MIN_LENGTH = 3;
const REMOTE_CACHE_SIZE = 20;
const REMOTE_SCORE = 40;

export function normalizeQuery(query) {
    return String(query || '').trim().toLowerCase().replace(/^@/, '');
}

// Loads /search-preload once per page and filters it in the browser.
class PreloadSource {
    constructor() {
        this.items = null;
        this.pending = null;
        this.index = new WordIndex();
        this.vocabulary = new WordIndex();
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
                setRelated(json.related);
                const items = (json.data || []).map(fromPreload).filter(r => r.title && r.url);
                items.forEach((r, i) => {
                    for (const w of new Set((r.keys || []).flatMap(words))) {
                        this.index.add(w, i);
                        if (r.type !== 'member') this.vocabulary.add(w, i);
                    }
                });
                this.items = items;
                return items;
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
        const found = new Map();
        for (const r of items) {
            const s = score(r, q);
            if (s > 0) found.set(r, { ...r, score: s, matchKind: 'exact' });
        }
        const exactCount = found.size;
        for (const { term, via } of relatedTerms(q)) {
            for (const r of items) {
                if (r.type === 'member' || found.has(r)) continue;
                const s = score(r, term);
                if (s >= RELATED_MIN_SCORE) {
                    found.set(r, { ...r, score: Math.max(1, s - RELATED_PENALTY), matchKind: 'related', relatedTo: via, hl: [term] });
                }
            }
        }
        if (exactCount < FUZZY_BELOW) {
            for (const [i, m] of fuzzySearch(this.index, words(q))) {
                const r = items[i];
                if (found.has(r)) continue;
                found.set(r, { ...r, score: r.inactive ? m.score - 5 : m.score, matchKind: 'fuzzy', hl: m.matched });
            }
        }
        return [...found.values()];
    }

    /** Spelling fixes and related terms, sent with server-side searches. */
    altTerms(query) {
        const q = normalizeQuery(query);
        const fixes = corrections(words(q), this.vocabulary);
        return [...new Set([...fixes, ...relatedTerms(q).map(r => r.term)])].slice(0, 10);
    }
}

// Server-side results (upcoming classes) from /search.
class RemoteSource {
    constructor() {
        this.cache = new Map();
    }

    eligible(query) {
        return normalizeQuery(query).length >= REMOTE_MIN_LENGTH;
    }

    async search(query, alt, signal) {
        const params = new URLSearchParams({ q: normalizeQuery(query) });
        alt.forEach(a => params.append('alt', a));
        const url = `/search?${params}`;
        if (this.cache.has(url)) return this.cache.get(url);
        const r = await fetch(url, { signal, credentials: 'same-origin', headers: { Accept: 'application/json' } });
        if (!r.ok || r.redirected) throw new Error(`HTTP ${r.status}`);
        const json = await r.json();
        const results = (json.data || []).map(fromGeneric).filter(x => x.title && x.url)
            .map(x => ({ ...x, score: REMOTE_SCORE, matchKind: 'remote' }));
        this.cache.set(url, results);
        if (this.cache.size > REMOTE_CACHE_SIZE) this.cache.delete(this.cache.keys().next().value);
        return results;
    }
}

export const preloadSource = new PreloadSource();
export const remoteSource = new RemoteSource();

export function sortResults(list) {
    return list.sort((a, b) => (b.score || 0) - (a.score || 0) || a.title.localeCompare(b.title));
}

/** Merge by type + id, keeping the higher score. */
export function mergeResults(...lists) {
    const merged = new Map();
    for (const r of lists.flat()) {
        const key = `${r.type}:${r.id}`;
        const prev = merged.get(key);
        if (!prev || (r.score || 0) > (prev.score || 0)) merged.set(key, r);
    }
    return sortResults([...merged.values()]);
}
