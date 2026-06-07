/**
 * <dms-timelapse-view stream="id" [buffer-secs="60"] [interval="1500"] [variant="thumbnail"]>
 *
 * Attributes:
 *   stream      — chrono-ring stream ID (required)
 *   buffer-secs — rolling buffer duration in seconds (default 60)
 *   interval    — live poll interval in ms (default 1500); no longer drives fetch frequency
 *   variant     — "thumbnail" for minimalist single-frame view; absent = live mode
 *
 * URLs are routed through the member-portal proxy at /cameras-api/...
 */

// Polls /cameras-api/streams once every 5s while at least one component is subscribed.
const StreamsPoller = (() => {
  let timer = null;
  let count = 0;
  let polling = false;

  async function poll() {
    if (polling) return;
    polling = true;
    try {
      const res = await fetch('/cameras-api/streams');
      if (!res.ok) return;
      const streams = await res.json();
      document.dispatchEvent(new CustomEvent('cameras-streams-update', { detail: streams }));
    } catch (_) {}
    finally { polling = false; }
  }

  return {
    subscribe()   { if (++count === 1) { poll(); timer = setInterval(poll, 5000); } },
    unsubscribe() { if (--count <= 0) { count = 0; clearInterval(timer); timer = null; } },
  };
})();

// Polls /cameras-api/timelapse once every 10s while at least one component is subscribed.
// Groups frames by stream_id and dispatches 'cameras-timelapse-update' with a Map as detail.
const TimelapsePoller = (() => {
  let timer = null;
  let count = 0;
  let polling = false;

  async function poll() {
    if (polling) return;
    polling = true;
    try {
      const res = await fetch('/cameras-api/timelapse');
      if (!res.ok) return;
      const frames = await res.json();
      const byStream = new Map();
      for (const f of frames) {
        if (!byStream.has(f.stream_id)) byStream.set(f.stream_id, []);
        byStream.get(f.stream_id).push(f);
      }
      document.dispatchEvent(new CustomEvent('cameras-timelapse-update', { detail: byStream }));
    } catch (_) {}
    finally { polling = false; }
  }

  return {
    subscribe()   { if (++count === 1) { poll(); timer = setInterval(poll, 10000); } },
    unsubscribe() { if (--count <= 0) { count = 0; clearInterval(timer); timer = null; } },
  };
})();

// Module-level frame cache shared across all component instances on the page.
// frames are keyed by UUID; the rolling window means old frames will eventually
// stop being requested, so the cache is left unbounded.
const FrameCache = (() => {
  const MAX_CONCURRENT = 4;
  const cache = new Map();   // id → src string
  const queue = [];          // pending frame objects waiting to be loaded
  const queued = new Set();  // ids currently in queue (prevents duplicate entries)
  let active = 0;            // number of in-flight prefetch loads

  function _next() {
    while (active < MAX_CONCURRENT && queue.length) {
      const frame = queue.shift();
      queued.delete(frame.id);
      if (cache.has(frame.id)) continue;
      active++;
      const img = new Image();
      img.onload = () => {
        cache.set(frame.id, img.src);
        active--;
        _next();
      };
      img.onerror = () => {
        active--;
        _next();
      };
      img.src = '/cameras-api/frame/' + encodeURIComponent(frame.id);
    }
  }

  return {
    // Queue frame objects for background loading (skips already-cached/queued ids).
    prefetch(frames) {
      for (const f of frames) {
        if (!cache.has(f.id) && !queued.has(f.id)) { queue.push(f); queued.add(f.id); }
      }
      _next();
    },
    // Manually store a src string (called from preload.onload / scrubImg.onload).
    store(id, src) {
      cache.set(id, src);
    },
    // Returns the cached src string, or undefined on a cache miss.
    get(id) {
      return cache.get(id);
    },
  };
})();

class DmsTimelapseView extends HTMLElement {
  static get observedAttributes() {
    return ['stream', 'buffer-secs', 'interval', 'variant'];
  }

