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
// Stores blob URLs so scrubbing and re-display never trigger network requests.
// Pauses background fetching when the tab is hidden; resumes on visibility.
const FrameCache = (() => {
  const MEMORY_BUDGET_BYTES = 50 * 1024 * 1024;  // 50 MB
  const MAX_CONCURRENT = 6;
  const cache = new Map();     // frameId → blob URL string
  const queued = new Set();    // frameIds currently in queue (not yet loaded)
  const queue = [];            // ordered array of frameIds to load (newest first)
  let active = 0;              // in-flight fetch count
  const registry = new Map();  // streamId → frames[] (latest timelapse snapshot)
  let paused = document.visibilityState === 'hidden';

  document.addEventListener('visibilitychange', () => {
    paused = document.visibilityState === 'hidden';
    if (!paused) _flush();
  });

  function _notify(id) {
    document.dispatchEvent(new CustomEvent('framecache-loaded', { detail: id }));
  }

  function _flush() {
    if (paused) return;
    while (active < MAX_CONCURRENT && queue.length) {
      const id = queue.shift();
      queued.delete(id);
      if (cache.has(id)) continue;
      active++;
      fetch('/cameras-api/frame/' + encodeURIComponent(id))
        .then(r => { if (!r.ok) throw new Error(r.status); return r.blob(); })
        .then(blob => {
          if (!cache.has(id)) { cache.set(id, URL.createObjectURL(blob)); _notify(id); }
          active--; _flush();
        })
        .catch(() => { active--; _flush(); });
    }
  }

  return {
    // Register (or update) a stream's full frame list and enqueue uncached frames
    // for background loading, newest first, respecting the global memory budget.
    enqueueStream(streamId, frames) {
      // 1. Update registry (empty array clears a stream that went offline)
      registry.set(streamId, frames);

      // 2. Recalculate global stride based on uncached bytes across ALL streams
      let totalBytes = 0;
      for (const streamFrames of registry.values()) {
        for (const f of streamFrames) {
          if (!cache.has(f.id)) {
            totalBytes += (f.size_bytes || 100000);
          }
        }
      }
      const stride = Math.max(1, Math.ceil(totalBytes / MEMORY_BUDGET_BYTES));

      // 3. Build load list for THIS stream only, newest first.
      // Iterate reversed (newest first); push appends in that order so _flush's
      // shift() dequeues newest frames first.
      const reversed = frames.slice().reverse();
      for (let i = 0; i < reversed.length; i++) {
        const f = reversed[i];
        if (i % stride !== 0) continue;
        if (cache.has(f.id)) continue;
        if (queued.has(f.id)) continue;
        queue.push(f.id);
        queued.add(f.id);
      }

      // 4. Start loading
      _flush();
    },

    // Store a blob URL acquired by the live-frame fetch path.
    store(id, blobUrl) {
      if (cache.has(id)) return;
      cache.set(id, blobUrl);
      _notify(id);
    },

    // Returns the cached src string, or undefined on a cache miss.
    get(id) {
      return cache.get(id);
    },

    // Find the nearest loaded frames around targetId for interpolated display.
    // frames: the full frames array for this stream (sorted oldest→newest).
    // Returns { exactSrc, prevSrc, nextSrc, alpha }.
    getNearestPair(frames, targetId) {
      const targetIndex = frames.findIndex(f => f.id === targetId);
      if (targetIndex === -1) {
        return { exactSrc: undefined, prevSrc: undefined, nextSrc: undefined, alpha: 0 };
      }

      // Walk backwards to find the nearest loaded frame at or before target
      let prevFrame = null;
      for (let i = targetIndex; i >= 0; i--) {
        if (cache.has(frames[i].id)) { prevFrame = frames[i]; break; }
      }

      // Walk forwards to find the nearest loaded frame after target
      let nextFrame = null;
      for (let i = targetIndex + 1; i < frames.length; i++) {
        if (cache.has(frames[i].id)) { nextFrame = frames[i]; break; }
      }

      const exactSrc = cache.get(targetId);
      let prevSrc = prevFrame ? cache.get(prevFrame.id) : undefined;
      let nextSrc = nextFrame ? cache.get(nextFrame.id) : undefined;
      let alpha = 0;

      if (prevFrame && nextFrame) {
        const tPrev   = new Date(prevFrame.timestamp).getTime();
        const tNext   = new Date(nextFrame.timestamp).getTime();
        const tTarget = new Date(frames[targetIndex].timestamp).getTime();
        const raw = (tNext > tPrev) ? (tTarget - tPrev) / (tNext - tPrev) : 0;
        alpha = Math.max(0, Math.min(1, raw));
      } else if (!prevFrame && nextFrame) {
        // Only a frame after the target — show it at full opacity with no blend
        prevSrc = nextSrc;
        nextSrc = undefined;
        alpha = 0;
      }
      // If only prevFrame: alpha stays 0, nextSrc stays undefined

      return { exactSrc, prevSrc, nextSrc, alpha };
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
    this._cacheListener = null;
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
    if (this._cacheListener) {
      document.removeEventListener('framecache-loaded', this._cacheListener);
      this._cacheListener = null;
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
        .ticks span { position: absolute; top: 0; bottom: 0; width: 1px; background: #888; opacity: .7; }
        .ticks span.loaded { background: #4af; }
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
        <img class="blend" style="display:none; position:absolute; inset:0; width:100%; height:100%; object-fit:contain; opacity:0;" alt=""/>
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
    const blendImg   = this._root.querySelector('img.blend');
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
        tick.dataset.fid = f.id;
        if (FrameCache.get(f.id)) tick.classList.add('loaded');
        ticksEl.appendChild(tick);
      }
    };

    // Subscribe to the shared timelapse poller for frame updates.
    this._timelapseListener = (e) => {
      if (this._generation !== myGen) return;
      const frames = e.detail.get(stream);
      if (!frames || !frames.length) {
        // Update registry with empty list so offline streams don't inflate stride.
        FrameCache.enqueueStream(stream, []);
        return;
      }
      this._buffer = frames;
      updateTicks(frames);
      // Enqueue ALL frames for background loading (newest first, memory-aware stride).
      FrameCache.enqueueStream(stream, frames);

      const latest = frames[frames.length - 1];
      if (latest.id === this._lastFrameId) return;
      this._lastFrameId = latest.id;

      const incomingTs = new Date(latest.timestamp).getTime();

      const _showFrame = (blobUrl) => {
        if (this._generation !== myGen) return;
        if (incomingTs < this._lastFrameTimestamp) return;
        this._lastFrameTimestamp = incomingTs;
        if (this._scrubbing) return;
        this._lastLiveFrameSrc = blobUrl;
        mainImg.src = blobUrl;
        mainImg.style.display = '';
        spinner.classList.add('hidden');
        badge.classList.add('visible');
        tsEl.classList.add('visible');
        timeline.classList.add('visible');
        fsBtn.classList.add('visible');
      };

      // Use cached blob URL if background prefetch already has this frame.
      const cached = FrameCache.get(latest.id);
      if (cached) {
        _showFrame(cached);
      } else {
        fetch(`/cameras-api/frame/${encodeURIComponent(latest.id)}`)
          .then(r => { if (!r.ok) throw new Error(r.status); return r.blob(); })
          .then(blob => {
            const blobUrl = URL.createObjectURL(blob);
            FrameCache.store(latest.id, blobUrl);
            _showFrame(blobUrl);
          })
          .catch(() => {});
      }
    };
    document.addEventListener('cameras-timelapse-update', this._timelapseListener);
    TimelapsePoller.subscribe();

    // Turn a tick blue the moment its frame blob lands in the cache.
    this._cacheListener = (e) => {
      const tick = ticksEl.querySelector(`[data-fid="${CSS.escape(e.detail)}"]`);
      if (tick) tick.classList.add('loaded');
    };
    document.addEventListener('framecache-loaded', this._cacheListener);

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

          // Zero-request scrubbing: read only from FrameCache, blend between neighbours.
          const { exactSrc, prevSrc, nextSrc, alpha } = FrameCache.getNearestPair(this._buffer, best.id);
          const displaySrc = exactSrc || prevSrc;
          if (displaySrc) {
            mainImg.src = displaySrc;
            mainImg.style.display = '';
          }
          if (nextSrc && alpha > 0 && !exactSrc) {
            blendImg.src = nextSrc;
            blendImg.style.opacity = alpha;
            blendImg.style.display = '';
          } else {
            blendImg.style.opacity = 0;
            blendImg.style.display = 'none';
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
      } else {
        // No live frame loaded yet — restore loading state rather than freezing on scrub frame.
        mainImg.style.display = 'none';
        spinner.classList.remove('hidden');
      }
      blendImg.style.opacity = 0;
      blendImg.style.display = 'none';
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
