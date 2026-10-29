import {LitElement, html} from 'https://esm.sh/lit@3.3.0';
import {unsafeHTML} from 'https://esm.sh/lit@3.3.0/directives/unsafe-html.js';
import {renderMarkdown, toolIcon, toolHint, toolLabel} from './markdown.js';

function formatDuration(ms) {
    if (ms >= 60000) return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
    return `${(ms / 1000).toFixed(1)} s`;
}

function formatCount(n) {
    return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

function formatCost(usd) {
    return '$' + (usd < 0.1 ? usd.toFixed(4) : usd.toFixed(2));
}

/** "us.anthropic.claude-haiku-5-5" becomes "Haiku 5.5"; other ids lose only their region prefix. */
function modelName(id) {
    const claude = /claude-([a-z]+)-(\d+)-(\d+)/.exec(id);
    if (claude) return `${claude[1][0].toUpperCase()}${claude[1].slice(1)} ${claude[2]}.${claude[3]}`;
    return id.replace(/^[a-z]{2}\.(openai\.)?/, '');
}

const SOURCE_ICONS ={Source: 'description', Talk: 'forum', Calendar: 'event', 'DMS Learn': 'school', Code: 'code'};

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
        _open: {state: true},
    };

    createRenderRoot() {
        return this;
    }

    constructor() {
        super();
        this._frame = null;
        this._copied = false;
        this._open = false;
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

    /**
     * While the assistant is still working: one line that follows the current call. Afterwards a
     * collapsed "Used N tools" that opens into the full list.
     */
    _activity(calls, working) {
        const meta = this.message?.meta;
        if ((!calls || !calls.length) && (working || !meta)) return '';
        calls = calls || [];
        if (working) {
            const current = calls.slice().reverse().find(x => x.running) || calls[calls.length - 1];
            const hint = toolHint(current.argsPreview);
            return html`
                <div class="chat-activity live" role="status">
                    <span class="material-symbols-outlined ${current.running ? 'spin' : ''}" aria-hidden="true">${current.running ? 'progress_activity' : toolIcon(current.name)}</span>
                    <span class="chat-activity-text">${toolLabel(current.name)}${hint ? html` <span class="chat-activity-hint">\u00b7 ${hint}</span>` : ''}</span>
                </div>`;
        }
        const failed = calls.filter(x => x.ok === false).length;
        const icons = [...new Set(calls.map(x => x.name))].slice(0, 5);
        return html`
            <div class="chat-activity">
                <button type="button" class="chat-activity-toggle" aria-expanded=${this._open} @click=${() => { this._open = !this._open; }}>
                    <span class="material-symbols-outlined chevron" aria-hidden="true">${this._open ? 'expand_more' : 'chevron_right'}</span>
                    <span>${calls.length ? `Used ${calls.length} ${calls.length === 1 ? 'tool' : 'tools'}` : 'Details'}</span>
                    <span class="chat-activity-icons" aria-hidden="true">${icons.map(n => html`<span class="material-symbols-outlined">${toolIcon(n)}</span>`)}</span>
                    ${failed ? html`<span class="chat-activity-failed">\u00b7 ${failed} failed</span>` : ''}
                </button>
                ${this._open ? html`
                    ${calls.length ? html`
                        <ul class="chat-activity-list">
                            ${calls.map(x => html`
                                <li class=${x.ok === false ? 'failed' : ''}>
                                    <span class="material-symbols-outlined" aria-hidden="true">${toolIcon(x.name)}</span>
                                    <span class="chat-activity-text">${toolLabel(x.name)}${toolHint(x.argsPreview) ? html` <span class="chat-activity-hint">\u00b7 ${toolHint(x.argsPreview)}</span>` : ''}</span>
                                    ${x.durationMs ? html`<span class="chat-activity-time">${formatDuration(x.durationMs)}</span>` : ''}
                                    ${x.ok === false ? html`<span class="chat-activity-failed">failed</span>` : ''}
                                </li>`)}
                        </ul>` : ''}
                    ${this._details(meta)}` : ''}
            </div>`;
    }

    /** How the answer was made: time, model, tokens, cost and where the sources came from. */
    _details(meta) {
        if (!meta) return '';
        const total = meta.inputTokens + meta.cacheReadTokens + meta.cacheWriteTokens;
        const cached = total ? Math.round(100 * meta.cacheReadTokens / total) : 0;
        const bySource = {};
        (this.message.sources || []).forEach(s => { bySource[s.source] = (bySource[s.source] || 0) + 1; });
        const rows = [
            meta.durationMs != null ? ['Response time', formatDuration(meta.durationMs)] : null,
            meta.model ? ['Model', modelName(meta.model)] : null,
            ['Tokens', `${formatCount(total)} in${cached ? ` (${cached}% cached)` : ''}, ${formatCount(meta.outputTokens)} out`],
            ['Estimated cost', formatCost(meta.costUsd)],
            Object.keys(bySource).length ? ['Sources', Object.entries(bySource).map(([k, v]) => `${k} ${v}`).join(', ')] : null,
        ].filter(Boolean);
        return html`
            <dl class="chat-activity-meta">
                ${rows.map(([k, v]) => html`<div><dt>${k}</dt><dd>${v}</dd></div>`)}
            </dl>`;
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
                <div class="chat-answer">
                    ${this._activity(m.toolCalls, this.streaming && !m.text)}
                    ${m.text
                        ? html`<div class="chat-markdown">${unsafeHTML(this._html || '')}</div>`
                        : (this.streaming && (m.stopping || !(m.toolCalls || []).length)
                            ? html`<span class="chat-thinking">${m.stopping ? 'Stopping…' : 'Thinking…'}</span>` : '')}
                    ${!this.streaming ? this._references(m.sources) : ''}
                    ${!this.streaming && m.text ? this._actions(m) : ''}
                </div>
            </div>`;
    }
}

customElements.define('dms-chat-message', DmsChatMessage);
