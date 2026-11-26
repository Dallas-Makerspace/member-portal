import { preloadSource, remoteSource, mergeResults, sortResults, normalizeQuery } from './search-sources.js';
import { registeredTypes, typeInfo } from './search-types.js';
import { getRecents, addRecent, clearRecents, isNewTip, markTipsSeen } from './search-recents.js';
import { isSafeUrl } from './search-model.js';
import { trackOpen, trackResultClick, trackNoResults, trackAskAi } from './search-track.js';

const DEBOUNCE_MS = 120;
const REMOTE_DEBOUNCE_MS = 250;
const NO_RESULTS_DELAY_MS = 1000;
const SECTION_LIMIT = 5;
const MAX_ASK_CHARS = 500;
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

const ICONS = {
    search: '<path d="M10.5 3a7.5 7.5 0 0 1 5.96 12.05l4.25 4.24-1.42 1.42-4.24-4.25A7.5 7.5 0 1 1 10.5 3Zm0 2a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z"/>',
    person: '<path d="M12 12a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9Zm0 2c-4.42 0-8 2.24-8 5v2h16v-2c0-2.76-3.58-5-8-5Z"/>',
    groups: '<path d="M8 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm8.5 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM8 13c-3.31 0-6 1.79-6 4v2h12v-2c0-2.21-2.69-4-6-4Zm8.5 0c-.7 0-1.37.09-1.98.25A5.1 5.1 0 0 1 16 17v2h6v-2c0-2.21-2.46-4-5.5-4Z"/>',
    school: '<path d="M12 3 1 9l11 6 9-4.91V17h2V9L12 3Zm-6.5 9.36v4.03L12 20l6.5-3.61v-4.03L12 16l-6.5-3.64Z"/>',
    history: '<path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 2.05 4.95l-1.42 1.42A9 9 0 1 0 13 3Zm-1 5v5l4.25 2.52.77-1.28-3.52-2.09V8H12Z"/>',
    sparkle: '<path d="M11 2h2l1.2 4.8L19 8v2l-4.8 1.2L13 16h-2l-1.2-4.8L5 10V8l4.8-1.2L11 2Zm7 12h1.5l.6 2.4 2.4.6v1.5l-2.4.6-.6 2.4H18l-.6-2.4-2.4-.6V17l2.4-.6.6-2.4ZM4 15h1.3l.5 1.7 1.7.5v1.3l-1.7.5-.5 1.7H4l-.5-1.7-1.7-.5v-1.3l1.7-.5L4 15Z"/>',
    back: '<path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2Z"/>',
    close: '<path d="M18.3 5.71 12 12.01l-6.3-6.3-1.41 1.41L10.59 13.4l-6.3 6.3 1.41 1.41 6.3-6.3 6.3 6.3 1.41-1.41-6.3-6.3 6.3-6.3-1.41-1.39Z"/>',
    external: '<path d="M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42L17.59 5H14V3ZM5 5h6v2H7v10h10v-4h2v6H5V5Z"/>',
    arrow: '<path d="m10 6-1.41 1.41L13.17 12l-4.58 4.59L10 18l6-6-6-6Z"/>',
    alert: '<path d="M1 21h22L12 2 1 21Zm12-3h-2v-2h2v2Zm0-4h-2v-4h2v4Z"/>',
    committee: '<path d="M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6h-5.6Z"/>',
    link: '<path d="M3.9 12a3.1 3.1 0 0 1 3.1-3.1h4V7H7a5 5 0 0 0 0 10h4v-1.9H7A3.1 3.1 0 0 1 3.9 12ZM8 13h8v-2H8v2Zm9-6h-4v1.9h4a3.1 3.1 0 0 1 0 6.2h-4V17h4a5 5 0 0 0 0-10Z"/>',
    calendar: '<path d="M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Zm0 16H5V10h14v10ZM7 12h5v5H7v-5Z"/>',
};

function icon(name, cls = 'ic') {
    return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true" focusable="false">${ICONS[name] || ICONS.search}</svg>`;
}

function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
}