  constructor() {
    super();
    this._root = this.attachShadow({ mode: 'open' });
    this._clockTimer = null;
    this._streamsListener = null;
    this._timelapseListener = null;
    this._mouseupListener = null;
    this._visibilityListener = null;
    this._buffer = [];
    this._scrubbing = false;
    this._connected = false;
    this._onFsChange = null;
    this._rendered = false;
    this._generation = 0;
    this._lastFrameId = null;
    this._lastFrameTimestamp = 0;
    this._lastLiveFrameSrc = null;
    this._scrubFrameId = null;
    this._abortController = null;
    this._scrubImg = null;
  }

  connectedCallback() { if (!this._rendered) this._render(); }
  disconnectedCallback() { this._teardown(); }

  attributeChangedCallback(name, oldValue, newValue) {
    if (!this._rendered) return;
    if (name === 'buffer-secs' || name === 'interval') return;
    if (oldValue === newValue) return;
    this._teardown();
    this._render();
  }

  _teardown() {
    this._generation++;
    clearInterval(this._clockTimer);
    this._clockTimer = null;
    this._scrubbing = false;
    this._rendered = false;
    this._lastFrameId = null;
    this._lastFrameTimestamp = 0;
    this._lastLiveFrameSrc = null;
    this._scrubFrameId = null;
    // Preserve this._connected across re-renders so the badge doesn't flash
    // incorrectly until the next authoritative poll arrives.

    if (this._scrubImg) {
      this._scrubImg.onload = null;
      this._scrubImg.onerror = null;
      this._scrubImg = null;
    }
    if (this._abortController) {
      this._abortController.abort();
      this._abortController = null;
    }
    if (this._streamsListener) {
      document.removeEventListener('cameras-streams-update', this._streamsListener);
      this._streamsListener = null;
      StreamsPoller.unsubscribe();
    }
    if (this._timelapseListener) {
      document.removeEventListener('cameras-timelapse-update', this._timelapseListener);
      this._timelapseListener = null;
      TimelapsePoller.unsubscribe();
    }
    if (this._mouseupListener) {
      document.removeEventListener('mouseup', this._mouseupListener);
      this._mouseupListener = null;
    }
    if (this._visibilityListener) {
      document.removeEventListener('visibilitychange', this._visibilityListener);
      this._visibilityListener = null;
    }
    if (this._onFsChange) {
      this.removeEventListener('fullscreenchange', this._onFsChange);
      this._onFsChange = null;
    }
  }

  _render() {
    const stream = this.getAttribute('stream');
    if (!stream) return;
    this._rendered = true;
    if (this.getAttribute('variant') === 'thumbnail') {
      this._renderThumbnail(stream);
      return;
    }
    this._renderLive(stream);
  }

  // ── Live mode ─────────────────────────────────────────────────────────────

