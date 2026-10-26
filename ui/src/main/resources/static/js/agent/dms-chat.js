import {LitElement, html} from 'https://esm.sh/lit@3.3.0';
import './dms-chat-message.js';

const API = '/api/agent';
const MAX_CHARS = 4000;

async function api(method, path, body) {
    const res = await fetch(API + path, {
        method,
        credentials: 'same-origin',
        headers: body ? {'Content-Type': 'application/json'} : {},
        body: body ? JSON.stringify(body) : undefined,
    });
    let json = null;
    try {
        json = await res.json();
    } catch (e) { /* empty body */ }
    return {ok: res.ok, status: res.status, json, data: json && json.data, message: json && json.message};
}

/**
 * Multi-turn assistant chat. Attributes: bot (bot key), bot-title.
 * Talks to /api/agent: a POST starts a turn, then an event stream carries it to the end.
 */
class DmsChat extends LitElement {
    static properties = {
        bot: {type: String},
        botTitle: {type: String, attribute: 'bot-title'},
        _conversation: {state: true},
        _messages: {state: true},
        _live: {state: true},
        _running: {state: true},
        _busy: {state: true},
        _error: {state: true},
        _starters: {state: true},
        _mine: {state: true},
        _public: {state: true},
        _scope: {state: true},
        _draft: {state: true},
        _loading: {state: true},
        _drawer: {state: true},
    };

    createRenderRoot() {
        return this;
    }

    constructor() {
        super();
        this._conversation = null;
        this._messages = [];
        this._live = null;
        this._running = false;
        this._busy = false;
        this._error = '';
        this._starters = [];
        this._mine = [];
        this._public = [];
        this._scope = 'mine';
        this._draft = '';
        this._loading = false;
        this._drawer = false;
        this._source = null;
        this._retries = 0;
    }

    _fit() {
        const top = this.getBoundingClientRect().top + window.scrollY;
        this.style.setProperty('--chat-top', Math.round(top) + 'px');
    }

    async connectedCallback() {
        super.connectedCallback();
        this._fit();
        window.addEventListener('resize', () => this._fit());
        window.addEventListener('popstate', () => this._openFromUrl());
        const bots = await api('GET', '/bots');
        const me = bots.ok && (bots.data || []).find(b => b.key === this.bot);
        if (!me) {
            this._error = bots.ok
                ? 'This assistant is not available to you.'
                : (bots.message || 'The assistant is unavailable right now.');
            return;
        }
        this._starters = me.starterPrompts || [];
        this._loadHistory();
        this._openFromUrl();
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        this._closeStream();
    }

    async _loadHistory() {
        const [mine, shared] = await Promise.all([
            api('GET', `/${this.bot}/conversations?scope=mine&limit=30`),
            api('GET', `/${this.bot}/conversations?scope=public&limit=15`),
        ]);
        if (mine.ok) this._mine = mine.data || [];
        if (shared.ok) this._public = shared.data || [];
    }

    _openFromUrl() {
        const id = new URLSearchParams(location.search).get('c');
        if (id && id !== this._conversation?.id) this._open(id, false);
        if (!id && this._conversation) this._reset(false);
    }

    _setUrl(id) {
        const url = id ? `${location.pathname}?c=${encodeURIComponent(id)}` : location.pathname;
        history.pushState({}, '', url);
    }

    _reset(pushUrl = true) {
        this._closeStream();
        this._conversation = null;
        this._messages = [];
        this._live = null;
        this._running = false;
        this._busy = false;
        this._error = '';
        if (pushUrl) this._setUrl(null);
    }

    async _open(id, pushUrl = true) {
        this._closeStream();
        this._loading = true;
        this._error = '';
        const res = await api('GET', `/conversations/${encodeURIComponent(id)}`);
        this._loading = false;
        if (!res.ok) {
            this._reset(false);
            this._error = res.status === 404 ? 'That chat was not found, or it is private.' : (res.message || 'Could not load the chat.');
            return;
        }
        this._apply(res.data);
        if (pushUrl) this._setUrl(id);
        this._drawer = false;
        if (res.data.activeTurn && res.data.isOwner) this._startStream(id);
        this._showLatestQuestion();
    }

