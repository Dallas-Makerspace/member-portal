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

export function toolLabel(tool) {
    switch (tool) {
        case 'search_source': return 'Searched the wiki';
        case 'read_source_page': return 'Read a wiki page';
        case 'search_talk': return 'Searched the forum';
        case 'search_calendar': return 'Checked the calendar';
        case 'search_online_courses': return 'Searched classes';
        case 'search_code': return 'Searched the code';
        case 'list_files': return 'Browsed files';
        case 'read_file': return 'Read a file';
        case 'git_log': return 'Checked history';
        case 'git_show': return 'Read a commit';
        case 'git_blame': return 'Checked who changed it';
        case 'query_logs': return 'Searched the logs';
        case 'sync_repos': return 'Refreshed the code';
        case 'create_artifact': return 'Created a document';
        case 'update_artifact': return 'Updated a document';
        case 'read_artifact': return 'Read a document';
        case 'list_artifacts': return 'Listed documents';
        case 'list_learn_courses': return 'Looked up courses';
        default: return 'Used ' + tool.replace(/_/g, ' ');
    }
}