  _renderLive(stream) {
    const myGen = ++this._generation;

    this._root.innerHTML = `
      <style>
        :host { display: block; background: #000; }
        .frame-wrap {
          position: relative; line-height: 0; overflow: hidden;
          aspect-ratio: 16/9;
        }
        img.main { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
        .spinner {
          position: absolute; inset: 0;
          display: flex; align-items: center; justify-content: center;
          color: #888; font-size: .8rem; gap: 8px;
        }
        .spinner.hidden { display: none; }
        .error { color: #f55; font-size: .8rem; word-break: break-word; }
        .badge {
          position: absolute; bottom: 6px; right: 8px;
          background: rgba(0,0,0,.55); color: #0f0; font-size: .65rem;
          padding: 2px 6px; border-radius: 4px; font-family: monospace;
          display: none; align-items: center; gap: 4px;
        }
        .badge.visible { display: flex; }
        .badge.offline { color: #f55; }
        .dot {
          width: 7px; height: 7px; border-radius: 50%; background: #0f0;
          animation: pulse 1.4s ease-in-out infinite;
        }
        .badge.offline .dot { background: #f55; animation: none; }
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
        .ts {
          position: absolute; bottom: 6px; left: 8px; display: none;
          color: #fff; font-size: .65rem; font-family: monospace;
          padding: 2px 6px; border-radius: 4px;
          text-shadow: 0 1px 3px rgba(0,0,0,.9);
          filter: drop-shadow(0 1px 4px rgba(0,0,0,.8));
        }
        .ts.visible { display: block; }
        .ts.stale { color: #aaa; }
        .timeline {
          position: relative; height: 18px; background: #e8e8e8;
          cursor: crosshair; user-select: none; touch-action: none;
          display: none;
        }
        .timeline.visible { display: block; }
        .ticks { position: absolute; inset: 0; }
        .ticks span { position: absolute; top: 0; bottom: 0; width: 1px; background: #4af; opacity: .7; }
        .cursor { position: absolute; top: -2px; bottom: -2px; width: 2px; background: #fff; pointer-events: none; }
        .btn-fs {
          position: absolute; top: 6px; right: 8px;
          background: rgba(0,0,0,.45); border: none; color: #fff;
          border-radius: 4px; padding: 4px 5px; cursor: pointer;
          line-height: 0; display: none;
        }
        .btn-fs.visible { display: block; }
        .btn-fs:hover { background: rgba(0,0,0,.75); }
        :host(:fullscreen), :host(:-webkit-full-screen) {
          display: flex; flex-direction: column; background: #000;
          width: 100vw; height: 100vh;
        }
        :host(:fullscreen) .frame-wrap, :host(:-webkit-full-screen) .frame-wrap {
          flex: 1; aspect-ratio: unset; overflow: hidden;
        }
        :host(:fullscreen) .timeline, :host(:-webkit-full-screen) .timeline {
          flex-shrink: 0;
        }
      </style>
      <div class="frame-wrap">
        <img class="main" style="display:none" alt="live feed"/>
        <img class="preload" style="display:none" alt=""/>
        <div class="spinner"><span>⏳</span><span>Loading…</span></div>
        <div class="ts"></div>
        <div class="badge"><div class="dot"></div><span class="badge-label">LIVE</span></div>
        <button class="btn-fs" title="Enter fullscreen">
          <svg class="icon-enter" viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
            <path d="M1.5 1h4v1.5h-2.5v2.5h-1.5zm9 0h4v4h-1.5v-2.5h-2.5v-1.5zm-9 9h1.5v2.5h2.5v1.5h-4zm10.5 2.5v-2.5h1.5v4h-4v-1.5h2.5z"/>
          </svg>
          <svg class="icon-exit" viewBox="0 0 16 16" width="14" height="14" fill="currentColor" style="display:none">
            <path d="M5.5 0v4h-4v-1.5h2.5v-2.5zm5 0h1.5v2.5h2.5v1.5h-4zm-9 9.5h4v4h-1.5v-2.5h-2.5zm8.5 1.5v-2.5h1.5v4h-4v-1.5h2.5z"/>
          </svg>
        </button>
      </div>
      <div class="timeline" title="Hover to preview · release to resume live">
        <div class="ticks"></div>
        <div class="cursor" style="display:none"></div>
      </div>
    `;

    const mainImg    = this._root.querySelector('img.main');
    const preload    = this._root.querySelector('img.preload');
    const spinner    = this._root.querySelector('.spinner');
    const badge      = this._root.querySelector('.badge');
    const badgeLabel = this._root.querySelector('.badge-label');
    const tsEl       = this._root.querySelector('.ts');
    const timeline   = this._root.querySelector('.timeline');
    const ticksEl    = this._root.querySelector('.ticks');
    const cursor     = this._root.querySelector('.cursor');
    const fsBtn      = this._root.querySelector('.btn-fs');
    const iconEnter  = fsBtn.querySelector('.icon-enter');
    const iconExit   = fsBtn.querySelector('.icon-exit');

    fsBtn.addEventListener('click', () => {
      if (!document.fullscreenElement) {
        this.requestFullscreen().catch(() => {});
      } else {
        document.exitFullscreen();
      }
    });
    this._onFsChange = () => {
      const isFs = !!document.fullscreenElement;
      iconEnter.style.display = isFs ? 'none' : '';
      iconExit.style.display  = isFs ? '' : 'none';
      fsBtn.title = isFs ? 'Exit fullscreen' : 'Enter fullscreen';
      if (!isFs && this.getAttribute('variant') === 'thumbnail') {
        this._teardown();
        this._renderThumbnail(stream);
      }
    };
    this.addEventListener('fullscreenchange', this._onFsChange);

    const bufferMs = () =>
      (parseInt(this.getAttribute('buffer-secs') || '60', 10)) * 1000;

    const updateTicks = (frames) => {
      const now = Date.now();
      const start = now - bufferMs();
      ticksEl.innerHTML = '';
      for (const f of frames) {
        const t = new Date(f.timestamp).getTime();
        const pos = (t - start) / bufferMs();
        if (pos < 0 || pos > 1) continue;
        const tick = document.createElement('span');
        tick.style.left = (pos * 100).toFixed(2) + '%';
        ticksEl.appendChild(tick);
      }
    };

    // Subscribe to the shared timelapse poller for frame updates.
    this._timelapseListener = (e) => {
      if (this._generation !== myGen) return;
      const frames = e.detail.get(stream);
      if (!frames || !frames.length) return;
      this._buffer = frames;
      updateTicks(frames);
      // Prefetch the 12 most recent frames into FrameCache so scrubbing is instant.
      FrameCache.prefetch(frames.slice(-12));

      const latest = frames[frames.length - 1];
      if (latest.id === this._lastFrameId) return;
      this._lastFrameId = latest.id;

      const incomingTs = new Date(latest.timestamp).getTime();
      const src = `/cameras-api/frame/${encodeURIComponent(latest.id)}`;
      preload.onload = () => {
        if (this._generation !== myGen) return;
        // Reject out-of-order frames that arrived after a newer one already displayed.
        if (incomingTs < this._lastFrameTimestamp) return;
        this._lastFrameTimestamp = incomingTs;
        // Store the live-loaded frame into the cache so scrubbing to it is instant.
        FrameCache.store(latest.id, preload.src);
        if (this._scrubbing) return;
        this._lastLiveFrameSrc = preload.src;
        mainImg.src = preload.src;
        mainImg.style.display = '';
        spinner.classList.add('hidden');
        badge.classList.add('visible');
        tsEl.classList.add('visible');
        timeline.classList.add('visible');
        fsBtn.classList.add('visible');
      };
      preload.onerror = () => {};
      preload.src = src;
    };
    document.addEventListener('cameras-timelapse-update', this._timelapseListener);
    TimelapsePoller.subscribe();

    // Scrub interactions
    let scrubRafId = null;

    const onScrub = (clientX) => {
      if (!this._buffer.length) return;
      if (scrubRafId) return;
      scrubRafId = requestAnimationFrame(() => {
        scrubRafId = null;
        if (this._generation !== myGen) return;
        const rect = timeline.getBoundingClientRect();
        const x = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
        cursor.style.left = (x * 100) + '%';
        cursor.style.display = '';
        const targetMs = (Date.now() - bufferMs()) + x * bufferMs();
        let best = null, bestDiff = Infinity;
        for (const f of this._buffer) {
          const diff = Math.abs(new Date(f.timestamp).getTime() - targetMs);
          if (diff < bestDiff) { bestDiff = diff; best = f; }
        }
        if (best) {
          if (best.id === this._scrubFrameId) return;
          this._scrubFrameId = best.id;
          this._scrubbing = true;
          // Use the matched frame's actual server timestamp, not a synthetic approximation.
          tsEl.textContent = new Date(best.timestamp).toLocaleTimeString();
          const cachedSrc = FrameCache.get(best.id);
          if (cachedSrc) {
            // Cache hit — zero network requests, instant display.
            mainImg.src = cachedSrc;
          } else {
            // Cache miss — abandon previous in-flight scrub load, start a new one.
            // Don't set src='' on the old img: that triggers a spurious fetch to the page URL.
            if (this._scrubImg) {
              this._scrubImg.onload = null;
              this._scrubImg.onerror = null;
              this._scrubImg = null;
            }
            const capturedId = best.id;
            const img = new Image();
            this._scrubImg = img;
            img.onload = () => {
              // Close over img (not this._scrubImg) so the correct src is stored even
              // if this._scrubImg was replaced before this load completed.
              FrameCache.store(capturedId, img.src);
              if (this._generation !== myGen) return;
              if (!this._scrubbing || this._scrubFrameId !== capturedId) return;
              mainImg.src = img.src;
            };
            img.onerror = () => { if (this._scrubImg === img) this._scrubImg = null; };
            img.src = '/cameras-api/frame/' + encodeURIComponent(best.id);
          }
        }
      });
    };

    const onRelease = () => {
      if (!this._scrubbing) return;
      this._scrubbing = false;
      this._scrubFrameId = null;
      cursor.style.display = 'none';
      if (this._lastLiveFrameSrc) {
        mainImg.src = this._lastLiveFrameSrc;
      }
      tsEl.textContent = new Date().toLocaleTimeString();
    };

    timeline.addEventListener('pointermove', e => onScrub(e.clientX));
    timeline.addEventListener('pointerdown', e => {
      timeline.setPointerCapture(e.pointerId);
      onScrub(e.clientX);
    });
    timeline.addEventListener('pointerup',     () => onRelease());
    timeline.addEventListener('pointercancel', () => onRelease());

    // Store document-level listeners so _teardown() can remove them.
    this._mouseupListener = () => onRelease();
    document.addEventListener('mouseup', this._mouseupListener);

    this._visibilityListener = () => {
      if (document.visibilityState === 'hidden') onRelease();
    };
    document.addEventListener('visibilitychange', this._visibilityListener);

    timeline.addEventListener('touchstart', e => { e.preventDefault(); onScrub(e.touches[0].clientX); }, { passive: false });
    timeline.addEventListener('touchmove',  e => { e.preventDefault(); onScrub(e.touches[0].clientX); }, { passive: false });
    timeline.addEventListener('touchend', () => onRelease());

    // Wall-clock timer: advance timestamp every second unless scrubbing or offline.
    this._clockTimer = setInterval(() => {
      if (this._generation !== myGen) return;
      if (this._scrubbing) return;
      if (this._connected === false) return;
      if (tsEl.classList.contains('visible')) {
        tsEl.textContent = new Date().toLocaleTimeString();
      }
    }, 1000);

    // Subscribe to the shared streams poller for connected-state updates.
    this._streamsListener = (e) => {
      if (this._generation !== myGen) return;
      const entry = e.detail.find(s => s.id === stream);
      if (!entry) return;
      this._connected = entry.connected;
      badge.classList.toggle('offline', !this._connected);
      badgeLabel.textContent = this._connected ? 'LIVE' : 'OFFLINE';
      tsEl.classList.toggle('stale', !this._connected);
    };
    document.addEventListener('cameras-streams-update', this._streamsListener);
    StreamsPoller.subscribe();
  }