    _apply(detail) {
        this._conversation = {...detail.conversation, privateReason: detail.privateReason, isOwner: detail.isOwner};
        this._messages = detail.messages;
        this._live = null;
        this._running = !!detail.activeTurn;
    }

    async _send(text) {
        text = (text || '').trim();
        if (!text || this._running) return;
        if (text.length > MAX_CHARS) {
            this._error = `Please keep questions under ${MAX_CHARS} characters.`;
            return;
        }
        this._error = '';
        const fresh = !this._conversation;
        const res = fresh
            ? await api('POST', `/${this.bot}/conversations`, {firstMessage: text})
            : await api('POST', `/conversations/${this._conversation.id}/messages`, {text});
        if (!res.ok) {
            this._error = res.message || 'Could not send your question.';
            return;
        }
        this._draft = '';
        const id = res.data.conversationId;
        this._messages = [...this._messages, {id: null, role: 'user', text}];
        this._live = {id: null, role: 'assistant', text: '', toolCalls: [], sources: []};
        this._running = true;
        if (fresh) {
            this._conversation = {id, title: text, visibility: 'PUBLIC', isOwner: true};
            this._setUrl(id);
        }
        this._startStream(id);
        this._scrollDown();
    }

    _startStream(id) {
        this._closeStream();
        // The stream replays the whole turn, so start from empty.
        this._live = {id: null, role: 'assistant', text: '', toolCalls: [], sources: []};
        this._running = true;
        const source = new EventSource(`${API}/conversations/${encodeURIComponent(id)}/stream`);
        this._source = source;
        const on = (type, fn) => source.addEventListener(type, ev => {
            try {
                fn(JSON.parse(ev.data));
            } catch (e) { /* ignore malformed event */ }
        });
        const live = () => this._live;
        const touch = () => {
            this._live = {...this._live};
            this._scrollDown();
        };
        on('delta', e => { live().text += e.text || ''; touch(); });
        on('tool-start', e => {
            live().toolCalls = [...live().toolCalls, {name: e.tool, argsPreview: e.argsPreview, running: true}];
            touch();
        });
        on('tool-end', e => {
            const calls = live().toolCalls.slice();
            for (let i = calls.length - 1; i >= 0; i--) {
                if (calls[i].name === e.tool && calls[i].running) {
                    calls[i] = {...calls[i], running: false, ok: e.ok};
                    break;
                }
            }
            live().toolCalls = calls;
            touch();
        });
        on('sources', e => { live().sources = e.sources || []; touch(); });
        on('visibility', () => {
            this._conversation = {...this._conversation, visibility: 'PRIVATE'};
            this._notice = 'This chat is now private';
            setTimeout(() => { this._notice = ''; this.requestUpdate(); }, 6000);
        });
        on('busy', () => { this._busy = true; });
        on('error', e => { this._error = e.text || 'Something went wrong.'; });
        on('turn-end', () => this._finishTurn(id));
        source.onerror = () => {
            if (!this._source || source.readyState !== EventSource.CLOSED) return;
            this._closeStream();
            this._recover(id);
        };
        source.addEventListener('stream-error', () => {
            this._closeStream();
            this._recover(id);
        });
    }

    async _recover(id) {
        if (this._retries++ > 3) {
            this._retries = 0;
            this._error = 'Lost the connection. Reload the page to see the answer.';
            this._running = false;
            return;
        }
        await new Promise(r => setTimeout(r, 1000 * this._retries));
        const res = await api('GET', `/conversations/${encodeURIComponent(id)}`);
        if (!res.ok) return;
        if (res.data.activeTurn) this._startStream(id);
        else this._apply(res.data);
    }

