// Close-match (typo) and related-term matching over preload results.
const WORD_SPLIT = /[^\p{L}\p{N}]+/u;
const MIN_FUZZY_LENGTH = 4;

export function words(text) {
    return String(text || '').toLowerCase().split(WORD_SPLIT).filter(Boolean);
}

export function maxDistance(length) {
    if (length < MIN_FUZZY_LENGTH) return -1;
    return length <= 6 ? 1 : 2;
}

// Optimal string alignment distance; returns max + 1 as soon as it can't stay within max.
export function distance(a, b, max) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    let prev2 = null;
    let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
    for (let i = 1; i <= a.length; i++) {
        const cur = [i];
        let rowMin = i;
        for (let j = 1; j <= b.length; j++) {
            const cost = a[i - 1] === b[j - 1] ? 0 : 1;
            let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
            if (prev2 && i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
                v = Math.min(v, prev2[j - 2] + 1);
            }
            cur.push(v);
            rowMin = Math.min(rowMin, v);
        }
        if (rowMin > max) return max + 1;
        prev2 = prev;
        prev = cur;
    }
    return prev[b.length];
}

// Distance from a query word to a result word, also against the word's prefix (partly typed words).
function wordDistance(q, w, max) {
    if (w.length < MIN_FUZZY_LENGTH || w.length < q.length - max) return max + 1;
    const full = distance(q, w, max);
    if (w.length <= q.length) return full;
    return Math.min(full, distance(q, w.slice(0, q.length), max));
}

/** Distinct words → item indexes, bucketed by first letter so a typo search scans few words. */
export class WordIndex {
    constructor() {
        this.postings = new Map();
        this.byFirst = new Map();
    }

    add(word, itemIndex) {
        let list = this.postings.get(word);
        if (!list) {
            list = [];
            this.postings.set(word, list);
            const bucket = this.byFirst.get(word[0]) || [];
            bucket.push(word);
            this.byFirst.set(word[0], bucket);
        }
        if (list[list.length - 1] !== itemIndex) list.push(itemIndex);
    }

    has(word) {
        return this.postings.has(word);
    }

    // Typos rarely change the first letter; a swap of the first two letters is also allowed.
    candidates(q) {
        const same = this.byFirst.get(q[0]) || [];
        const swapped = q.length > 1 ? (this.byFirst.get(q[1]) || []).filter(w => w[1] === q[0]) : [];
        return same.concat(swapped);
    }

    /** word → distance for words matching q exactly (as a prefix) or within the allowed distance. */
    near(q) {
        const max = maxDistance(q.length);
        const out = new Map();
        for (const w of this.candidates(q)) {
            if (w.startsWith(q)) out.set(w, 0);
            else if (max >= 0) {
                const d = wordDistance(q, w, max);
                if (d <= max) out.set(w, d);
            }
        }
        return out;
    }
}

/**
 * Items where every query word matches one of their words, at least one only approximately.
 * Returns Map<itemIndex, { score, matched }>.
 */
export function fuzzySearch(index, queryWords) {
    let result = null;
    for (const q of queryWords) {
        const perItem = new Map();
        for (const [w, d] of index.near(q)) {
            for (const i of index.postings.get(w)) {
                const prev = perItem.get(i);
                if (!prev || d < prev.d) perItem.set(i, { d, w });
            }
        }
        if (result === null) {
            result = new Map([...perItem].map(([i, m]) => [i, { worst: m.d, matched: [m.w] }]));
        } else {
            for (const [i, acc] of result) {
                const m = perItem.get(i);
                if (!m) result.delete(i);
                else { acc.worst = Math.max(acc.worst, m.d); acc.matched.push(m.w); }
            }
        }
        if (!result.size) break;
    }
    const out = new Map();
    for (const [i, acc] of result || []) {
        if (acc.worst > 0) out.set(i, { score: 15 - 3 * acc.worst, matched: acc.matched });
    }
    return out;
}

/** Closest indexed words for query words the index doesn't contain (for server-side alt terms). */
export function corrections(queryWords, index, limit = 5) {
    const out = [];
    for (const q of queryWords) {
        if (maxDistance(q.length) < 0 || index.has(q)) continue;
        let best = null;
        let bestD = Infinity;
        for (const [w, d] of index.near(q)) {
            if (d > 0 && d < bestD) { best = w; bestD = d; }
        }
        if (best) out.push(best);
        if (out.length >= limit) break;
    }
    return out;
}

let relatedGroups = [];

export function setRelated(groups) {
    relatedGroups = (Array.isArray(groups) ? groups : [])
        .map(g => (Array.isArray(g) ? g : []).map(t => String(t).toLowerCase().trim()).filter(Boolean))
        .filter(g => g.length > 1);
}

/** Terms related to the query: { term, via } for each other member of a matching group. */
export function relatedTerms(query, limit = 8) {
    const q = ` ${words(query).join(' ')} `;
    if (!q.trim()) return [];
    const out = [];
    const seen = new Set([q.trim()]);
    for (const group of relatedGroups) {
        const hit = group.find(t => q.includes(` ${words(t).join(' ')} `));
        if (!hit) continue;
        for (const t of group) {
            if (t === hit || seen.has(t)) continue;
            seen.add(t);
            out.push({ term: t, via: hit });
            if (out.length >= limit) return out;
        }
    }
    return out;
}
