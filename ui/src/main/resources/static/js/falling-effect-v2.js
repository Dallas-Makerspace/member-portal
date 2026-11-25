/**
 * Falling Effect - Self-contained seasonal falling animation
 * Supports configurable characters, images, colors, and timing
 * Starts by itself on portal pages, by date or URL hash. The TV dashboard loads it with data-manual
 * and calls FallingEffect.sync(). Keep the syntax old enough for Chromecast browsers.
 */
(function() {
    'use strict';

    // ============================================================
    // Theme Configurations
    // ============================================================

    // Dates are Central time. Hash overrides are on each theme below.
    const MANUAL = !!document.currentScript && document.currentScript.hasAttribute('data-manual');

    function centralToday() {
        const now = new Date();
        try {
            const parts = now.toLocaleDateString('en-US', { timeZone: 'America/Chicago' }).replace(/[^0-9/]/g, '').split('/');
            return { month: Number(parts[0]), day: Number(parts[1]), year: Number(parts[2]) };
        } catch (e) {
            return { month: now.getMonth() + 1, day: now.getDate(), year: now.getFullYear() };
        }
    }

    // Thanksgiving is the fourth Thursday of November; the effect also runs the day before.
    function thanksgivingRange(year) {
        const day = 22 + (4 - new Date(year, 10, 1).getDay() + 7) % 7;
        return { startMonth: 11, startDay: day - 1, endMonth: 11, endDay: day };
    }

    const dateRanges = {
        halloween: [{ startMonth: 10, startDay: 30, endMonth: 10, endDay: 31 }],
        thanksgiving: [thanksgivingRange],
        christmas: [{ startMonth: 12, startDay: 24, endMonth: 12, endDay: 25 }],
        winter: [
            { startMonth: 12, startDay: 24, endMonth: 12, endDay: 31 },
            { startMonth: 1, startDay: 1, endMonth: 1, endDay: 1 }
        ],
        valentines: [{ startMonth: 2, startDay: 13, endMonth: 2, endDay: 14 }],
        independence: [{ startMonth: 7, startDay: 3, endMonth: 7, endDay: 4 }]
    };

    const THEMES = {
        halloween: {
            name: 'halloween',
            characters: ['🎃', '👻', '🦇', '🍬'],
            hashOverride: '#halloween',
            elementCount: 6,
            containerClass: 'falling-effect-container',
            elementClass: 'falling-element',
            styles: {
                color: 'rgba(255, 140, 0, 0.9)',
                textShadow: '0 0 4px rgba(120, 60, 160, 0.5)',
                fontSize: { min: 14, max: 26 },
                animationDuration: { min: 8, max: 14 },
                animationDelay: { max: 8 }
            },
            dateRanges: dateRanges.halloween || []
        },
        thanksgiving: {
            name: 'thanksgiving',
            characters: ['🦃', '🍗', '🌽', '🍁'],
            hashOverride: '#thanksgiving',
            elementCount: 6,
            containerClass: 'falling-effect-container',
            elementClass: 'falling-element',
            styles: {
                color: 'rgba(205, 105, 30, 0.9)',
                textShadow: '0 0 4px rgba(160, 82, 45, 0.4)',
                fontSize: { min: 18, max: 32 },
                animationDuration: { min: 8, max: 14 },
                animationDelay: { max: 8 }
            },
            dateRanges: dateRanges.thanksgiving || []
        },
        // Listed before winter so it wins on the days they share.
        christmas: {
            name: 'christmas',
            characters: ['🎄', '🎁', '🎅', '❄️'],
            hashOverride: '#christmas',
            elementCount: 8,
            containerClass: 'falling-effect-container',
            elementClass: 'falling-element',
            styles: {
                color: 'rgba(255, 255, 255, 0.9)',
                textShadow: '0 0 4px rgba(200, 220, 255, 0.8)',
                fontSize: { min: 14, max: 26 },
                animationDuration: { min: 8, max: 14 },
                animationDelay: { max: 8 }
            },
            dateRanges: dateRanges.christmas || []
        },
        winter: {
            name: 'winter',
            characters: ['❄', '❅', '❆', '✦', '✧'],
            hashOverride: '#snow',
            elementCount: 50,
            containerClass: 'falling-effect-container',
            elementClass: 'falling-element',
            styles: {
                color: 'rgba(255, 255, 255, 0.8)',
                textShadow: '0 0 3px rgba(200, 220, 255, 0.8)',
                fontSize: { min: 10, max: 20 },
                animationDuration: { min: 8, max: 13 },
                animationDelay: { max: 10 }
            },
            dateRanges: dateRanges.winter || []
        },
        valentines: {
            name: 'valentines',
            characters: ['❤️', '💝', '🌹', '🍫'],
            hashOverride: '#hearts',
            elementCount: 5,
            containerClass: 'falling-effect-container',
            elementClass: 'falling-element',
            styles: {
                color: 'rgba(255, 105, 180, 0.9)',
                textShadow: '0 0 5px rgba(255, 20, 147, 0.6)',
                fontSize: { min: 12, max: 24 },
                animationDuration: { min: 6, max: 12 },
                animationDelay: { max: 8 }
            },
            dateRanges: dateRanges.valentines || []
        },
        independence: {
            name: 'independence',
            characters: ['⭐', '🎇', '✨', '🎉'],
            images: ['/static/img/us-flag.svg', '/static/img/america250.svg'],
            hashOverride: '#fireworks',
            elementCount: 5,
            containerClass: 'falling-effect-container',
            elementClass: 'falling-element',
            styles: {
                colorVariants: [
                    'rgba(255, 50, 50, 0.9)',
                    'rgba(255, 255, 255, 0.9)',
                    'rgba(50, 100, 255, 0.9)'
                ],
                textShadow: '0 0 4px rgba(255, 215, 0, 0.8)',
                fontSize: { min: 14, max: 28 },
                animationDuration: { min: 5, max: 10 },
                animationDelay: { max: 6 }
            },
            dateRanges: dateRanges.independence || []
        }
    };

    // ============================================================
    // Date/Hash Detection
    // ============================================================

    function isDateInRange(rule) {
        const today = centralToday();
        const range = typeof rule === 'function' ? rule(today.year) : rule;
        const month = today.month;
        const day = today.day;

        if (range.startMonth === range.endMonth) {
            return month === range.startMonth && day >= range.startDay && day <= range.endDay;
        }

        return (month === range.startMonth && day >= range.startDay) ||
        (month === range.endMonth && day <= range.endDay);
    }

    function isThemeActiveByDate(theme) {
        return theme.dateRanges.some(range => isDateInRange(range));
    }

    function isThemeActiveByHash(theme) {
        return window.location.hash === theme.hashOverride;
    }

    function getActiveTheme() {
        // Hash overrides take precedence
        for (const key in THEMES) {
            if (isThemeActiveByHash(THEMES[key])) {
                return THEMES[key];
            }
        }

        // Then check date-based activation
        for (const key in THEMES) {
            if (isThemeActiveByDate(THEMES[key])) {
                return THEMES[key];
            }
        }

        return null;
    }

    // ============================================================
    // Falling Effect Engine
    // ============================================================

    const FallingEffect = {
        config: null,
        container: null,
        options: {},

        /** Shows today's theme, or the named one. Call it any time: it only acts when the theme changes. */
        sync: function(name, options) {
            const theme = name ? (THEMES[name] || null) : getActiveTheme();
            if (theme === this.config) return;
            this.destroy();
            this.config = null;
            this.init(theme, options);
        },

        init: function(config, options) {
            if (!config) return;
            this.config = config;
            this.options = options || {};
            this.injectStyles();
            this.createElements();
        },

        generateCSS: function() {
            const tv = !!this.options.parent;
            const styles = this.config.styles;
            const containerClass = this.config.containerClass;
            const elementClass = this.config.elementClass;

            const colorCSS = styles.colorVariants
                ? `color: var(--falling-element-color, ${styles.colorVariants[0]});`
                : `color: ${styles.color};`;

            return `
        .${containerClass} {
          position: ${tv ? 'absolute' : 'fixed'};
          top: 0;
          left: 0;
          width: 100%;
          height: 100%;
          pointer-events: none;
          z-index: 1000;
          overflow: hidden;
        }
        .${elementClass} {
          position: absolute;
          top: ${tv ? '-8vh' : '-50px'};
          ${colorCSS}
          text-shadow: ${tv ? 'none' : styles.textShadow};
          user-select: none;
          animation: falling-animation linear infinite;
        }
        @keyframes falling-animation {
          0% {
            transform: translateY(0) rotate(0deg);
            opacity: 1;
          }
          100% {
            transform: translateY(${tv ? '108vh' : '100vh'}) rotate(360deg);
            opacity: 0.3;
          }
        }
      `;
        },

        injectStyles: function() {
            const style = document.createElement('style');
            style.id = `falling-effect-styles-${this.config.name}`;
            style.textContent = this.generateCSS();
            document.head.appendChild(style);
        },

        // On the TV sizes are in rem, so they scale with the page (1rem = 16px at 1920 wide).
        size: function(px) {
            return this.options.scale ? (px * this.options.scale / 16) + 'rem' : px + 'px';
        },

        count: function() {
            const count = this.config.elementCount * (this.options.countScale || 1);
            return this.options.maxCount ? Math.min(count, this.options.maxCount) : count;
        },

        createElements: function() {
            const config = this.config;
            const container = document.createElement('div');
            container.className = config.containerClass;
            container.setAttribute('aria-hidden', 'true');
            container.id = `falling-effect-${config.name}`;

            const characters = config.characters || [];
            const images = config.images || [];
            const totalItems = characters.length + images.length;

            // Seed one of each image to guarantee all images appear, then fill randomly
            const pool = images.map(src => ({ type: 'img', src }));
            if (this.options.parent) characters.forEach(char => pool.push({ type: 'char', char }));
            const remaining = Math.max(0, this.count() - pool.length);
            for (let i = 0; i < remaining; i++) {
                const idx = Math.floor(Math.random() * totalItems);
                if (idx < characters.length) {
                    pool.push({ type: 'char', char: characters[idx] });
                } else {
                    pool.push({ type: 'img', src: images[idx - characters.length] });
                }
            }
            // Fisher-Yates shuffle so seeded images don't always appear first
            for (let i = pool.length - 1; i > 0; i--) {
                const j = Math.floor(Math.random() * (i + 1));
                [pool[i], pool[j]] = [pool[j], pool[i]];
            }

            for (let i = 0; i < pool.length; i++) {
                let element;
                const item = pool[i];

                if (item.type === 'char') {
                    element = document.createElement('span');
                    element.textContent = item.char;
                } else {
                    element = document.createElement('img');
                    element.src = item.src;
                    element.alt = '';
                }

                element.className = config.elementClass;
                element.style.left = Math.random() * 100 + '%';

                const size = Math.random() *
                (config.styles.fontSize.max - config.styles.fontSize.min) +
                config.styles.fontSize.min;

                if (element.tagName === 'IMG') {
                    element.style.width = this.size(size);
                    element.style.height = 'auto';
                } else {
                    element.style.fontSize = this.size(size);
                }

                const duration = Math.random() *
                (config.styles.animationDuration.max - config.styles.animationDuration.min) +
                config.styles.animationDuration.min;
                element.style.animationDuration = duration + 's';

                element.style.animationDelay = Math.random() * config.styles.animationDelay.max + 's';

                if (config.styles.colorVariants && element.tagName !== 'IMG') {
                    const colorIndex = i % config.styles.colorVariants.length;
                    element.style.color = config.styles.colorVariants[colorIndex];
                }

                container.appendChild(element);
            }

            (this.options.parent || document.body).appendChild(container);
            this.container = container;
        },

        destroy: function() {
            if (this.container) {
                this.container.remove();
                this.container = null;
            }
            const styleEl = document.getElementById(`falling-effect-styles-${this.config ? this.config.name : ''}`);
            if (styleEl) {
                styleEl.remove();
            }
        }
    };

    // ============================================================
    // Auto-initialize
    // ============================================================

    function init() {
        const activeTheme = getActiveTheme();
        if (activeTheme) {
            FallingEffect.init(activeTheme);
        }
    }

    if (MANUAL) {
        // The page starts it.
    } else if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Expose for manual control if needed
    window.FallingEffect = FallingEffect;
})();