// Marks the earliest occurrence of any of the terms.
function highlight(text, terms) {
    const t = String(text ?? '');
    const lower = t.toLowerCase();
    let at = -1;
    let len = 0;
    for (const term of [].concat(terms || []).filter(Boolean)) {
        const i = lower.indexOf(term);
        if (i >= 0 && (at < 0 || i < at)) { at = i; len = term.length; }
    }
    if (at < 0) return esc(t);
    return `${esc(t.slice(0, at))}<mark>${esc(t.slice(at, at + len))}</mark>${esc(t.slice(at + len))}`;
}

const STYLES = `
:host { --s-accent: #0b9cc9; --s-accent-ink: #076a8a; --s-ink: #212529; --s-muted: #59616d;
  --s-line: #e9ecef; --s-wash: #f6f8fa; --s-tint: #e6f5fb; --s-radius: 14px; }
dialog { padding: 0; border: 0; background: transparent; max-width: none; max-height: none;
  width: min(680px, calc(100vw - 2rem)); margin: 10vh auto auto; color: var(--s-ink);
  font: 400 15px/1.4 Inter, system-ui, -apple-system, "Segoe UI", sans-serif; overflow: visible; }
dialog::backdrop { background: rgba(15, 23, 32, .45); backdrop-filter: blur(2px); }
dialog[open] .panel { animation: pop .14s ease-out; }
dialog[open]::backdrop { animation: fade .14s ease-out; }
@keyframes pop { from { opacity: 0; transform: translateY(-6px) scale(.985); } }
@keyframes fade { from { opacity: 0; } }
@media (prefers-reduced-motion: reduce) { dialog[open] .panel, dialog[open]::backdrop { animation: none; } }
.panel { display: flex; flex-direction: column; height: min(560px, 78vh); background: #fff;
  border-radius: var(--s-radius); box-shadow: 0 24px 64px rgba(15, 23, 32, .28), 0 2px 6px rgba(15, 23, 32, .12);
  overflow: hidden; }
.ic { width: 20px; height: 20px; fill: currentColor; flex: 0 0 auto; }

.bar { display: flex; align-items: center; gap: .6rem; padding: .85rem 1rem; border-bottom: 1px solid var(--s-line); }
.bar .lead { color: var(--s-muted); }
.bar input { flex: 1 1 auto; min-width: 0; border: 0; outline: 0; background: transparent; color: inherit;
  font: inherit; font-size: 17px; padding: .2rem 0; }
.bar input::placeholder { color: #8a929c; }
.bar input::-webkit-search-cancel-button { display: none; }
.iconbtn { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px;
  border: 0; border-radius: 8px; background: transparent; color: var(--s-muted); cursor: pointer; padding: 0; }
.iconbtn:hover { background: var(--s-wash); color: var(--s-ink); }
.iconbtn:focus-visible, .row:focus-visible, .linkbtn:focus-visible { outline: 2px solid var(--s-accent); outline-offset: 2px; }
.back { display: none; }
kbd { display: inline-flex; align-items: center; justify-content: center; min-width: 1.5em; height: 1.5em;
  padding: 0 .35em; border: 1px solid #d0d5db; border-bottom-width: 2px; border-radius: 5px; background: #fff;
  font: 500 11px/1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; color: var(--s-muted); }
.spinner { width: 16px; height: 16px; border: 2px solid var(--s-line); border-top-color: var(--s-accent);
  border-radius: 50%; animation: spin .8s linear infinite; flex: 0 0 auto; }
@keyframes spin { to { transform: rotate(360deg); } }
[hidden] { display: none !important; }

.body { flex: 1 1 auto; overflow-y: auto; overscroll-behavior: contain; padding: .35rem .5rem .6rem; }
.section { padding-top: .5rem; }
.shead { display: flex; align-items: center; justify-content: space-between; gap: .5rem;
  margin: .2rem .6rem .3rem; font-size: 12px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--s-muted); }
.shead .count { font-weight: 500; letter-spacing: 0; text-transform: none; margin-left: .35rem; color: #8a929c; }
.linkbtn { border: 0; background: none; padding: .15rem .35rem; border-radius: 6px; font: inherit; font-size: 12px;
  font-weight: 500; letter-spacing: 0; text-transform: none; color: var(--s-accent-ink); cursor: pointer; }
.linkbtn:hover { background: var(--s-tint); }

.row { display: flex; align-items: center; gap: .75rem; min-height: 48px; padding: .45rem .6rem; border-radius: 10px;
  color: inherit; text-decoration: none; cursor: pointer; scroll-margin: .5rem; }
.row[aria-selected="true"] { background: var(--s-tint); }
@media (hover: hover) { .row:hover { background: var(--s-wash); } .row[aria-selected="true"]:hover { background: var(--s-tint); } }
.thumb { width: 34px; height: 34px; flex: 0 0 auto; border-radius: 50%; display: inline-flex; align-items: center;
  justify-content: center; background: var(--s-wash); color: var(--s-muted); overflow: hidden; }
.thumb.square { border-radius: 9px; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.text { display: flex; flex-direction: column; min-width: 0; flex: 1 1 auto; }
.title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sub { font-size: 13px; color: var(--s-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
mark { background: #fff1b8; color: inherit; border-radius: 2px; padding: 0 1px; }
.chip { flex: 0 0 auto; font-size: 11px; font-weight: 600; padding: .15rem .5rem; border-radius: 999px;
  background: var(--s-wash); color: var(--s-muted); }
.chip.new { background: #fff1b8; color: #6b4e00; }
.chip.related { background: var(--s-tint); color: var(--s-accent-ink); }
.note { margin: .5rem .6rem 0; padding: .45rem .7rem; border-radius: 8px; background: var(--s-wash);
  color: var(--s-muted); font-size: 13px; }
.go { color: #b0b7bf; flex: 0 0 auto; }
.row[aria-selected="true"] .go { color: var(--s-accent-ink); }
.more { min-height: 38px; color: var(--s-accent-ink); font-weight: 500; font-size: 14px; }
.more .thumb { background: transparent; height: 20px; }

.skel { display: flex; align-items: center; gap: .75rem; padding: .55rem .6rem; }
.skel i { display: block; border-radius: 6px; background: linear-gradient(90deg, #f1f3f5 0, #e9ecef 50%, #f1f3f5 100%);
  background-size: 200% 100%; animation: shimmer 1.1s linear infinite; }
.skel i.c { width: 34px; height: 34px; border-radius: 50%; }
@keyframes shimmer { to { background-position: -200% 0; } }

.empty { text-align: center; padding: 2rem 1rem 1.5rem; color: var(--s-muted); }
.empty strong { display: block; color: var(--s-ink); font-size: 16px; margin-bottom: .25rem; }
.empty .ic { width: 28px; height: 28px; margin-bottom: .5rem; }
.empty button { margin-top: .75rem; border: 1px solid #ced4da; background: #fff; border-radius: 8px;
  padding: .4rem .9rem; font: inherit; cursor: pointer; }

.foot { display: flex; align-items: center; gap: .75rem; border-top: 1px solid var(--s-line); padding: .4rem .5rem;
  background: #fbfcfd; }
#actions { flex: 1 1 auto; min-width: 0; }
.ask { box-sizing: border-box; display: flex; align-items: center; gap: .5rem; min-height: 38px; padding: .25rem .6rem .25rem .3rem;
  border-radius: 8px; color: inherit; text-decoration: none; background: linear-gradient(90deg, #eef9fd, #f3f1fe);
  border: 1px solid #d7eef6; }
.ask .thumb { width: 26px; height: 26px; border-radius: 7px; background: linear-gradient(135deg, #0b9cc9, #6f5bd6);
  color: #fff; }
.ask .thumb .ic { width: 16px; height: 16px; }
.ask .text { flex-direction: row; align-items: baseline; gap: .4rem; }
.ask .title { flex: 0 0 auto; font-size: 14px; color: var(--s-ink); }
.ask .ask-q { min-width: 0; font-size: 13px; color: var(--s-muted); white-space: nowrap; overflow: hidden;
  text-overflow: ellipsis; }
.ask[aria-selected="true"], .ask:hover { border-color: var(--s-accent); box-shadow: 0 0 0 1px var(--s-accent) inset; }
.ask .keys { display: inline-flex; gap: .2rem; flex: 0 0 auto; }
.hints { display: flex; flex: 0 0 auto; gap: .75rem; font-size: 12px; color: #8a929c; }
.hints span { display: inline-flex; align-items: center; gap: .3rem; }

@media (max-width: 767.98px) {
  dialog { width: 100vw; height: 100dvh; margin: 0; }
  dialog::backdrop { backdrop-filter: none; }
  dialog[open] .panel { animation: slide .16s ease-out; }
  @keyframes slide { from { opacity: 0; transform: translateY(12px); } }
  .panel { max-height: none; height: 100dvh; border-radius: 0; box-shadow: none; }
  .bar { padding: .5rem .5rem .5rem .25rem; gap: .25rem; padding-top: max(.5rem, env(safe-area-inset-top)); }
  .bar .lead, .bar .esc, .hints { display: none; }
  .back { display: inline-flex; width: 44px; height: 44px; }
  .clear { width: 44px; height: 44px; }
  .bar input { font-size: 16px; padding: .5rem 0; }
  .body { padding: .25rem .25rem .75rem; }
  .row { min-height: 56px; }
  .foot { padding: .35rem .5rem max(.35rem, env(safe-area-inset-bottom)); }
  .ask { min-height: 44px; }
  .ask .keys { display: none; }
}
`;

