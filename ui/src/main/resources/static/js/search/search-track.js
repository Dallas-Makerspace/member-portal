// Search usage events, logged through /api/track. Only search_no_results carries query text.
const MAX_QUERY_CHARS = 100;

function send(actionType, fields) {
    const payload = JSON.stringify({ actionType, uri: location.pathname, ...fields });
    try {
        if (navigator.sendBeacon) {
            navigator.sendBeacon('/api/track', new Blob([payload], { type: 'application/json' }));
        } else {
            fetch('/api/track', {
                method: 'POST',
                body: payload,
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                keepalive: true,
            }).catch(() => {});
        }
    } catch { /* tracking is best effort */ }
}

export function trackOpen(trigger) {
    send('search_open', { trigger });
}

export function trackResultClick({ resultType, resultRank, sectionRank, queryLength, resultCount, fromRecents, matchKind }) {
    send('search_result_click', { resultType, resultRank, sectionRank, queryLength, resultCount, fromRecents, matchKind });
}

export function trackNoResults(query) {
    const q = String(query || '').trim().slice(0, MAX_QUERY_CHARS);
    if (q) send('search_no_results', { searchQuery: q, queryLength: q.length });
}

export function trackAskAi({ queryLength, resultCount }) {
    send('search_ask_ai', { queryLength, resultCount });
}
