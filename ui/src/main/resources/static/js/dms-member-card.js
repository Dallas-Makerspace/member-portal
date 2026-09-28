import { LitElement, html, css } from 'https://esm.sh/lit@3.3.0';
import { fetchMember } from './dms-member-service.js';

// Shared observer so all cards intersecting together land in one callback,
// keeping their fetchMember calls in the same sync execution → one batched request.
let _sharedObserver = null;
const _observerCallbacks = new WeakMap();

function getSharedObserver() {
    if (!_sharedObserver) {
        _sharedObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    if (entry.isIntersecting) {
                        _sharedObserver.unobserve(entry.target);
                        _observerCallbacks.get(entry.target)?.();
                        _observerCallbacks.delete(entry.target);
                    }
                }
            },
            { rootMargin: '200px' }
        );
    }
    return _sharedObserver;
}

/**
 * DmsMemberCard - Self-fetching web component for displaying a DMS member.
 *
 * @element dms-member-card
 * @prop {String} username - The member's username (required)
 * @prop {String} state - Display state: 'inline' | 'mini' (default: 'inline')
 * @prop {Boolean} openInNewWindow - Open profile link in new tab (default: false)
 *
 * Member data (displayName, avatarUrl, active status) is fetched automatically
 * via a batched API call shared across all cards rendered in the same tick.
 */
class DmsMemberCard extends LitElement {
    static properties = {
        username: { type: String },
        state: { type: String },
        openInNewWindow: { type: Boolean },
        _loading: { state: true },
        _memberData: { state: true },
        _error: { state: true },
    };

    static styles = css`
        :host {
            display: block;
            width: 100%;
            max-width: 100%;
        }

        @keyframes shimmer {
            0%   { background-position: -200% 0; }
            100% { background-position:  200% 0; }
        }

        .shimmer {
            display: block;
            background: linear-gradient(90deg, #ececec 25%, #d8d8d8 50%, #ececec 75%);
            background-size: 200% 100%;
            animation: shimmer 1.4s ease-in-out infinite;
            border-radius: 4px;
        }

        .shimmer-inline {
            width: 80px;
            height: 14px;
            display: inline-block;
            vertical-align: middle;
        }

        .shimmer-username {
            width: 65%;
            height: 12px;
            margin-bottom: 5px;
        }

        .shimmer-displayname {
            width: 85%;
            height: 11px;
        }

        /* Inline state */
        .inline-card {
            display: inline;
        }

        .inline-link {
            color: #4a90e2;
            text-decoration: none;
            font-weight: 500;
        }

        .inline-link:hover {
            text-decoration: underline;
            color: #357abd;
        }

        .inline-link--inactive {
            color: #999;
        }

        .inline-inactive-icon {
            font-family: 'Material Symbols Outlined';
            font-size: 13px;
            font-style: normal;
            font-weight: normal;
            color: #dc3545;
            vertical-align: middle;
            margin-left: 3px;
            line-height: 1;
            display: inline-block;
            white-space: nowrap;
            direction: ltr;
            letter-spacing: normal;
            text-transform: none;
            -webkit-font-feature-settings: 'liga';
            font-feature-settings: 'liga';
            font-variation-settings: 'FILL' 1, 'wght' 400, 'GRAD' 0, 'opsz' 24;
            -webkit-font-smoothing: antialiased;
        }

        /* Mini state */
        .mini-card {
            display: flex;
            align-items: center;
            text-decoration: none;
            color: inherit;
            transition: opacity 0.2s ease;
            width: 100%;
        }

        .mini-card:hover {
            opacity: 0.8;
        }

        .mini-card--loading {
            pointer-events: none;
        }

        /* Avatar */
        .avatar-wrapper {
            position: relative;
            flex-shrink: 0;
            margin-right: 12px;
        }

        .avatar {
            width: 40px;
            height: 40px;
            border-radius: 50%;
            border: 1px solid #ccc;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 600;
            color: #333;
            font-size: 16px;
            overflow: hidden;
            text-transform: uppercase;
        }

        .avatar img {
            width: 100%;
            height: 100%;
            object-fit: cover;
            border-radius: 50%;
        }

        .avatar--placeholder {
            background-color: #e8e8e8;
            border-color: #e0e0e0;
        }

        .avatar--inactive {
            filter: grayscale(100%);
            opacity: 0.6;
        }

        .inactive-badge {
            position: absolute;
            bottom: -3px;
            right: -3px;
            width: 17px;
            height: 17px;
            background: #fff;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .inactive-badge-icon {
            font-family: 'Material Symbols Outlined';
            font-size: 13px;
            font-style: normal;
            font-weight: normal;
            color: #dc3545;
            line-height: 1;
            display: inline-block;
            white-space: nowrap;
            direction: ltr;
            letter-spacing: normal;
            text-transform: none;
            -webkit-font-feature-settings: 'liga';
            font-feature-settings: 'liga';
            font-variation-settings: 'FILL' 1, 'wght' 400, 'GRAD' 0, 'opsz' 24;
            -webkit-font-smoothing: antialiased;
        }

        /* Member info */
        .member-info {
            flex: 1;
            min-width: 0;
        }

        .username {
            font-weight: 500;
            color: #4a90e2;
            font-size: 14px;
            margin: 0 0 2px 0;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            line-height: 1.2;
        }

        .username--inactive {
            color: #999;
        }

        .display-name {
            font-size: 13px;
            color: #333;
            margin: 0;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            line-height: 1.2;
        }

        @media (max-width: 768px) {
            .avatar-wrapper { margin-right: 10px; }

            .avatar {
                width: 36px;
                height: 36px;
                font-size: 14px;
            }

            .username { font-size: 13px; }
            .display-name { font-size: 12px; }
        }
    `;