class DmsSearchDialog extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({ mode: 'open' });
        this.query = '';
        this.options = [];
        this.active = -1;
        this.expanded = new Set();
        this.results = null;
        this.error = false;
        this.loading = false;
        this.seq = 0;
        this.timer = null;
        this.pushedState = false;
        this.newTips = new Map();
        this.localResults = [];
        this.remoteTimer = null;
        this.remoteCtrl = null;
        this.remoteLoading = false;
        this.awaitingRemote = false;
        this.noResultsTimer = null;
        this.reportedNoResults = new Set();
    }

    get username() {
        return this.getAttribute('username') || '';
    }

    get askUrl() {
        return this.getAttribute('ask-ai-url') || '/ask-ai';
    }

    connectedCallback() {
        if (this.dialog) return;
        this.shadowRoot.innerHTML = `
<style>${STYLES}</style>
<dialog aria-label="Search">
  <div class="panel">
    <div class="bar">
      <button type="button" class="iconbtn back" aria-label="Close search">${icon('back')}</button>
      ${icon('search', 'ic lead')}
      <input type="search" role="combobox" aria-expanded="true" aria-autocomplete="list"
             aria-controls="results actions" autocomplete="off" autocapitalize="off" spellcheck="false"
             enterkeyhint="search" placeholder="Search members, groups and more" aria-label="Search">
      <span class="spinner" hidden></span>
      <button type="button" class="iconbtn clear" aria-label="Clear search" hidden>${icon('close')}</button>
      <kbd class="esc" aria-hidden="true">Esc</kbd>
    </div>
    <div class="body" id="results" role="listbox" aria-label="Search results"></div>
    <div class="foot">
      <div id="actions" role="listbox" aria-label="More ways to find answers">
        <a class="ask" id="opt-ask" role="option" aria-selected="false" href="${esc(this.askUrl)}">
          <span class="thumb">${icon('sparkle')}</span>
          <span class="text"><span class="title">Ask AI Assistant</span><span class="ask-q"></span></span>
          <span class="keys" aria-hidden="true"><kbd>${IS_MAC ? '⌘' : 'Ctrl'}</kbd><kbd>↵</kbd></span>
        </a>
      </div>
      <div class="hints" aria-hidden="true">
        <span><kbd>↑</kbd><kbd>↓</kbd> move</span><span><kbd>↵</kbd> open</span>
      </div>
    </div>
  </div>
</dialog>`;
        const $ = s => this.shadowRoot.querySelector(s);
        this.dialog = $('dialog');
        this.input = $('input');
        this.body = $('#results');
        this.spinner = $('.spinner');
        this.clearBtn = $('.clear');
        this.ask = $('#opt-ask');
        this.askSub = $('.ask-q');

        this.input.addEventListener('input', () => this.onInput());
        this.input.addEventListener('keydown', e => this.onKeydown(e));
        this.clearBtn.addEventListener('click', () => this.setQuery(''));
        $('.back').addEventListener('click', () => this.close());
        this.dialog.addEventListener('close', () => this.onClosed());
        this.dialog.addEventListener('click', e => {
            if (e.target === this.dialog) this.close();
        });
        this.body.addEventListener('click', e => this.onBodyClick(e));
        this.body.addEventListener('error', e => {
            if (e.target.tagName !== 'IMG') return;
            const row = e.target.closest('.row');
            const type = row?.dataset.key?.split(':')[0];
            e.target.parentElement.innerHTML = icon(typeInfo(type).icon);
        }, true);
        this.body.addEventListener('mousemove', e => {
            const row = e.target.closest('[role="option"]');
            if (row) this.setActive(this.options.indexOf(row), false);
        });
        this.ask.addEventListener('click', e => this.onAskClick(e));
        window.addEventListener('popstate', () => {
            if (this.dialog.open && this.pushedState) {
                this.pushedState = false;
                this.close();
            }
        });
        this.render();
    }

    prefetch() {
        preloadSource.load().catch(() => { /* surfaced when searching */ });
    }

    open(initial = '', trigger = 'click') {
        if (this.dialog.open) {
            this.input.focus();
            return;
        }
        preloadSource.load()
            .then(() => { if (this.dialog.open && !normalizeQuery(this.query)) this.render(); })
            .catch(() => { /* surfaced when searching */ });
        trackOpen(trigger);
        this.reportedNoResults.clear();
        this.newTips = new Map();
        this.expanded.clear();
        this.dialog.showModal();
        document.documentElement.style.overflow = 'hidden';
        if (matchMedia('(max-width: 767.98px)').matches) {
            history.pushState({ dmsSearch: true }, '');
            this.pushedState = true;
        }
        this.setQuery(initial || this.query);
        this.input.select();
        this.input.focus();
    }

    close() {
        if (this.dialog.open) this.dialog.close();
    }

    onClosed() {
        document.documentElement.style.overflow = '';
        this.cancelPending();
        markTipsSeen([...this.newTips].filter(([, isNew]) => isNew).map(([type]) => type));
        this.newTips = new Map();
        if (this.pushedState) {
            this.pushedState = false;
            history.back();
        }
    }

    cancelPending() {
        clearTimeout(this.timer);
        clearTimeout(this.remoteTimer);
        clearTimeout(this.noResultsTimer);
        this.remoteCtrl?.abort();
        this.remoteCtrl = null;
        this.awaitingRemote = false;
        this.setRemoteLoading(false);
    }

    setQuery(q) {
        this.input.value = q;
        this.onInput(true);
    }

    onInput(immediate = false) {
        this.query = this.input.value;
        this.clearBtn.hidden = !this.query;
        this.updateAsk();
        this.cancelPending();
        this.expanded.clear();
        if (!normalizeQuery(this.query)) {
            this.seq++;
            this.results = null;
            this.error = false;
            this.setLoading(false);
            this.render();
            return;
        }
        if (!preloadSource.ready) {
            this.setLoading(true);
            this.render();
        }
        this.timer = setTimeout(() => this.runSearch(), immediate ? 0 : DEBOUNCE_MS);
    }

    async runSearch() {
        const seq = ++this.seq;
        const q = this.query;
        this.error = false;
        let local;
        try {
            local = await preloadSource.search(q);
        } catch (err) {
            if (seq !== this.seq) return;
            console.error('Search failed', err);
            this.results = null;
            this.error = true;
            this.setLoading(false);
            this.render();
            return;
        }
        if (seq !== this.seq) return;
        this.localResults = local;
        this.results = sortResults([...local]);
        this.awaitingRemote = remoteSource.eligible(q);
        this.setLoading(false);
        this.render();
        if (this.awaitingRemote) {
            this.remoteTimer = setTimeout(() => this.runRemote(seq, q), REMOTE_DEBOUNCE_MS);
        } else {
            this.scheduleNoResults(seq, q);
        }
    }

    async runRemote(seq, q) {
        this.remoteCtrl?.abort();
        const ctrl = new AbortController();
        this.remoteCtrl = ctrl;
        this.setRemoteLoading(true);
        let remote = [];
        try {
            remote = await remoteSource.search(q, preloadSource.altTerms(q), ctrl.signal);
        } catch (err) {
            if (err.name === 'AbortError') return;
            console.warn('Class search failed', err);
        } finally {
            if (this.remoteCtrl === ctrl) {
                this.remoteCtrl = null;
                this.setRemoteLoading(false);
            }
        }
        if (seq !== this.seq) return;
        this.awaitingRemote = false;
        this.results = mergeResults(this.localResults || [], remote);
        this.render(true);
        this.scheduleNoResults(seq, q);
    }

    scheduleNoResults(seq, q) {
        const key = normalizeQuery(q);
        if (this.results?.length || !key || this.reportedNoResults.has(key)) return;
        this.noResultsTimer = setTimeout(() => {
            if (seq !== this.seq || this.results?.length) return;
            this.reportedNoResults.add(key);
            trackNoResults(q);
        }, NO_RESULTS_DELAY_MS);
    }

    setLoading(on) {
        this.loading = on;
        this.spinner.hidden = !(this.loading || this.remoteLoading);
    }

    setRemoteLoading(on) {
        this.remoteLoading = on;
        this.spinner.hidden = !(this.loading || this.remoteLoading);
    }

    updateAsk() {
        const q = this.query.trim().slice(0, MAX_ASK_CHARS);
        this.ask.href = q ? `${this.askUrl}?q=${encodeURIComponent(q)}` : this.askUrl;
        this.askSub.textContent = q ? `“${q}”` : '';
    }

    optionKey(el) {
        if (!el) return null;
        if (!el.dataset.key) return el.id;
        return `${el.id.startsWith('opt-recent') ? 'r' : 'x'}:${el.dataset.key}`;
    }

    render(keepActive = false) {
        const prevKey = keepActive ? this.optionKey(this.options[this.active]) : null;
        const q = normalizeQuery(this.query);
        let html;
        if (!q) html = this.renderIdle();
        else if (this.error) html = this.renderError();
        else if (this.results === null || this.loading) html = this.renderSkeleton();
        else if (this.results.length === 0 && this.awaitingRemote) html = this.renderSkeleton();
        else if (this.results.length === 0) html = this.renderNoResults();
        else html = this.renderResults(q);
        this.body.innerHTML = html;
        this.options = [...this.body.querySelectorAll('[role="option"]'), this.ask];
        let idx = q && this.results?.length ? 0 : -1;
        if (prevKey) {
            const i = this.options.findIndex(o => this.optionKey(o) === prevKey);
            if (i >= 0) idx = i;
        }
        this.setActive(idx, false);
    }

    renderIdle() {
        const recents = getRecents(this.username);
        let html = '';
        if (recents.length) {
            html += `<div class="section" role="group" aria-labelledby="h-recent">
              <div class="shead"><span id="h-recent">Recent</span>
                <button type="button" class="linkbtn" data-action="clear-recents">Clear</button></div>
              ${recents.map((r, i) => this.row(r, `opt-recent-${i}`, '', { recent: true })).join('')}
            </div>`;
        }
        const available = preloadSource.types();
        const tips = registeredTypes().filter(t => t.tip && (!t.gated || available.has(t.type)));
        for (const t of tips) {
            if (!this.newTips.has(t.type)) this.newTips.set(t.type, isNewTip(t.type, t.since));
        }
        html += `<div class="section" role="group" aria-labelledby="h-tips">
          <div class="shead"><span id="h-tips">You can search for</span></div>
          ${tips.map(t => `
            <div class="row" role="option" id="opt-tip-${esc(t.type)}" aria-selected="false"
                 data-action="tip" data-example="${esc(t.example || '')}">
              <span class="thumb square">${icon(t.icon)}</span>
              <span class="text"><span class="title">${esc(t.tip)}</span>
                ${t.example ? `<span class="sub">Try “${esc(t.example)}”</span>` : ''}</span>
              ${this.newTips.get(t.type) ? '<span class="chip new">New</span>' : ''}
            </div>`).join('')}
        </div>`;
        return html;
    }

    renderSkeleton() {
        const one = '<div class="skel" aria-hidden="true"><i class="c"></i><span style="flex:1"><i style="width:45%;height:12px;margin-bottom:6px"></i><i style="width:28%;height:10px"></i></span></div>';
        return `<div class="section" aria-busy="true"><div class="shead"><span>Searching…</span></div>${one.repeat(4)}</div>`;
    }

    renderError() {
        return `<div class="empty" role="alert">${icon('alert')}
          <strong>Search isn't available right now</strong>
          Check your connection and try again.<br>
          <button type="button" data-action="retry">Try again</button></div>`;
    }

    renderNoResults() {
        return `<div class="empty">${icon('search')}
          <strong>No results for “${esc(this.query.trim())}”</strong>
          Try a different spelling, or ask the AI Assistant below.</div>`;
    }

    renderResults(q) {
        const byType = new Map();
        for (const r of this.results) {
            if (!byType.has(r.type)) byType.set(r.type, []);
            byType.get(r.type).push(r);
        }
        const sections = [...byType.entries()].sort(([ta, a], [tb, b]) =>
            (b[0].score || 0) - (a[0].score || 0) || typeInfo(ta).order - typeInfo(tb).order);
        const onlyClose = this.results.every(r => r.matchKind === 'fuzzy');
        const note = onlyClose ? '<div class="note" role="status">No exact matches. Showing close matches.</div>' : '';
        return note + sections.map(([type, list]) => {
            const info = typeInfo(type);
            const open = this.expanded.has(type);
            const shown = open ? list : list.slice(0, SECTION_LIMIT);
            const hidden = list.length - shown.length;
            const hid = `h-${esc(type)}`;
            return `<div class="section" role="group" aria-labelledby="${hid}">
              <div class="shead"><span id="${hid}">${esc(info.label)}<span class="count">${list.length}</span></span></div>
              ${shown.map((r, i) => this.row(r, `opt-${esc(type)}-${i}`, q)).join('')}
              ${hidden > 0 ? `<div class="row more" role="option" aria-selected="false" id="opt-more-${esc(type)}"
                  data-action="more" data-type="${esc(type)}"><span class="thumb"></span>
                  <span class="text">Show ${hidden} more ${esc(info.label.toLowerCase())}</span></div>` : ''}
            </div>`;
        }).join('');
    }

    row(r, id, q, { recent = false } = {}) {
        const info = typeInfo(r.type);
        const safe = isSafeUrl(r.url, r.external);
        const thumb = r.imageUrl
            ? `<span class="thumb${r.type === 'member' ? '' : ' square'}"><img src="${esc(r.imageUrl)}" alt="" loading="lazy" decoding="async"></span>`
            : `<span class="thumb square">${icon(info.icon)}</span>`;
        const related = r.matchKind === 'related' && !recent
            ? [`<span class="chip related" title="Related to “${esc(r.relatedTo)}”">Related</span>`] : [];
        const chips = [
            ...(r.badges || []).map(b => `<span class="chip">${esc(b)}</span>`),
            ...related,
            ...(recent ? [`<span class="chip">${esc(singular(info.label))}</span>`] : []),
        ].join('');
        const terms = r.hl && r.matchKind !== 'exact' ? r.hl : q;
        const ext = r.external ? ' target="_blank" rel="noopener"' : '';
        return `<a class="row" role="option" id="${id}" aria-selected="false" data-key="${esc(r.type)}:${esc(r.id)}"
            ${safe ? `href="${esc(r.url)}"${ext}` : 'aria-disabled="true"'}>
          ${thumb}
          <span class="text"><span class="title">${highlight(r.title, terms)}</span>
            ${r.subtitle ? `<span class="sub">${highlight(r.subtitle, terms)}</span>` : ''}</span>
          ${chips}
          ${icon(r.external || r.offsite ? 'external' : 'arrow', 'ic go')}
        </a>`;
    }

    findResult(key) {
        const recents = getRecents(this.username);
        return [...(this.results || []), ...recents].find(r => `${r.type}:${r.id}` === key);
    }

    onBodyClick(e) {
        const el = e.target.closest('[data-action], a.row');
        if (!el) return;
        const action = el.dataset.action;
        if (action === 'clear-recents') {
            clearRecents(this.username);
            this.render();
            this.input.focus();
        } else if (action === 'tip') {
            if (el.dataset.example) this.setQuery(el.dataset.example);
            this.input.focus();
        } else if (action === 'retry') {
            this.onInput(true);
            this.input.focus();
        } else if (action === 'more') {
            this.expand(el.dataset.type);
        } else if (el.matches('a.row')) {
            this.onResultClick(e, el);
        }
    }

    expand(type) {
        const idx = this.active;
        this.expanded.add(type);
        const q = normalizeQuery(this.query);
        this.body.innerHTML = this.renderResults(q);
        this.options = [...this.body.querySelectorAll('[role="option"]'), this.ask];
        this.setActive(Math.min(idx, this.options.length - 1), true);
        this.input.focus();
    }

    trackClick(el) {
        const recent = el.id.startsWith('opt-recent');
        const section = el.closest('.section');
        const r = recent ? null : this.findResult(el.dataset.key);
        trackResultClick({
            resultType: (el.dataset.key || '').split(':')[0],
            resultRank: [...this.body.querySelectorAll('a.row')].indexOf(el) + 1,
            sectionRank: section ? [...section.querySelectorAll('a.row')].indexOf(el) + 1 : 0,
            queryLength: this.query.trim().length,
            resultCount: this.results?.length || 0,
            fromRecents: recent,
            matchKind: recent ? 'recent' : (r?.matchKind || 'exact'),
        });
    }

    onResultClick(e, el) {
        const r = this.findResult(el.dataset.key);
        if (!el.hasAttribute('href')) {
            e.preventDefault();
            return;
        }
        this.trackClick(el);
        if (r) addRecent(this.username, r);
        const newTab = e.ctrlKey || e.metaKey || e.shiftKey || e.button === 1 || el.target === '_blank';
        if (!newTab) {
            e.preventDefault();
            this.navigate(el.href);
        }
    }

    askAi(e) {
        trackAskAi({ queryLength: this.query.trim().length, resultCount: this.results?.length || 0 });
        if (e?.type === 'click' && (e.ctrlKey || e.metaKey || e.shiftKey || e.button === 1)) return;
        e?.preventDefault();
        this.navigate(this.ask.href);
    }

    onAskClick(e) {
        this.askAi(e);
    }

    navigate(href) {
        // On mobile the dialog's history entry is replaced, so Back from the next page lands here.
        const replace = this.pushedState;
        this.pushedState = false;
        this.close();
        if (replace) location.replace(href);
        else location.assign(href);
    }

    activate(el, e) {
        if (!el) return;
        if (el === this.ask) {
            this.askAi();
            return;
        }
        if (el.matches('a.row')) {
            const r = this.findResult(el.dataset.key);
            if (!el.hasAttribute('href')) return;
            this.trackClick(el);
            if (r) addRecent(this.username, r);
            if (el.target === '_blank' || e?.ctrlKey || e?.metaKey) {
                window.open(el.href, '_blank', 'noopener');
                return;
            }
            this.navigate(el.href);
            return;
        }
        el.click();
    }

    onKeydown(e) {
        const n = this.options.length;
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.setActive(this.active < n - 1 ? this.active + 1 : 0, true);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.setActive(this.active > 0 ? this.active - 1 : n - 1, true);
        } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            this.askAi();
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (e.isComposing) return;
            const el = this.options[this.active];
            if (el) this.activate(el, e);
            else if (normalizeQuery(this.query) && this.results === null) this.runSearch();
        } else if (e.key === 'Escape' && this.query && !matchMedia('(max-width: 767.98px)').matches) {
            e.preventDefault();
            this.setQuery('');
        }
    }

    setActive(i, scroll) {
        this.options.forEach(o => o.setAttribute('aria-selected', 'false'));
        this.active = i;
        const el = this.options[i];
        if (el) {
            el.setAttribute('aria-selected', 'true');
            this.input.setAttribute('aria-activedescendant', el.id);
            if (scroll) el.scrollIntoView({ block: 'nearest' });
        } else {
            this.input.removeAttribute('aria-activedescendant');
        }
    }
}

function singular(label) {
    return label.endsWith('s') ? label.slice(0, -1) : label;
}

customElements.define('dms-search-dialog', DmsSearchDialog);

// Page-level wiring: any [data-search-open] opens the dialog; Ctrl/Cmd+K and "/" too.
function dialogEl() {
    return document.querySelector('dms-search-dialog');
}

document.addEventListener('click', e => {
    const trigger = e.target.closest?.('[data-search-open]');
    if (!trigger) return;
    e.preventDefault();
    dialogEl()?.open('', trigger.classList.contains('navbar-search-toggle') ? 'mobile' : 'click');
});

['pointerenter', 'focusin', 'touchstart'].forEach(type => {
    document.addEventListener(type, e => {
        if (e.target.closest?.('[data-search-open]')) dialogEl()?.prefetch();
    }, { capture: true, passive: true });
});

document.addEventListener('keydown', e => {
    const d = dialogEl();
    if (!d) return;
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        d.open('', 'shortcut');
        return;
    }
    if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        const t = e.composedPath()[0];
        const typing = t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
        if (!typing) {
            e.preventDefault();
            d.open('', 'shortcut');
        }
    }
});
