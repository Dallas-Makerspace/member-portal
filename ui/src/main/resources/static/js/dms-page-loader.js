/**
 * Page Loading Indicator
 * A lightweight, self-contained loading bar that displays at the top of the page
 * during navigation events.
 */
(function() {
    // Create and inject the loading bar element
    const createLoader = () => {
        const loader = document.createElement('div');
        loader.id = 'yt-style-loader';

        // Apply styles directly to the element
        loader.style.position = 'fixed';
        loader.style.top = '0';
        loader.style.left = '0';
        loader.style.height = '3px';
        loader.style.width = '0%';
        loader.style.backgroundColor = '#ff0000'; // YouTube red
        loader.style.zIndex = '9999';
        loader.style.transition = 'width 0.2s ease-out';
        loader.style.boxShadow = '0 1px 2px rgba(0,0,0,0.1)';
        loader.style.display = 'none';

        document.body.appendChild(loader);
        return loader;
    };

    // Get the loader element or create it if it doesn't exist
    const getLoader = () => {
        return document.getElementById('yt-style-loader') || createLoader();
    };

    // Animation steps for the loader
    const animateLoader = (loader) => {
        // Show the loader
        loader.style.display = 'block';

        // Initial quick progress to 30%
        setTimeout(() => {
            loader.style.width = '30%';
        }, 50);

        // Progress to 50% after a delay
        setTimeout(() => {
            loader.style.width = '50%';
        }, 300);

        // Progress to 70% after another delay
        setTimeout(() => {
            loader.style.width = '70%';
        }, 1000);

        // Progress to 85% and slow down
        setTimeout(() => {
            loader.style.width = '85%';
        }, 2000);

        // The loader will stay at 85% until navigation completes
    };

    // Complete the loader animation
    const completeLoader = (loader) => {
        // Progress to 100% quickly
        loader.style.width = '100%';

        // After reaching 100%, hide the loader
        setTimeout(() => {
            loader.style.display = 'none';
            loader.style.width = '0%';
        }, 300);
    };

    // Start the loader
    const startLoader = () => {
        const loader = getLoader();
        animateLoader(loader);
        return loader;
    };

    // Store navigation state in sessionStorage to handle page reloads and back navigation
    const setNavigatingState = (isNavigating) => {
        try {
            if (isNavigating) {
                sessionStorage.setItem('yt_loader_navigating', 'true');
            } else {
                sessionStorage.removeItem('yt_loader_navigating');
            }
        } catch (e) {
            // Handle potential SecurityError if sessionStorage is not available
            console.warn('Session storage not available for loader state');
        }
    };

    const getNavigatingState = () => {
        try {
            return sessionStorage.getItem('yt_loader_navigating') === 'true';
        } catch (e) {
            return false;
        }
    };

    // Handle page navigation events
    const setupNavigationListeners = () => {
        let activeLoader = null;
        let isNavigating = getNavigatingState();

        // If we were in the middle of navigation (back button case), complete the loader
        if (isNavigating) {
            activeLoader = getLoader();
            completeLoader(activeLoader);
            setNavigatingState(false);
        }

        // Listen for navigation events
        document.addEventListener('click', (e) => {
            // Check if the click is on a link that leads to a different page
            if (e.defaultPrevented) return;
            const link = e.target.closest('a');
            const rawHref = link && link.getAttribute('href');
            if (link && link.href && rawHref && !rawHref.startsWith('#') &&
            !link.href.startsWith('javascript:') &&
            link.hostname === window.location.hostname &&
            !e.ctrlKey && !e.metaKey) {
                if (!isNavigating) {
                    isNavigating = true;
                    setNavigatingState(true);
                    activeLoader = startLoader();
                }
            }
        });

        // Listen for form submissions
        document.addEventListener('submit', (e) => {
            if (!isNavigating) {
                isNavigating = true;
                setNavigatingState(true);
                activeLoader = startLoader();
            }
        });

        // Handle history navigation (back/forward)
        window.addEventListener('popstate', () => {
            if (!isNavigating) {
                isNavigating = true;
                setNavigatingState(true);
                activeLoader = startLoader();
            }
        });

        // Reset on page load complete
        window.addEventListener('pageshow', (e) => {
            // The pageshow event fires after load and works better with bfcache
            if (isNavigating && activeLoader) {
                completeLoader(activeLoader);
                isNavigating = false;
                setNavigatingState(false);
            }

            // Handle case when the page is loaded from back/forward cache
            if (e.persisted) {
                const loader = getLoader();
                completeLoader(loader);
                isNavigating = false;
                setNavigatingState(false);
            }
        });

        // Handle page unload (navigation away)
        window.addEventListener('beforeunload', () => {
            if (!isNavigating) {
                isNavigating = true;
                setNavigatingState(true);
                activeLoader = startLoader();
            }
        });

        // Mock completion for demonstration purposes
        // Remove this in production, as actual page loads will handle this naturally
        window.mockCompletePage = () => {
            if (isNavigating && activeLoader) {
                completeLoader(activeLoader);
                isNavigating = false;
                setNavigatingState(false);
            }
        };
    };

    // Initialize when the DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', setupNavigationListeners);
    } else {
        setupNavigationListeners();
    }
})();