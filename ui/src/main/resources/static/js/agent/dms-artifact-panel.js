import {LitElement, html} from 'https://esm.sh/lit@3.3.0';
import {unsafeHTML} from 'https://esm.sh/lit@3.3.0/directives/unsafe-html.js';
import {renderMarkdown} from './markdown.js';
import {api} from './api.js';
import {lineDiff, readable, withContext} from './artifact-diff.js';
import {outlineForm, quizForm} from './artifact-forms.js';

const KIND_ICONS = {markdown: 'article', course_outline: 'account_tree', quiz: 'quiz'};
const FORMAT_LABELS = {md: 'Markdown', gift: 'Moodle GIFT', xml: 'Moodle XML'};

/**
 * Documents the assistant wrote, beside the chat. Properties: items (summaries), active (id),
 * refresh (any changing number reloads the open document). Fires `close` and `select`.
 */
class DmsArtifactPanel extends LitElement {
    static properties = {
        items: {type: Array},
        active: {type: String},
        refresh: {type: Number},
        _detail: {state: true},
        _editing: {state: true},
        _draft: {state: true},
        _error: {state: true},
        _saving: {state: true},
        _form: {state: true},
        _diff: {state: true},
    };

    createRenderRoot() {
        return this;
    }

    constructor() {
        super();
        this.items = [];
        this._detail = null;
        this._editing = false;
        this._draft = '';
        this._error = '';
        this._saving = false;
        this._form = null;
        this._diff = null;
    }

    willUpdate(changed) {
        if (changed.has('active') || changed.has('refresh')) {
            if (changed.has('active')) {
                this._editing = false;
                this._diff = null;
            }
            this._load();
        }
    }

    async _load(version) {
        if (!this.active) {
            this._detail = null;
            return;
        }
        const query = version ? `?version=${version}` : '';
        const res = await api('GET', `/artifacts/${encodeURIComponent(this.active)}${query}`);
        if (res.ok) {
            this._detail = res.data;
            this._error = '';
        } else {
            this._error = res.message || 'Could not load the document.';
        }
    }

    _select(id) {
        this.dispatchEvent(new CustomEvent('select', {detail: id, bubbles: true}));
    }

    _edit() {
        this._draft = this._detail.content;
        this._error = '';
        this._diff = null;
        this._form = null;
        if (this._detail.artifact.kind !== 'markdown') {
            try {
                this._form = JSON.parse(this._draft);
            } catch (e) { /* edit as text */ }
        }
        this._editing = true;
    }

    /** Keeps the text in step with the form; [redraw] false while typing so inputs keep focus. */
    _changed(redraw = true) {
        this._draft = JSON.stringify(this._form, null, 2);
        if (redraw) this.requestUpdate();
    }

    _toText() {
        if (this._form) this._draft = JSON.stringify(this._form, null, 2);
        this._form = null;
    }

    _toForm() {
        try {
            this._form = JSON.parse(this._draft);
            this._error = '';
        } catch (e) {
            this._error = 'That text is not valid JSON yet, so the form cannot open it.';
        }
    }

    async _toggleDiff() {
        if (this._diff) {
            this._diff = null;
            return;
        }
        const d = this._detail;
        const before = await api('GET', `/artifacts/${encodeURIComponent(this.active)}?version=${d.version - 1}`);
        if (!before.ok) {
            this._error = before.message || 'Could not load the earlier version.';
            return;
        }
        const kind = d.artifact.kind;
        this._diff = lineDiff(readable(kind, before.data.content), readable(kind, d.content));
    }

    async _save() {
        this._saving = true;
        const res = await api('PUT', `/artifacts/${encodeURIComponent(this.active)}`,
            {content: this._form ? JSON.stringify(this._form) : this._draft, basedOn: this._detail.version});
        this._saving = false;
        if (res.ok) {
            this._editing = false;
            this._error = '';
            this.dispatchEvent(new CustomEvent('saved', {bubbles: true}));
            this._load();
        } else {
            this._error = res.message || 'Could not save.';
        }
    }