    async _finishTurn(id) {
        this._closeStream();
        this._retries = 0;
        this._busy = false;
        const res = await api('GET', `/conversations/${encodeURIComponent(id)}`);
        if (res.ok) this._apply(res.data);
        else this._running = false;
        this._loadHistory();
        this._showLatestQuestion();
    }

    /** After an answer, put the latest question at the top so it stays in view with its answer. */
    _showLatestQuestion() {
        this.updateComplete.then(() => requestAnimationFrame(() => {
            const box = this.querySelector('.chat-scroll');
            const questions = this.querySelectorAll('.chat-row.user');
            const last = questions[questions.length - 1];
            if (!box || !last) return;
            const offset = last.getBoundingClientRect().top - box.getBoundingClientRect().top;
            box.scrollTop += offset - 16;
        }));
    }

    _closeStream() {
        if (this._source) {
            this._source.close();
            this._source = null;
        }
    }

    async _stop() {
        if (this._conversation) await api('POST', `/conversations/${this._conversation.id}/cancel`);
    }

    async _rate(ev) {
        const {messageId, helpful} = ev.detail;
        const res = await api('POST', `/conversations/${this._conversation.id}/feedback`, {messageId, helpful});
        if (res.ok) this._messages = this._messages.map(m => m.id === messageId ? {...m, feedback: helpful} : m);
    }

    async _delete() {
        if (!this._conversation) return;
        const shared = this._conversation.visibility !== 'PRIVATE'
            ? ' It will also disappear for other members.' : '';
        if (!confirm(`Delete \u201c${this._conversation.title}\u201d?${shared} This cannot be undone.`)) return;
        const res = await api('DELETE', `/conversations/${this._conversation.id}`);
        if (res.ok) {
            this._reset();
            this._loadHistory();
        }
    }

    _share() {
        const url = `${location.origin}${location.pathname}?c=${this._conversation.id}`;
        navigator.clipboard?.writeText(url);
        this._error = '';
        this._notice = 'Link copied';
        this.requestUpdate();
        setTimeout(() => { this._notice = ''; this.requestUpdate(); }, 2000);
    }

    _scrollDown() {
        this.updateComplete.then(() => {
            const box = this.querySelector('.chat-scroll');
            if (box) box.scrollTop = box.scrollHeight;
        });
    }

