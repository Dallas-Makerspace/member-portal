/**
 * Falling Effect - Self-contained seasonal falling animation
 * Supports configurable characters, images, colors, and timing
 * Auto-initializes based on date ranges or URL hash overrides
 */
(function() {
    'use strict';

    // ============================================================
    // Theme Configurations
    // ============================================================

    // Date ranges are defined in app.js via window.SEASONAL_DATE_RANGES
    const dateRanges = window.SEASONAL_DATE_RANGES || {};

    const THEMES = {
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

    function isDateInRange(range) {
        const now = new Date();
        const month = now.getMonth() + 1;
        const day = now.getDate();

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

        init: function(config) {
            if (!config) return;
            this.config = config;
            this.injectStyles();
            this.createElements();
        },

        generateCSS: function() {
            const styles = this.config.styles;
            const containerClass = this.config.containerClass;
            const elementClass = this.config.elementClass;

            const colorCSS = styles.colorVariants
                ? `color: var(--falling-element-color, ${styles.colorVariants[0]});`
                : `color: ${styles.color};`;

            return `
        .${containerClass} {
          position: fixed;
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
          top: -50px;
          ${colorCSS}
          text-shadow: ${styles.textShadow};
          user-select: none;
          animation: falling-animation linear infinite;
        }
        @keyframes falling-animation {
          0% {
            transform: translateY(0) rotate(0deg);
            opacity: 1;
          }
          100% {
            transform: translateY(100vh) rotate(360deg);
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
            const remaining = Math.max(0, config.elementCount - pool.length);
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
                    element.style.width = size + 'px';
                    element.style.height = 'auto';
                } else {
                    element.style.fontSize = size + 'px';
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

            document.body.appendChild(container);
            this.container = container;
        },

        destroy: function() {
            if (this.container) {
                this.container.remove();
                this.container = null;
            }
            const styleEl = document.getElementById(`falling-effect-styles-${this.config?.name}`);
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

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Expose for manual control if needed
    window.FallingEffect = FallingEffect;
})();
