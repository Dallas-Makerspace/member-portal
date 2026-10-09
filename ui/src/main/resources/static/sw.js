// Service Worker for Dallas Makerspace Member Portal
// Version: 1.0.0

const CACHE_VERSION = 'v26';
const STATIC_CACHE = `dms-static-${CACHE_VERSION}`;
const DYNAMIC_CACHE = `dms-dynamic-${CACHE_VERSION}`;
const MAX_CACHE_AGE_DAYS = 7;

// Assets to precache on install
const PRECACHE_ASSETS = [
  '/',
  '/offline',
  '/static/styles/global.css',
  '/static/styles/index.css',
  '/static/styles/profile.css',
  '/static/js/app.js',
  '/static/js/dms-page-loader.js',
  '/static/icons/icon-192x192.png',
  '/static/icons/icon-512x512.png',
  'https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css',
  'https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js',
  'https://fonts.googleapis.com/css2?family=Outfit:wght@400;700;800;900&display=swap'
];

// Install event: precache essential assets
self.addEventListener('install', (event) => {
  console.log('[ServiceWorker] Install event');
  event.waitUntil(
    caches.open(STATIC_CACHE).then((cache) => {
      console.log('[ServiceWorker] Precaching app shell');
      return cache.addAll(PRECACHE_ASSETS).catch((err) => {
        console.error('[ServiceWorker] Precache failed:', err);
        // Continue even if some assets fail to cache
        return Promise.resolve();
      });
    })
  );
  // Force the waiting service worker to become the active service worker
  self.skipWaiting();
});

// Activate event: clean up old caches
self.addEventListener('activate', (event) => {
  console.log('[ServiceWorker] Activate event');
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cacheName) => {
          if (cacheName !== STATIC_CACHE && cacheName !== DYNAMIC_CACHE) {
            console.log('[ServiceWorker] Removing old cache:', cacheName);
            return caches.delete(cacheName);
          }
        })
      );
    })
  );
  // Take control of all pages immediately
  return self.clients.claim();
});

// Fetch event: route requests to appropriate cache strategy
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Only handle GET requests
  if (request.method !== 'GET') {
    return;
  }

  // Only handle http/https — skip chrome-extension:// and other schemes
  if (!url.protocol.startsWith('http')) {
    return;
  }

  // PostHog analytics: pass through without caching
  if (url.hostname.includes('posthog.com')) {
    return;
  }

  // API calls: Network Only (no caching for member data)
  if (url.pathname.startsWith('/backend-api/')) {
    event.respondWith(networkOnly(request));
    return;
  }

  // Service worker itself: always fetch fresh
  if (url.pathname === '/sw.js') {
    event.respondWith(fetch(request));
    return;
  }

  // CSS and JS: Stale While Revalidate — serve cached immediately, refresh cache in background
  if (
    url.pathname.match(/\.(css|js)$/)
  ) {
    event.respondWith(staleWhileRevalidate(request, STATIC_CACHE));
    return;
  }

  // Other static assets (images, fonts, icons): Cache First — these rarely change
  if (
    url.pathname.startsWith('/static/') ||
    url.pathname.match(/\.(png|jpg|jpeg|gif|svg|woff|woff2|ttf|eot|ico)$/)
  ) {
    event.respondWith(cacheFirst(request, STATIC_CACHE));
    return;
  }

  // External CDN resources (Bootstrap, fonts, Lit): Stale While Revalidate
  if (
    url.origin !== location.origin &&
    (url.hostname.includes('cdn.jsdelivr.net') ||
     url.hostname.includes('esm.sh') ||
     url.hostname.includes('fonts.googleapis.com') ||
     url.hostname.includes('fonts.gstatic.com'))
  ) {
    event.respondWith(staleWhileRevalidate(request, STATIC_CACHE));
    return;
  }

  // HTML pages: Network First with Cache Fallback
  event.respondWith(networkFirst(request, DYNAMIC_CACHE));
});

// Cache First: check cache first, fall back to network
async function cacheFirst(request, cacheName) {
  try {
    const cachedResponse = await caches.match(request);
    if (cachedResponse) {
      // Check if cache is stale (older than MAX_CACHE_AGE_DAYS)
      const cachedDate = new Date(cachedResponse.headers.get('date'));
      const now = new Date();
      const ageInDays = (now - cachedDate) / (1000 * 60 * 60 * 24);

      if (ageInDays < MAX_CACHE_AGE_DAYS) {
        return cachedResponse;
      }
    }

    // Cache miss or stale: fetch from network
    const networkResponse = await fetch(request);
    if (networkResponse && networkResponse.status === 200) {
      const cache = await caches.open(cacheName);
      cache.put(request, networkResponse.clone());
    }
    return networkResponse;
  } catch (error) {
    console.error('[ServiceWorker] Cache First failed:', error);
    // Return cached response even if stale
    const cachedResponse = await caches.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }
    throw error;
  }
}

// Network First: try network first, fall back to cache
async function networkFirst(request, cacheName) {
  try {
    const networkResponse = await fetch(request);
    if (networkResponse && networkResponse.status === 200) {
      const cache = await caches.open(cacheName);
      cache.put(request, networkResponse.clone());
    }
    return networkResponse;
  } catch (error) {
    console.log('[ServiceWorker] Network First: network failed, checking cache');
    const cachedResponse = await caches.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }

    // No cache available: return offline page
    if (request.mode === 'navigate') {
      const offlineResponse = await caches.match('/offline');
      if (offlineResponse) {
        return offlineResponse;
      }
    }

    throw error;
  }
}

// Stale While Revalidate: serve from cache immediately, update cache in background
async function staleWhileRevalidate(request, cacheName) {
  const cachedResponse = await caches.match(request);

  const fetchPromise = fetch(request).then((networkResponse) => {
    if (networkResponse && networkResponse.status === 200) {
      const responseToCache = networkResponse.clone();
      caches.open(cacheName).then((cache) => cache.put(request, responseToCache));
    }
    return networkResponse;
  });

  // Return cached response immediately if available, otherwise wait for network
  return cachedResponse || fetchPromise;
}

// Network Only: always fetch from network (for API calls)
async function networkOnly(request) {
  return fetch(request);
}

// Listen for messages from the client
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});
