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

const TOOL_ICONS = {
    search_source: 'manage_search', read_source_page: 'article', search_talk: 'forum', read_talk_topic: 'chat',
    search_calendar: 'event', search_online_courses: 'school', search_code: 'code', list_files: 'folder_open',
    read_file: 'draft', git_log: 'history', git_show: 'commit', git_blame: 'person_search',
    query_logs: 'receipt_long', sync_repos: 'sync', create_artifact: 'note_add', update_artifact: 'edit_note',
    read_artifact: 'description', list_artifacts: 'library_books', list_learn_courses: 'local_library',
    get_user_access: 'badge', search_user: 'person_search', get_authorization_path: 'verified_user',
    get_committees: 'groups', get_site_config: 'toggle_on', get_audit_trail: 'manage_history',
};

/** A Material Symbols name that tells tools apart at a glance. */
export function toolIcon(tool) {
    return TOOL_ICONS[tool] || 'build';
}

const HINT_KEYS = ['query', 'pattern', 'text', 'path', 'url', 'title', 'topicId', 'id', 'repo', 'topic', 'username', 'group', 'search', 'name'];

/** The main argument of a call as a short phrase, with emails and phone numbers hidden. */
export function toolHint(argsJson) {
    let args;
    try {
        args = JSON.parse(argsJson || '{}');
    } catch (e) {
        return '';
    }
    const key = HINT_KEYS.find(k => args[k] !== undefined && String(args[k]).trim() !== '');
    if (!key) return '';
    let shown = String(args[key]);
    if (key === 'url') {
        try {
            shown = new URL(shown).pathname;
        } catch (e) { /* keep as typed */ }
    }
    const value = shown.replace(/[^\s@]+@[^\s@]+\.[^\s@]+/g, '\u2026').replace(/\+?\d[\d\s().-]{7,}\d/g, '\u2026');
    return value.length > 60 ? value.slice(0, 59) + '\u2026' : value;
}

export function toolLabel(tool) {
    switch (tool) {
        case 'search_source': return 'Searched Source';
        case 'read_source_page': return 'Read a Source page';
        case 'search_talk': return 'Searched the forum';
        case 'read_talk_topic': return 'Read a forum topic';
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
        case 'get_user_access': return 'Checked access';
        case 'search_user': return 'Searched members';
        case 'get_authorization_path': return 'Looked up how to get authorized';
        case 'get_committees': return 'Looked up committees';
        case 'get_site_config': return 'Checked site settings';
        case 'get_audit_trail': return 'Checked change history';
        default: return 'Used ' + tool.replace(/_/g, ' ');
    }
}
