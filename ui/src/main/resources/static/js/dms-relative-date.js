import { LitElement, html, css } from 'https://esm.sh/lit@3.3.0';

/**
 * DmsRelativeDate - A web component for displaying relative dates.
 *
 * @element dms-relative-date
 * @prop {String} timestamp - ISO 8601 date/time string (required)
 * @prop {String} prefix - Optional prefix text (e.g., "(")
 * @prop {String} suffix - Optional suffix text (e.g., ")")
 * @prop {Boolean} relativeOnly - If true, only show when date is relative (Today, Yesterday, X days/weeks ago)
 *
 * Output examples:
 * - "Today", "Yesterday", "3 days ago", "2 weeks ago", "Dec 18"
 *
 * Usage:
 * <dms-relative-date timestamp="2025-12-18T00:11:00"></dms-relative-date>
 * <dms-relative-date timestamp="2025-12-18" prefix="(" suffix=")"></dms-relative-date>
 * <dms-relative-date timestamp="2025-12-18" relativeOnly="true"></dms-relative-date>
 */
class DmsRelativeDate extends LitElement {
    static properties = {
        timestamp: { type: String },
        prefix: { type: String },
        suffix: { type: String },
        relativeOnly: { type: Boolean },
        _showFull: { type: Boolean, state: true }
    };

    static styles = css`
        :host {
            display: inline;
        }

        .relative-date {
            color: #6c757d;
            font-size: inherit;
            text-decoration: underline dotted;
            text-underline-offset: 2px;
            cursor: pointer;
        }

        .full-date {
            color: #6c757d;
            font-size: inherit;
        }
    `;

    constructor() {
        super();
        this.timestamp = '';
        this.prefix = '';
        this.suffix = '';
        this.relativeOnly = false;
        this._showFull = false;
    }

    _toggleDisplay() {
        this._showFull = !this._showFull;
    }

    /**
     * Parse the timestamp and return a Date object.
     * Timestamps WITHOUT timezone info are interpreted as Chicago time.
     * Handles multiple formats:
     * - ISO 8601: "2025-12-18T00:11:00"
     * - SQL format: "2025-12-18 00:11:00"
     * - Date only: "2025-12-18"
     * - With timezone: "2025-12-18T00:11:00Z" or "2025-12-18T00:11:00-06:00"
     */
    _parseTimestamp() {
        if (!this.timestamp) return null;

        try {
            // Normalize format: replace space with T for SQL-style dates
            let normalizedTimestamp = this.timestamp.trim();
            if (normalizedTimestamp.includes(' ') && !normalizedTimestamp.includes('T')) {
                normalizedTimestamp = normalizedTimestamp.replace(' ', 'T');
            }

            // If timestamp has explicit timezone, parse directly
            const hasTimezone = normalizedTimestamp.endsWith('Z') ||
                /[+-]\d{2}:?\d{2}$/.test(normalizedTimestamp);

            if (hasTimezone) {
                const date = new Date(normalizedTimestamp);
                return isNaN(date.getTime()) ? null : date;
            }

            // No timezone specified - interpret as Chicago time
            const match = normalizedTimestamp.match(
                /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/
            );

            if (!match) {
                const date = new Date(normalizedTimestamp);
                return isNaN(date.getTime()) ? null : date;
            }

            const [, year, month, day, hour = '12', minute = '0', second = '0'] = match;

            // Create initial UTC timestamp with these component values
            const utcGuess = Date.UTC(+year, +month - 1, +day, +hour, +minute, +second);

            // Determine Chicago's offset by seeing how this UTC time displays in Chicago
            const formatter = new Intl.DateTimeFormat('en-US', {
                timeZone: 'America/Chicago',
                year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit',
                hour12: false
            });

            const chicagoParts = formatter.formatToParts(new Date(utcGuess));
            const getPart = (type) => parseInt(chicagoParts.find(p => p.type === type)?.value || '0');

            // Calculate offset: difference between intended Chicago time and what UTC shows as in Chicago
            const intendedUtc = Date.UTC(+year, +month - 1, +day, +hour, +minute, +second);
            const actualChicagoUtc = Date.UTC(
                getPart('year'), getPart('month') - 1, getPart('day'),
                getPart('hour'), getPart('minute'), getPart('second')
            );
            const offsetMs = intendedUtc - actualChicagoUtc;

            // Adjust to get the UTC time that represents the intended Chicago time
            return new Date(utcGuess + offsetMs);
        } catch {
            return null;
        }
    }