    constructor() {
        super();
        this.username = '';
        this.state = 'inline';
        this.openInNewWindow = false;
        this._loading = true;
        this._memberData = null;
        this._error = false;
        this._intersected = false;
    }

    connectedCallback() {
        super.connectedCallback();
        // @font-face rules must live in the document (not a shadow root) to propagate
        // into Shadow DOM. Inject once so any page using this component gets the font.
        if (!document.querySelector('link[data-dms-material-symbols]')) {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = 'https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@24,400,1,0';
            link.setAttribute('data-dms-material-symbols', '');
            document.head.appendChild(link);
        }
        _observerCallbacks.set(this, () => {
            this._intersected = true;
            if (this.username) this._fetch();
        });
        getSharedObserver().observe(this);
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        getSharedObserver().unobserve(this);
        _observerCallbacks.delete(this);
    }

    updated(changedProperties) {
        if (changedProperties.has('username') && this.username) {
            this._loading = true;
            this._memberData = null;
            this._error = false;
            if (this._intersected) this._fetch();
        }
    }

    async _fetch() {
        try {
            this._memberData = await fetchMember(this.username);
        } catch {
            this._error = true;
        } finally {
            this._loading = false;
        }
    }

    _getProfileUrl() {
        return `/profile/@${this.username}`;
    }

    _getDisplayName() {
        if (!this._memberData) return this.username;
        const { displayName, firstName, lastName } = this._memberData;
        return displayName
            || [firstName, lastName].filter(Boolean).join(' ')
            || this.username;
    }

    _isActive() {
        return this._memberData ? this._memberData.enabled !== false : true;
    }

    _getLinkTarget() { return this.openInNewWindow ? '_blank' : '_self'; }
    _getLinkRel()    { return this.openInNewWindow ? 'noopener noreferrer' : ''; }

    _resolveAvatarUrl(url) {
        if (!url) return null;
        // An uploaded profile photo is already a same-origin path (/avatar/@username?v=...) -
        // pass it through unchanged rather than prefixing it onto the Discourse host below.
        if (url.startsWith('/avatar/')) return url;
        const absolute = url.startsWith('//')
            ? `https:${url}`
            : url.startsWith('/')
            ? `https://talk.dallasmakerspace.org${url}`
            : url;
        return absolute.replace('{size}', '90');
    }

    _renderAvatar() {
        // Uploaded photo takes precedence over the Discourse avatar - avatarUrl was removed from
        // the API response when the dead DB column it came from was dropped.
        const url = this._resolveAvatarUrl(this._memberData?.profilePhotoUrl ?? this._memberData?.discourseAvatarUrl);
        if (url) {
            return html`<img src="${url}" alt="${this._getDisplayName()}" loading="lazy">`;
        }
        const letter = this._getDisplayName().charAt(0).toUpperCase();
        return html`
            <div style="background-color:#f0f0f0;width:100%;height:100%;display:flex;align-items:center;justify-content:center;border-radius:50%;">
                ${letter}
            </div>
        `;
    }

    _renderInlineLoading() {
        return html`<div class="inline-card"><span class="shimmer shimmer-inline"></span></div>`;
    }

    _renderMiniLoading() {
        return html`
            <div class="mini-card mini-card--loading">
                <div class="avatar avatar--placeholder"></div>
                <div class="member-info">
                    <div class="shimmer shimmer-username"></div>
                    <div class="shimmer shimmer-displayname"></div>
                </div>
            </div>
        `;
    }

    _renderInline() {
        const inactive = !this._isActive();
        return html`
            <div class="inline-card">
                <a href="${this._getProfileUrl()}"
                   class="inline-link${inactive ? ' inline-link--inactive' : ''}"
                   target="${this._getLinkTarget()}"
                   rel="${this._getLinkRel()}"
                   title="${inactive ? 'Inactive member' : this._getDisplayName()}">
                    @${this.username}
                </a>${inactive ? html`<span class="inline-inactive-icon" title="Disabled profile" aria-label="Disabled profile">encrypted_off</span>` : ''}
            </div>
        `;
    }

    _renderMini() {
        const inactive = !this._isActive();
        const displayName = this._getDisplayName();
        const linkTitle = inactive
            ? `Inactive member — ${displayName}`
            : `View ${displayName}'s profile`;
        return html`
            <a href="${this._getProfileUrl()}"
               class="mini-card"
               target="${this._getLinkTarget()}"
               rel="${this._getLinkRel()}"
               title="${linkTitle}">
                <div class="avatar-wrapper">
                    <div class="avatar${inactive ? ' avatar--inactive' : ''}">
                        ${this._renderAvatar()}
                    </div>
                    ${inactive ? html`<span class="inactive-badge" title="Disabled profile" aria-label="Disabled profile"><span class="inactive-badge-icon">encrypted_off</span></span>` : ''}
                </div>
                <div class="member-info">
                    <div class="username${inactive ? ' username--inactive' : ''}">@${this.username}</div>
                    <div class="display-name">${displayName}</div>
                </div>
            </a>
        `;
    }

    render() {
        if (!this.username) {
            return html`<div style="color:red;font-style:italic;">Error: username is required for dms-member-card</div>`;
        }

        if (this._loading) {
            return this.state === 'mini' ? this._renderMiniLoading() : this._renderInlineLoading();
        }

        switch (this.state) {
            case 'inline': return this._renderInline();
            case 'mini':   return this._renderMini();
            default:
                console.warn(`Unknown state "${this.state}" for dms-member-card, defaulting to inline`);
                return this._renderInline();
        }
    }
}

customElements.define('dms-member-card', DmsMemberCard);
export { DmsMemberCard };