    _keydown(ev) {
        if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) {
            ev.preventDefault();
            this._send(this._draft);
        }
    }

    _input(ev) {
        this._draft = ev.target.value;
        ev.target.style.height = 'auto';
        ev.target.style.height = Math.min(ev.target.scrollHeight, 200) + 'px';
    }

    _groupHistory(list) {
        const startOfDay = d => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
        const today = startOfDay(new Date());
        const groups = [];
        for (const c of list) {
            const days = Math.round((today - startOfDay(new Date(c.lastMessageAt))) / 86400000);
            const label = days <= 0 ? 'Today' : days <= 7 ? 'Previous 7 days' : days <= 30 ? 'Previous 30 days' : 'Older';
            let group = groups.find(g => g.label === label);
            if (!group) groups.push(group = {label, items: []});
            group.items.push(c);
        }
        return groups;
    }

    _ago(iso) {
        const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
        if (mins < 1) return 'just now';
        if (mins < 60) return `${mins}m ago`;
        if (mins < 1440) return `${Math.round(mins / 60)}h ago`;
        return `${Math.round(mins / 1440)}d ago`;
    }

    _history() {
        const all = this._scope === 'mine' ? this._mine : this._public;
        const needle = (this._search || '').trim().toLowerCase();
        const list = needle ? all.filter(c => c.title.toLowerCase().includes(needle)) : all;
        return html`
            <div class="chat-side">
                <div class="chat-drawer-head d-md-none">
                    <strong>Chats</strong>
                    <button class="btn btn-outline-secondary chat-close" aria-label="Close chat list" title="Close"
                            @click=${() => { this._drawer = false; }}>
                        <span class="material-symbols-outlined" aria-hidden="true">close</span></button>
                </div>
                <button class="btn btn-primary w-100 mb-3 chat-new" @click=${() => { this._reset(); this._drawer = false; }}>
                    <span class="material-symbols-outlined" aria-hidden="true">add</span> New chat</button>
                <label class="chat-search">
                    <span class="material-symbols-outlined" aria-hidden="true">search</span>
                    <input type="search" placeholder="Search chats" aria-label="Search chats" .value=${this._search || ''}
                           @input=${e => { this._search = e.target.value; this.requestUpdate(); }}>
                </label>
                <div class="chat-tabs" role="tablist" aria-label="Which chats">
                    <button role="tab" aria-selected=${this._scope === 'mine'} class=${this._scope === 'mine' ? 'active' : ''}
                            @click=${() => { this._scope = 'mine'; }}>My chats</button>
                    <button role="tab" aria-selected=${this._scope === 'public'} class=${this._scope === 'public' ? 'active' : ''}
                            @click=${() => { this._scope = 'public'; }}>From members</button>
                </div>
                ${list.length === 0 ? html`<p class="text-muted small mt-3 px-1">${needle
                    ? 'No matches.' : this._scope === 'mine' ? 'No chats yet.' : 'Nothing shared yet.'}</p>` : ''}
                ${this._groupHistory(list).map(g => html`
                    <div class="chat-group-label">${g.label}</div>
                    <ul class="list-unstyled chat-history">
                        ${g.items.map(c => html`
                            <li><a href=${'?c=' + c.id} title=${c.title}
                                   class=${c.id === this._conversation?.id ? 'active' : ''}
                                   @click=${e => { e.preventDefault(); this._open(c.id); }}>
                                <span class="chat-history-title">${c.title}</span>
                                <span class="chat-history-meta">
                                    ${c.visibility === 'PRIVATE'
                                        ? html`<span class="material-symbols-outlined" aria-label="Private chat">lock</span>`
                                        : html`<span class="material-symbols-outlined" aria-label="Shared with members">groups</span>`}
                                    ${this._ago(c.lastMessageAt)}</span></a></li>`)}
                    </ul>`)}
            </div>`;
    }

    _header() {
        const c = this._conversation;
        const isPrivate = c?.visibility === 'PRIVATE';
        return html`
            <div class="chat-head ${c ? '' : 'bare'}">
                <button class="btn btn-outline-secondary d-md-none chat-chats-btn" @click=${() => { this._drawer = !this._drawer; }}
                        aria-expanded=${this._drawer} aria-label="Chat list" title="Chat list">
                    <span class="material-symbols-outlined" aria-hidden="true">forum</span>
                    <span class="chat-chats-label">Chats</span></button>
                ${c ? html`<h1 class="chat-title" title=${c.title}>${c.title}</h1>
                    <span class="chat-privacy ${isPrivate ? 'private' : ''}"
                          aria-label=${isPrivate ? 'Only you can see this chat' : 'Visible to all members'}
                          title=${isPrivate ? (c.privateReason || 'Only you can see this chat') : 'Any member allowed to use this assistant can read this chat'}>
                        <span class="material-symbols-outlined" aria-hidden="true">${isPrivate ? 'lock' : 'groups'}</span>
                        <span class="chat-privacy-long">${isPrivate ? 'Private' : 'Visible to all members'}</span>
                        <span class="chat-privacy-short">${isPrivate ? 'Private' : 'Shared'}</span></span>`
                    : ''}
                <span class="flex-grow-1"></span>
                ${this._notice ? html`<span class="small text-success" role="status">${this._notice}</span>` : ''}
                ${c ? html`<button class="btn chat-newmini d-md-none" aria-label="New chat" title="New chat"
                        @click=${() => { this._reset(); }}>
                    <span class="material-symbols-outlined" aria-hidden="true">edit_square</span></button>` : ''}
                ${c?.isOwner ? html`
                    <div class="dropdown">
                        <button class="btn btn-outline-secondary chat-menu-btn" data-bs-toggle="dropdown"
                                aria-expanded="false" aria-label="Chat actions" title="Chat actions">
                            <span class="material-symbols-outlined" aria-hidden="true">more_vert</span></button>
                        <ul class="dropdown-menu dropdown-menu-end">
                            ${!isPrivate ? html`<li><button class="dropdown-item" @click=${this._share}>Copy link to this chat</button></li>` : ''}
                            ${!isPrivate ? html`<li><hr class="dropdown-divider"></li>` : ''}
                            <li><button class="dropdown-item chat-delete" @click=${this._delete}>Delete chat…</button></li>
                        </ul>
                    </div>` : ''}
            </div>`;
    }

    _composer(large) {
        return html`
            <form class="chat-composer ${large ? 'large' : ''}" @submit=${e => { e.preventDefault(); this._send(this._draft); }}>
                <textarea rows="1" maxlength=${MAX_CHARS} .value=${this._draft}
                          placeholder="Ask a question about the space…" aria-label="Your question"
                          @input=${this._input} @keydown=${this._keydown}></textarea>
                ${this._running
                    ? html`<button type="button" class="chat-send stop" aria-label="Stop answering" title="Stop" @click=${this._stop}>
                        <span class="material-symbols-outlined" aria-hidden="true">stop</span></button>`
                    : html`<button type="submit" class="chat-send" aria-label="Send" title="Send" ?disabled=${!this._draft.trim()}>
                        <span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span></button>`}
            </form>
            ${large ? html`<div class="chat-disclaimer">
                <span class="material-symbols-outlined" aria-hidden="true">groups</span>
                <span>Chats are visible to other members unless they include personal details.</span>
            </div>` : ''}`;
    }

    _empty() {
        return html`
            <div class="chat-empty">
                <h1>${this.botTitle || 'Assistant'}</h1>
                <p>Ask about policies, tools, classes and how things work.</p>
                ${this._composer(true)}
                <div class="chat-starters">
                    ${this._starters.map(s => html`<button type="button" @click=${() => this._send(s)}>${s}</button>`)}
                </div>
            </div>`;
    }

    render() {
        const readOnly = this._conversation && !this._conversation.isOwner;
        const empty = !this._loading && !this._conversation && !this._messages.length;
        return html`
            <div class="chat-layout">
                <aside class="chat-aside ${this._drawer ? 'open' : ''}">${this._history()}</aside>
                <section class="chat-main">
                    ${this._header()}
                    <div class="chat-scroll" aria-busy=${this._running}>
                        <div class="chat-thread">
                        ${this._loading ? html`<div class="text-muted p-3">Loading…</div>` : ''}
                        ${empty ? this._empty() : ''}
                        ${this._messages.map(m => html`
                            <dms-chat-message .message=${m} ?canRate=${!!m.id && m.role === 'assistant'}
                                              @rate=${this._rate}></dms-chat-message>`)}
                        ${this._live ? html`<dms-chat-message .message=${this._live} streaming></dms-chat-message>` : ''}
                        </div>
                    </div>
                    <div class="chat-foot">
                    ${this._busy ? html`<div class="alert alert-warning py-1 mb-2">Lots of questions right now - yours is queued.</div>` : ''}
                    ${this._error ? html`<div class="alert alert-danger py-1 mb-2" role="alert">${this._error}</div>` : ''}
                    ${readOnly ? html`<div class="chat-note p-2">You are reading another member's chat.
                        <a href="#" @click=${e => { e.preventDefault(); this._reset(); }}>Start your own</a></div>`
                        : (empty ? '' : this._composer(false))}
                    </div>
                </section>
            </div>`;
    }
}

customElements.define('dms-chat', DmsChat);