    /**
     * Get today's date in Chicago timezone (date only, no time)
     */
    _getTodayInChicago() {
        const now = new Date();
        const chicagoTime = new Date(now.toLocaleString('en-US', { timeZone: 'America/Chicago' }));
        return new Date(chicagoTime.getFullYear(), chicagoTime.getMonth(), chicagoTime.getDate());
    }

    /**
     * Get the event date in Chicago timezone (date only, no time)
     */
    _getEventDateInChicago(date) {
        const chicagoTime = new Date(date.toLocaleString('en-US', { timeZone: 'America/Chicago' }));
        return new Date(chicagoTime.getFullYear(), chicagoTime.getMonth(), chicagoTime.getDate());
    }

    /**
     * Calculate days between two dates
     */
    _daysBetween(date1, date2) {
        const msPerDay = 24 * 60 * 60 * 1000;
        return Math.floor((date2 - date1) / msPerDay);
    }

    /**
     * Returns true when the raw timestamp string contains a seconds component
     * (i.e. matches HH:MM:SS). Date-only strings and minute-only strings return false,
     * so we never display a misleading ":00" for inputs that don't carry seconds.
     */
    _hasSeconds() {
        if (!this.timestamp) return false;
        return /\d{2}:\d{2}:\d{2}/.test(this.timestamp.trim());
    }

    /**
     * Format the full human-readable timestamp in Chicago timezone
     * e.g., "December 31, 2025 at 11:30 PM" or "… at 11:30:45 PM" when seconds are present
     */
    _formatFullTimestamp() {
        const eventDate = this._parseTimestamp();
        if (!eventDate) return 'Invalid date';

        const options = {
            timeZone: 'America/Chicago',
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric',
            hour: 'numeric',
            minute: '2-digit',
            hour12: true
        };
        if (this._hasSeconds()) {
            options.second = '2-digit';
        }

        return new Intl.DateTimeFormat('en-US', options).format(eventDate);
    }


    /**
     * Format the relative date string
     * @returns {Object} { text: string, isRelative: boolean }
     */
    _formatRelativeDate() {
        const eventDate = this._parseTimestamp();
        if (!eventDate) return { text: 'Invalid date', isRelative: false };

        const today = this._getTodayInChicago();
        const eventDateOnly = this._getEventDateInChicago(eventDate);
        const daysAgo = this._daysBetween(eventDateOnly, today);

        if (daysAgo === 0) {
            return { text: 'Today', isRelative: true };
        } else if (daysAgo === 1) {
            return { text: 'Yesterday', isRelative: true };
        } else if (daysAgo > 1 && daysAgo < 7) {
            return { text: `${daysAgo} days ago`, isRelative: true };
        } else if (daysAgo >= 7 && daysAgo < 30) {
            const weeks = Math.floor(daysAgo / 7);
            return { text: `${weeks} week${weeks > 1 ? 's' : ''} ago`, isRelative: true };
        } else if (daysAgo < 0) {
            // Future date
            const daysUntil = Math.abs(daysAgo);
            if (daysUntil === 1) {
                return { text: 'Tomorrow', isRelative: true };
            } else if (daysUntil < 7) {
                return { text: `in ${daysUntil} days`, isRelative: true };
            }
        }

        // For dates more than 30 days ago or far in future, show "MMM d" format
        const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                        'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        const chicagoDate = this._getEventDateInChicago(eventDate);
        return { text: `${months[chicagoDate.getMonth()]} ${chicagoDate.getDate()}`, isRelative: false };
    }

    render() {
        if (!this.timestamp) {
            return html`
                <span style="color: red; font-style: italic;">
                    Error: timestamp is required for dms-relative-date
                </span>
            `;
        }

        const { text, isRelative } = this._formatRelativeDate();

        // If relativeOnly is true and this is not a relative date, render nothing
        if (this.relativeOnly && !isRelative) {
            return html``;
        }

        const fullTimestamp = this._formatFullTimestamp();

        if (this._showFull) {
            return html`
                <span class="full-date" @click="${this._toggleDisplay}">
                    ${this.prefix}${fullTimestamp}${this.suffix}
                </span>
            `;
        }

        return html`
            <span class="relative-date"
                  title="${fullTimestamp}"
                  @click="${this._toggleDisplay}">
                ${this.prefix}${text}${this.suffix}
            </span>
        `;
    }
}

customElements.define('dms-relative-date', DmsRelativeDate);
export { DmsRelativeDate };
