import {LitElement, html} from 'https://esm.sh/lit@3.3.0';
import {unsafeHTML} from 'https://esm.sh/lit@3.3.0/directives/unsafe-html.js';
import {renderMarkdown, toolLabel} from './markdown.js';

const SOURCE_ICONS = {Source: 'description', Talk: 'forum', Calendar: 'event', 'DMS Learn': 'school', Code: 'code'};

/**
 * One chat message. Properties: message {id, role, text, toolCalls, sources, feedback},
 * streaming (bool), canRate (bool). Fires `rate` with {messageId, helpful}.
 */
class DmsChatMessage extends LitElement {
    static properties = {
        message: {type: Object},
        streaming: {type: Boolean},
        canRate: {type: Boolean},
        _html: {state: true},
        _copied: {state: true},
    };

    createRenderRoot() {
        return this;
    }

    constructor() {
        super();
        this._frame = null;
        this._copied = false;
    }

    willUpdate(changed) {
        if (!changed.has('message')) return;
        if (!this.streaming) {
            this._html = renderMarkdown(this.message?.text);
        } else if (this._frame === null) {
            this._frame = requestAnimationFrame(() => {
                this._frame = null;
                this._html = renderMarkdown(this.message?.text);
            });
        }
    }

    _rate(helpful) {
        this.dispatchEvent(new CustomEvent('rate', {
            detail: {messageId: this.message.id, helpful},
            bubbles: true,
        }));
    }

    _copy() {
        navigator.clipboard?.writeText(this.message.text || '');
        this._copied = true;
        setTimeout(() => { this._copied = false; }, 1500);
    }

    _activity(calls) {
        if (!calls || !calls.length) return '';
        return html`
            <div class="chat-tools" aria-label="What the assistant looked up">
                ${calls.map(c => html`
                    <span class="chat-tool-chip ${c.ok === false ? 'failed' : ''} ${c.running ? 'running' : ''}">
                        <span class="material-symbols-outlined" aria-hidden="true">${c.running ? 'progress_activity' : 'search'}</span>
                        <span class="chat-tool-label">${toolLabel(c.name, c.argsPreview)}</span>
                    </span>`)}
            </div>`;
    }

    _host(url) {
        try {
            return new URL(url).hostname.replace(/^www\./, '');
        } catch (e) {
            return '';
        }
    }

    _references(sources) {
        if (!sources || !sources.length) return '';
        return html`
            <div class="chat-refs">
                <div class="chat-refs-label">Sources</div>
                <div class="chat-refs-list">
                    ${sources.map(s => html`
                        <a class="chat-ref" href=${s.url} target="_blank" rel="noopener noreferrer" title=${s.title}>
                            <span class="material-symbols-outlined" aria-hidden="true">${SOURCE_ICONS[s.source] || 'link'}</span>
                            <span class="chat-ref-text">
                                <span class="chat-ref-title">${s.title}</span>
                                <span class="chat-ref-source">${this._host(s.url) || s.source}</span>
                            </span>
                        </a>`)}
                </div>
            </div>`;
    }

    _actions(m) {
        const rated = m.feedback;
        return html`
            <div class="chat-actions">
                ${this.canRate && m.id ? html`
                    <button class="btn btn-link ${rated === true ? 'active' : ''}" aria-pressed=${rated === true}
                            title="Helpful" @click=${() => this._rate(true)}>
                        <span class="material-symbols-outlined" aria-hidden="true">thumb_up</span>
                        <span class="chat-action-label">Helpful</span></button>
                    <button class="btn btn-link ${rated === false ? 'active' : ''}" aria-pressed=${rated === false}
                            title="Not helpful" @click=${() => this._rate(false)}>
                        <span class="material-symbols-outlined" aria-hidden="true">thumb_down</span>
                        <span class="chat-action-label">Not helpful</span></button>` : ''}
                <button class="btn btn-link" title="Copy answer" @click=${this._copy}>
                    <span class="material-symbols-outlined" aria-hidden="true">${this._copied ? 'check' : 'content_copy'}</span>
                    <span class="chat-action-label">${this._copied ? 'Copied' : 'Copy'}</span></button>
            </div>`;
    }

    render() {
        const m = this.message;
        if (!m) return html``;
        if (m.role === 'user') {
            return html`<div class="chat-row user"><div class="chat-bubble user">${m.text}</div></div>`;
        }
        return html`
            <div class="chat-row assistant">
                <span class="chat-ai-mark" aria-hidden="true">AI</span>
                <div class="chat-answer" aria-live=${this.streaming ? 'polite' : 'off'}>
                    ${this._activity(m.toolCalls)}
                    ${m.text
                        ? html`<div class="chat-markdown">${unsafeHTML(this._html || '')}</div>`
                        : (this.streaming ? html`<span class="chat-thinking">Thinking…</span>` : '')}
                    ${!this.streaming ? this._references(m.sources) : ''}
                    ${!this.streaming && m.text ? this._actions(m) : ''}
                </div>
            </div>`;
    }
}

customElements.define('dms-chat-message', DmsChatMessage);