  // ── Thumbnail mode ────────────────────────────────────────────────────────

  async _renderThumbnail(stream) {
    this._rendered = true;
    const myGen = ++this._generation;
    this._abortController = new AbortController();
    const signal = this._abortController.signal;

    this._root.innerHTML = `
      <style>
        :host { display: block; background: #000; }
        .thumb-wrap {
          position: relative; aspect-ratio: 16/9; overflow: hidden; line-height: 0;
        }
        img.thumb { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
        .spinner {
          position: absolute; inset: 0;
          display: flex; align-items: center; justify-content: center;
          color: #888; font-size: .8rem;
        }
        .spinner.hidden { display: none; }
        .status-dot {
          position: absolute; bottom: 6px; right: 8px;
          width: 10px; height: 10px; border-radius: 50%;
          background: #0f0; box-shadow: 0 0 4px rgba(0,200,0,.7);
          animation: pulse 1.4s ease-in-out infinite;
          display: none;
        }
        .status-dot.visible { display: block; }
        .status-dot.offline { background: #f55; box-shadow: 0 0 4px rgba(200,0,0,.7); animation: none; }
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
        .btn-expand {
          position: absolute; top: 50%; left: 50%;
          transform: translate(-50%, -50%);
          background: rgba(0,0,0,.55); border: 2px solid rgba(255,255,255,.55);
          color: #fff; border-radius: 8px; padding: 10px 12px;
          cursor: pointer; line-height: 0; display: none;
        }
        .btn-expand.visible { display: block; }
        .btn-expand:hover { background: rgba(0,0,0,.85); border-color: #fff; }
      </style>
      <div class="thumb-wrap">
        <img class="thumb" style="display:none" alt="camera thumbnail"/>
        <div class="spinner"><span>⏳</span></div>
        <div class="status-dot"></div>
        <button class="btn-expand" title="Enter fullscreen">
          <svg viewBox="0 0 16 16" width="22" height="22" fill="currentColor">
            <path d="M1.5 1h4v1.5h-2.5v2.5h-1.5zm9 0h4v4h-1.5v-2.5h-2.5v-1.5zm-9 9h1.5v2.5h2.5v1.5h-4zm10.5 2.5v-2.5h1.5v4h-4v-1.5h2.5z"/>
          </svg>
        </button>
      </div>
    `;

    const thumbImg  = this._root.querySelector('img.thumb');
    const spinner   = this._root.querySelector('.spinner');
    const statusDot = this._root.querySelector('.status-dot');
    const expandBtn = this._root.querySelector('.btn-expand');

    // Wrap the abort signal in a rejection promise for racing.
    const abortPromise = new Promise((_, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    });

    // One-shot streams listener — stored in this._streamsListener so _teardown()
    // can remove it and call StreamsPoller.unsubscribe() if abort fires first.
    let streamsSettled = false;
    const streamsReady = new Promise((resolve) => {
      const handler = (e) => {
        if (streamsSettled) return;
        streamsSettled = true;
        document.removeEventListener('cameras-streams-update', handler);
        this._streamsListener = null;
        StreamsPoller.unsubscribe();
        resolve(e.detail);
      };
      this._streamsListener = handler;
      document.addEventListener('cameras-streams-update', this._streamsListener);
      StreamsPoller.subscribe();
    });

    // One-shot timelapse listener — stored in this._timelapseListener so _teardown()
    // can remove it and call TimelapsePoller.unsubscribe() if abort fires first.
    let timelapseSettled = false;
    const timelapseReady = new Promise((resolve) => {
      const handler = (e) => {
        if (timelapseSettled) return;
        timelapseSettled = true;
        document.removeEventListener('cameras-timelapse-update', handler);
        this._timelapseListener = null;
        TimelapsePoller.unsubscribe();
        resolve(e.detail);
      };
      this._timelapseListener = handler;
      document.addEventListener('cameras-timelapse-update', this._timelapseListener);
      TimelapsePoller.subscribe();
    });

    try {
      const [streams, framesByStream] = await Promise.all([
        Promise.race([streamsReady, abortPromise]),
        Promise.race([timelapseReady, abortPromise]),
      ]);

      if (this._generation !== myGen) return;

      const entry = streams.find(s => s.id === stream);
      if (entry) {
        this._connected = entry.connected;
        statusDot.classList.toggle('offline', !entry.connected);
        statusDot.classList.add('visible');
      }

      const frames = framesByStream.get(stream);
      if (frames && frames.length) {
        const latest = frames[frames.length - 1];
        thumbImg.onload = () => {
          if (this._generation !== myGen) return;
          thumbImg.style.display = '';
          spinner.classList.add('hidden');
          expandBtn.classList.add('visible');
        };
        thumbImg.onerror = () => {
          if (this._generation !== myGen) return;
          spinner.innerHTML = '<span style="color:#f55">No image</span>';
        };
        thumbImg.src = `/cameras-api/frame/${encodeURIComponent(latest.id)}`;
      } else {
        spinner.innerHTML = '<span>No frames</span>';
      }
    } catch (err) {
      if (err.name === 'AbortError') return;
      if (this._generation !== myGen) return;
      spinner.innerHTML = '<span style="color:#f55">Error</span>';
    }

    // Guard against a stale click from a detached button disrupting the current render.
    expandBtn.addEventListener('click', () => {
      if (this._generation !== myGen) return;
      this._teardown();
      this._rendered = true;
      this._renderLive(stream);
      this.requestFullscreen().catch(() => {});
    });
  }
}


customElements.define('dms-timelapse-view', DmsTimelapseView);
