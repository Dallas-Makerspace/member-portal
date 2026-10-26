// Markdown to sanitized HTML for chat messages. Needs the marked and DOMPurify globals.
let hooked = false;

function ensureHook() {
    if (hooked) return;
    hooked = true;
    window.DOMPurify.addHook('afterSanitizeAttributes', function (node) {
        if (node.tagName !== 'A') return;
        node.setAttribute('rel', 'noopener noreferrer');
        const href = node.getAttribute('href') || '';
        if (!href.startsWith('/') && !href.startsWith('#')) node.setAttribute('target', '_blank');
    });
}

export function renderMarkdown(text) {
    ensureHook();
    let source = (text || '').replace(/\u00ab\d+\u00bb/g, '');
    if ((source.match(/```/g) || []).length % 2 === 1) source += '\n```';
    const html = window.marked.parse(source, {gfm: true, breaks: true});
    return window.DOMPurify.sanitize(html, {
        FORBID_TAGS: ['img', 'style', 'form', 'input', 'iframe'],
        ADD_ATTR: ['target'],
    });
}

export function toolLabel(tool, argsJson) {
    let query = '';
    try {
        query = (JSON.parse(argsJson || '{}').query || '').toString().slice(0, 60);
    } catch (e) { /* partial args while streaming */ }
    const quoted = query ? ' for “' + query + '”' : '';
    switch (tool) {
        case 'search_source': return 'Searched Source' + quoted;
        case 'read_source_page': return 'Read a Source page';
        case 'search_talk': return 'Searched Talk' + quoted;
        case 'search_calendar': return 'Checked the calendar' + quoted;
        case 'search_online_courses': return 'Searched DMS Learn' + quoted;
        default: return 'Used ' + tool.replace(/_/g, ' ');
    }
}