    async _export(format) {
        const v = this._detail.version;
        const res = await api('GET', `/artifacts/${encodeURIComponent(this.active)}/export?format=${format}&version=${v}`);
        if (!res.ok) {
            this._error = res.message || 'Could not export.';
            return;
        }
        const file = res.data;
        const url = URL.createObjectURL(new Blob([file.body], {type: file.contentType}));
        const link = document.createElement('a');
        link.href = url;
        link.download = file.fileName;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    _diffView() {
        const changed = this._diff.filter(l => l.kind !== 'same').length;
        if (!changed) return html`<p class="text-muted">No differences from the previous version.</p>`;
        const mark = k => (k === 'add' ? '+' : k === 'del' ? '-' : ' ');
        return html`<pre class="artifact-diff" aria-label="Changes from the previous version">${withContext(this._diff).map(l => html`<div class="diff-${l.kind}"><span class="diff-mark" aria-hidden="true">${mark(l.kind)}</span>${l.text}</div>`)}</pre>`;
    }

    _body(kind, content) {
        if (kind === 'markdown') return html`<div class="chat-markdown">${unsafeHTML(renderMarkdown(content))}</div>`;
        let data;
        try {
            data = JSON.parse(content);
        } catch (e) {
            return html`<pre class="artifact-raw">${content}</pre>`;
        }
        return kind === 'course_outline' ? this._outline(data) : this._quiz(data);
    }

    _outline(o) {
        return html`<div class="artifact-doc">
            <h2>${o.title}</h2>
            ${(o.sections || []).map((s, i) => html`
                <h3>${i + 1}. ${s.title}</h3>
                <ul class="artifact-list">${(s.activities || []).map(a => html`
                    <li><strong>${a.title}</strong> <span class="artifact-tag">${a.type}</span>
                        ${a.minutes ? html`<span class="text-muted"> ${a.minutes} min</span>` : ''}
                        ${a.summary ? html`<div class="text-muted">${a.summary}</div>` : ''}</li>`)}</ul>`)}
        </div>`;
    }

    _quiz(q) {
        return html`<div class="artifact-doc">
            <h2>${q.title}</h2>
            <ol class="artifact-questions">${(q.questions || []).map(item => html`
                <li><div>${item.text} <span class="artifact-tag">${item.type.replace('_', ' ')}</span></div>
                    ${(item.options || []).length ? html`<ul class="artifact-options">${item.options.map(o => html`
                        <li class=${o.correct ? 'correct' : ''}>${o.text}</li>`)}</ul>` : ''}
                    ${(item.answers || []).length ? html`<div class="text-muted">Accepted: ${item.answers.join(', ')}</div>` : ''}
                    ${(item.pairs || []).map(p => html`<div class="text-muted">${p.item} → ${p.match}</div>`)}
                    ${item.feedback ? html`<div class="text-muted fst-italic">${item.feedback}</div>` : ''}</li>`)}
            </ol>
        </div>`;
    }

    render() {
        const d = this._detail;
        return html`
            <div class="artifact-head">
                <div class="artifact-tabs" role="tablist">
                    ${this.items.map(i => html`
                        <button role="tab" aria-selected=${i.id === this.active} class=${i.id === this.active ? 'active' : ''}
                                @click=${() => this._select(i.id)} title=${i.title}>
                            <span class="material-symbols-outlined" aria-hidden="true">${KIND_ICONS[i.kind] || 'description'}</span>
                            <span class="artifact-tab-title">${i.title}</span></button>`)}
                </div>
                <button class="btn chat-close" aria-label="Close documents" title="Close"
                        @click=${() => this.dispatchEvent(new CustomEvent('close', {bubbles: true}))}>
                    <span class="material-symbols-outlined" aria-hidden="true">close</span></button>
            </div>
            ${d ? html`
                <div class="artifact-bar">
                    <select class="form-select form-select-sm artifact-version" aria-label="Version"
                            @change=${e => this._load(Number(e.target.value))}>
                        ${d.versions.slice().reverse().map(v => html`
                            <option value=${v.version} ?selected=${v.version === d.version}>
                                v${v.version} · ${v.author === 'assistant' ? 'Assistant' : 'You'}
                                ${v.version === d.artifact.currentVersion ? '(latest)' : ''}</option>`)}
                    </select>
                    <span class="flex-grow-1"></span>
                    ${d.version > 1 && !this._editing ? html`<button class="btn btn-sm btn-outline-secondary" aria-pressed=${!!this._diff}
                            @click=${this._toggleDiff}>Changes</button>` : ''}
                    ${d.canEdit && d.version === d.artifact.currentVersion && !this._editing
                        ? html`<button class="btn btn-sm btn-outline-secondary" @click=${this._edit}>Edit</button>` : ''}
                    ${d.exportFormats.map(f => html`
                        <button class="btn btn-sm btn-outline-secondary" @click=${() => this._export(f)}
                                title="Download as ${FORMAT_LABELS[f] || f}">${FORMAT_LABELS[f] || f}</button>`)}
                </div>
                ${this._error ? html`<div class="alert alert-danger py-1 m-2" role="alert">${this._error}</div>` : ''}
                <div class="artifact-body">
                    ${this._editing ? html`
                        ${d.artifact.kind !== 'markdown' ? html`<div class="artifact-modes">
                            <button class="btn btn-sm ${this._form ? 'btn-primary' : 'btn-outline-secondary'}" @click=${this._toForm}>Form</button>
                            <button class="btn btn-sm ${this._form ? 'btn-outline-secondary' : 'btn-primary'}" @click=${this._toText}>JSON</button>
                        </div>` : ''}
                        ${this._form
                            ? (d.artifact.kind === 'quiz' ? quizForm(this._form, r => this._changed(r)) : outlineForm(this._form, r => this._changed(r)))
                            : html`<textarea class="artifact-editor" .value=${this._draft} spellcheck="false"
                                  @input=${e => { this._draft = e.target.value; }}></textarea>`}
                        <div class="artifact-actions">
                            <button class="btn btn-primary btn-sm" ?disabled=${this._saving} @click=${this._save}>
                                ${this._saving ? 'Saving…' : 'Save'}</button>
                            <button class="btn btn-outline-secondary btn-sm" @click=${() => { this._editing = false; this._error = ''; }}>Cancel</button>
                        </div>`
                        : (this._diff ? this._diffView() : this._body(d.artifact.kind, d.content))}
                </div>`
                : html`<div class="p-3 text-muted">${this._error || 'Loading…'}</div>`}`;
    }
}

customElements.define('dms-artifact-panel', DmsArtifactPanel);
