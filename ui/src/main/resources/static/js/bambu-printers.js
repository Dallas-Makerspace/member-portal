/* 3D printers page: live camera video in the modal. The stream runs only while the modal is open
 * and the tab is visible, and pauses itself after a while so a forgotten tab doesn't hold it open. */
(function () {
    'use strict';

    const LIVE_LIMIT_MS = 10 * 60 * 1000;
    const BLANK = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';

    const modalEl = document.getElementById('bambuCameraModal');
    if (!modalEl) return;
    const modal = new bootstrap.Modal(modalEl);
    const label = document.getElementById('bambuCameraModalLabel');
    const placeholder = document.getElementById('bambuCameraPlaceholder');
    const loading = document.getElementById('bambuCameraModalLoading');
    const badge = document.getElementById('bambuCameraLiveBadge');
    const notice = document.getElementById('bambuCameraNotice');
    const noticeText = document.getElementById('bambuCameraNoticeText');
    const resumeBtn = document.getElementById('bambuCameraResume');
    let live = document.getElementById('bambuCameraLive');

    let printerId = null;
    let limitTimer = null;
    let firstFrameWatch = null;

    function showNotice(text, canResume) {
        noticeText.textContent = text;
        resumeBtn.hidden = !canResume;
        notice.hidden = false;
    }

    function stopStream() {
        clearTimeout(limitTimer);
        cancelAnimationFrame(firstFrameWatch);
        // Pointing the image elsewhere closes the open stream connection.
        live.onload = live.onerror = null;
        live.src = BLANK;
        live.removeAttribute('src');
        badge.hidden = true;
        loading.hidden = true;
    }

    function onFirstFrame() {
        loading.hidden = true;
        badge.hidden = false;
        notice.hidden = true;
    }

    function startStream() {
        if (!printerId) return;
        stopStream();
        notice.hidden = true;
        loading.hidden = false;
        // Fresh element per start so a stale connection can't paint over a new one.
        const next = live.cloneNode(false);
        live.replaceWith(next);
        live = next;
        live.onload = onFirstFrame;
        live.onerror = function () {
            stopStream();
            showNotice('Live video is unavailable right now. Showing the latest snapshot.', true);
        };
        live.src = '/bambu-api/printers/' + encodeURIComponent(printerId) + '/camera/stream';
        // Not every browser fires load for a video-style image, so also watch for the first frame.
        const startedAt = performance.now();
        (function watch() {
            if (live.naturalWidth > 0) return onFirstFrame();
            if (performance.now() - startedAt < 15000) firstFrameWatch = requestAnimationFrame(watch);
        })();
        limitTimer = setTimeout(function () {
            stopStream();
            showNotice('Live video paused.', true);
        }, LIVE_LIMIT_MS);
    }

    // Shows the card's snapshot under the stream while it connects. Copies the already-decoded
    // pixels so nothing is fetched again; a thumbnail still loading fills in when it arrives.
    let pendingThumb = null;
    function showPlaceholder(thumb) {
        if (pendingThumb) pendingThumb.removeEventListener('load', copyPending);
        pendingThumb = null;
        placeholder.removeAttribute('src');
        if (!thumb) return;
        if (thumb.complete && thumb.naturalWidth > 0) copyThumb(thumb);
        else {
            pendingThumb = thumb;
            thumb.addEventListener('load', copyPending, {once: true});
        }
    }
    function copyPending() {
        if (pendingThumb && printerId) copyThumb(pendingThumb);
        pendingThumb = null;
    }
    function copyThumb(thumb) {
        try {
            const canvas = document.createElement('canvas');
            canvas.width = thumb.naturalWidth;
            canvas.height = thumb.naturalHeight;
            canvas.getContext('2d').drawImage(thumb, 0, 0);
            placeholder.src = canvas.toDataURL('image/jpeg', 0.9);
        } catch (e) {
            placeholder.src = thumb.src;
        }
    }

    document.querySelectorAll('.camera-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            if (btn.classList.contains('pr-cam-missing')) return;
            printerId = btn.dataset.printerId;
            label.textContent = btn.dataset.printerName + ' camera';
            showPlaceholder(btn.querySelector('img.printer-thumb'));
            startStream();
            modal.show();
        });
    });

    resumeBtn.addEventListener('click', startStream);

    modalEl.addEventListener('hidden.bs.modal', function () {
        stopStream();
        printerId = null;
        notice.hidden = true;
        showPlaceholder(null);
    });

    document.addEventListener('visibilitychange', function () {
        if (!printerId) return;
        if (document.hidden) stopStream();
        else startStream();
    });
})();
