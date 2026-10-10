/**
 * Camera button / upload / crop / validate / save flow for the profile avatar (see
 * fragments/profile-photo.html for the markup this wires up). Cropper.js and
 * @mediapipe/tasks-vision are both loaded lazily, on first use, so the profile page itself never
 * pays their cost. If the face detector fails to load (blocked CDN, old browser), the flow
 * degrades to the non-ML checks in photo-checks.js only.
 */
import {
    THRESHOLDS,
    decodeToBitmap,
    checkMinResolution,
    checkLighting,
    checkBlur,
    checkFaceGeometry,
    faceBoxToPixelRegion,
    isHardCheck,
} from './photo-checks.js';

const CROPPER_JS_URL = 'https://cdn.jsdelivr.net/npm/cropperjs@1.6.2/dist/cropper.esm.js';
const CROPPER_CSS_URL = 'https://cdn.jsdelivr.net/npm/cropperjs@1.6.2/dist/cropper.min.css';
const MEDIAPIPE_VISION_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs';
const MEDIAPIPE_WASM_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm';
const MEDIAPIPE_MODEL_URL = '/static/vendor/mediapipe/blaze_face_short_range.tflite';
const LIVE_GUIDANCE_INTERVAL_MS = 200; // ~5fps
const FACE_CROP_PADDING = 0.6; // extra margin around a detected face box when pre-positioning

const btn = document.getElementById('profilePhotoBtn');
if (btn) {
    const username = btn.dataset.username;
    const canUpload = btn.dataset.canUpload === 'true';
    initProfilePhoto(username, canUpload);
}

function initProfilePhoto(username, canUpload) {
    const modalEl = document.getElementById('profilePhotoModal');
    const modal = new bootstrap.Modal(modalEl);
    const fileInput = document.getElementById('profilePhotoFileInput');
    const statusEl = document.getElementById('profilePhotoStatus');

    const steps = {
        camera: document.getElementById('profilePhotoCameraStep'),
        crop: document.getElementById('profilePhotoCropStep'),
        checking: document.getElementById('profilePhotoCheckingStep'),
        result: document.getElementById('profilePhotoResultStep'),
        saving: document.getElementById('profilePhotoSavingStep'),
    };

    let cropperInstance = null;
    let cropperModulePromise = null;
    let faceDetectorPromise = null;
    let mediaStream = null;
    let liveGuidanceTimer = null;
    let lastCroppedCanvas = null;
    let source = null; // 'camera' | 'file'
    // Bumped when the modal closes, so async work started by an earlier flow can tell it's stale.
    let flowId = 0;

    const captureBtn = document.getElementById('profilePhotoCaptureBtn');
    const cropBackBtn = document.getElementById('profilePhotoCropBackBtn');

    function setStatus(message) {
        statusEl.textContent = message || '';
    }

    function showStep(name) {
        Object.entries(steps).forEach(([key, el]) => el.classList.toggle('d-none', key !== name));
    }

    function stopCamera() {
        if (liveGuidanceTimer) {
            clearInterval(liveGuidanceTimer);
            liveGuidanceTimer = null;
        }
        if (mediaStream) {
            mediaStream.getTracks().forEach((track) => track.stop());
            mediaStream = null;
        }
    }

    modalEl.addEventListener('hidden.bs.modal', () => {
        flowId++;
        stopCamera();
        lastCroppedCanvas = null;
        source = null;
        if (cropperInstance) {
            cropperInstance.destroy();
            cropperInstance = null;
        }
        setStatus('');
        fileInput.value = '';
    });

    async function loadCropper() {
        if (!cropperModulePromise) {
            if (!document.getElementById('profilePhotoCropperCss')) {
                const link = document.createElement('link');
                link.id = 'profilePhotoCropperCss';
                link.rel = 'stylesheet';
                link.href = CROPPER_CSS_URL;
                document.head.appendChild(link);
            }
            cropperModulePromise = import(CROPPER_JS_URL).then((mod) => mod.default || mod);
        }
        return cropperModulePromise;
    }

    async function getFaceDetector() {
        if (!faceDetectorPromise) {
            faceDetectorPromise = (async () => {
                try {
                    const vision = await import(MEDIAPIPE_VISION_URL);
                    const fileset = await vision.FilesetResolver.forVisionTasks(MEDIAPIPE_WASM_URL);
                    return await vision.FaceDetector.createFromOptions(fileset, {
                        baseOptions: { modelAssetPath: MEDIAPIPE_MODEL_URL },
                        runningMode: 'VIDEO',
                    });
                } catch (e) {
                    console.warn('profile-photo: face detector unavailable, using non-ML checks only', e);
                    return null;
                }
            })();
        }
        return faceDetectorPromise;
    }

    /** Runs face detection; returns detections[], or null if the detector itself is unusable. */
    function detectFaces(detector, source) {
        if (!detector) return null;
        try {
            const result = detector.detectForVideo(source, Math.floor(performance.now()));
            return result.detections || [];
        } catch (e) {
            console.warn('profile-photo: face detection call failed', e);
            return null;
        }
    }

    // ---- Menu actions ----

    document.getElementById('profilePhotoTakeSelfie')?.addEventListener('click', (e) => {
        e.preventDefault();
        if (!canUpload) return;
        openCameraFlow();
    });

    document.getElementById('profilePhotoUpload')?.addEventListener('click', (e) => {
        e.preventDefault();
        if (!canUpload) return;
        fileInput.click();
    });

    fileInput.addEventListener('change', () => {
        const file = fileInput.files && fileInput.files[0];
        if (file) openFileFlow(file);
    });

    async function removePhoto(e) {
        e.preventDefault();
        const prompt = canUpload
            ? 'Remove your profile photo? Your Discourse avatar will be shown instead.'
            : `Remove @${username}'s profile photo? Their Discourse avatar will be shown instead.`;
        if (!confirm(prompt)) return;
        const btn = e.currentTarget;
        setButtonLoading(btn, true, 'Removing photo…');
        try {
            const response = await fetch(`/profile/@${username}/photo`, { method: 'DELETE' });
            const body = await response.json().catch(() => ({}));
            if (response.ok) {
                showToastAfterReload(body.message || 'Profile photo removed');
                window.location.reload();
            } else {
                showToast(body.message || 'Failed to remove profile photo', 'danger');
                setButtonLoading(btn, false);
            }
        } catch (err) {
            showToast('Failed to remove profile photo', 'danger');
            setButtonLoading(btn, false);
        }
    }

    document.getElementById('profilePhotoRemove')?.addEventListener('click', removePhoto);

    // ---- Camera flow ----

    async function openCameraFlow() {
        const myFlow = flowId;
        source = 'camera';
        stopCamera();
        modal.show();
        showStep('camera');
        setStatus('');
        const errorEl = document.getElementById('profilePhotoCameraError');
        errorEl.classList.add('d-none');
        captureBtn.classList.remove('d-none');
        captureBtn.disabled = true;
        const video = document.getElementById('profilePhotoVideo');

        let stream;
        try {
            stream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } },
            });
        } catch (e) {
            if (myFlow !== flowId) return;
            errorEl.textContent =
                "We couldn't access your camera. Check your browser's camera permission, or choose a photo instead.";
            errorEl.classList.remove('d-none');
            captureBtn.classList.add('d-none');
            return;
        }
        // The modal was closed while the permission prompt was up - don't leave the camera on.
        if (myFlow !== flowId) {
            stream.getTracks().forEach((track) => track.stop());
            return;
        }
        mediaStream = stream;
        video.onloadeddata = () => {
            captureBtn.disabled = false;
        };
        video.srcObject = mediaStream;

        const detector = await getFaceDetector();
        if (detector && mediaStream === stream && myFlow === flowId) {
            liveGuidanceTimer = setInterval(() => {
                if (video.readyState < 2) return;
                const detections = detectFaces(detector, video);
                setStatus(liveGuidanceMessage(detections, Math.min(video.videoWidth, video.videoHeight)));
            }, LIVE_GUIDANCE_INTERVAL_MS);
        }
    }

    function liveGuidanceMessage(detections, referenceSize) {
        if (detections === null) return '';
        if (detections.length === 0) return 'Move into view of the camera';
        if (detections.length > 1) return 'Only one person should be in frame';
        const result = checkFaceGeometry(detections[0], referenceSize);
        if (!result) return 'Looking good!';
        if (result.id === 'faceTooSmallHard' || result.id === 'faceTooSmallSoft') return 'Move closer';
        if (result.id === 'faceTooClose') return 'Move back slightly';
        if (result.id === 'offCenter') return 'Center your face';
        if (result.id === 'yaw') return 'Look straight at the camera';
        return '';
    }

    captureBtn.addEventListener('click', () => {
        const video = document.getElementById('profilePhotoVideo');
        if (!mediaStream || !video.videoWidth) return;
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext('2d');
        // The preview is mirrored (CSS transform) for a natural selfie feel, but the saved photo
        // shouldn't be - flip it back here.
        ctx.translate(canvas.width, 0);
        ctx.scale(-1, 1);
        ctx.drawImage(video, 0, 0);
        stopCamera();
        canvas.toBlob((blob) => openCropFlow(blob), 'image/jpeg', 0.92);
    });

    // ---- File flow ----

    async function openFileFlow(file) {
        source = 'file';
        modal.show();
        openCropFlow(file);
    }

    // ---- Crop flow ----

    async function openCropFlow(fileOrBlob) {
        const myFlow = flowId;
        lastCroppedCanvas = null;
        if (cropperInstance) {
            cropperInstance.destroy();
            cropperInstance = null;
        }
        cropBackBtn.textContent = source === 'camera' ? 'Retake' : 'Cancel';
        showStep('crop');
        setStatus('Loading...');

        const bitmap = await decodeToBitmap(fileOrBlob);
        if (myFlow !== flowId) return;
        if (!bitmap) {
            showResult({ passed: false, hard: true, message: "We couldn't read that file. Try a JPEG or PNG photo." });
            return;
        }

        const objectUrl = URL.createObjectURL(fileOrBlob);
        const img = document.getElementById('profilePhotoCropImage');
        img.src = objectUrl;
        // Check `complete` right after setting src (synchronously, before any await) - a tiny blob
        // image can finish loading before an async gap like `loadCropper()`'s network fetch, and an
        // img that's already `complete` never fires another 'load' event, which would hang this
        // forever if we only ever attached an onload handler.
        const imgLoaded = img.complete
            ? Promise.resolve()
            : new Promise((resolve) => {
                  img.onload = resolve;
              });

        const [Cropper] = await Promise.all([loadCropper(), imgLoaded]);
        if (myFlow !== flowId) return;

        if (cropperInstance) cropperInstance.destroy();
        cropperInstance = new Cropper(img, {
            aspectRatio: 1,
            viewMode: 1,
            dragMode: 'move',
            autoCropArea: 0.8,
            cropBoxResizable: true,
            background: false,
            ready() {
                positionCropOnFace(cropperInstance, bitmap).finally(() => setStatus(''));
            },
        });
    }

    async function positionCropOnFace(cropper, bitmap) {
        try {
            const detector = await getFaceDetector();
            const detections = detectFaces(detector, bitmap);
            if (!detections || detections.length !== 1) return;
            const box = detections[0].boundingBox;
            const pad = Math.max(box.width, box.height) * FACE_CROP_PADDING;
            const size = Math.max(box.width, box.height) + pad * 2;
            const x = Math.max(0, box.originX + box.width / 2 - size / 2);
            const y = Math.max(0, box.originY + box.height / 2 - size / 2);
            cropper.setData({ x, y, width: size, height: size });
        } catch (e) {
            // Best-effort only - Cropper's default centered crop is a fine fallback.
            console.warn('profile-photo: could not pre-position crop on detected face', e);
        }
    }

    cropBackBtn.addEventListener('click', () => {
        if (source === 'camera') {
            openCameraFlow();
        } else {
            modal.hide();
        }
    });

    document.getElementById('profilePhotoCropNextBtn').addEventListener('click', async () => {
        if (!cropperInstance) return;
        const canvas = cropperInstance.getCroppedCanvas({
            width: THRESHOLDS.outputSize,
            height: THRESHOLDS.outputSize,
            imageSmoothingQuality: 'high',
        });
        lastCroppedCanvas = canvas;
        showStep('checking');
        setStatus('');
        const outcome = await runChecks(canvas);
        showResult(outcome);
    });

    // ---- Validation ----

    async function runChecks(canvas) {
        const resCheck = checkMinResolution({ width: canvas.width, height: canvas.height });
        if (resCheck) return { passed: false, hard: true, message: resCheck.message };

        const detector = await getFaceDetector();
        const detections = detectFaces(detector, canvas);

        let faceRegion = null;
        if (detections !== null) {
            if (detections.length === 0) {
                return { passed: false, hard: true, message: 'We couldn’t find a face. Make sure your face is visible and try again.' };
            }
            if (detections.length > 1) {
                return { passed: false, hard: true, message: 'We found more than one face. Profile photos should show just you.' };
            }
            const geometryFailure = checkFaceGeometry(detections[0], THRESHOLDS.outputSize);
            if (geometryFailure) {
                return { passed: false, hard: isHardCheck(geometryFailure.id), message: geometryFailure.message };
            }
            faceRegion = faceBoxToPixelRegion(detections[0], THRESHOLDS.outputSize);
        }

        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);

        const lightingFailure = checkLighting(imageData, faceRegion);
        if (lightingFailure) return { passed: false, hard: false, message: lightingFailure.message };

        const blurFailure = checkBlur(canvas, faceRegion);
        if (blurFailure) return { passed: false, hard: false, message: blurFailure.message };

        return { passed: true };
    }

    function showResult({ passed, hard, message }) {
        showStep('result');
        setStatus('');
        const preview = document.getElementById('profilePhotoPreviewImg');
        if (lastCroppedCanvas) preview.src = lastCroppedCanvas.toDataURL('image/jpeg', 0.85);
        preview.classList.toggle('d-none', !lastCroppedCanvas);
        document.getElementById('profilePhotoResultMessage').textContent = message || 'Looks good!';
        document.getElementById('profilePhotoSaveBtn').classList.toggle('d-none', !passed);
        document.getElementById('profilePhotoUseAnywayBtn').classList.toggle('d-none', passed || hard);
    }

    // Back to the crop if there is one (it still has its Retake/Cancel), else restart the source.
    document.getElementById('profilePhotoRetryBtn').addEventListener('click', () => {
        if (cropperInstance) {
            setStatus('');
            showStep('crop');
        } else if (source === 'camera') {
            openCameraFlow();
        } else {
            fileInput.value = '';
            fileInput.click();
        }
    });

    document.getElementById('profilePhotoSaveBtn').addEventListener('click', () => savePhoto(false));
    document.getElementById('profilePhotoUseAnywayBtn').addEventListener('click', () => savePhoto(true));

    async function savePhoto(override) {
        if (!lastCroppedCanvas) return;
        showStep('saving');
        const blob = await new Promise((resolve) =>
            lastCroppedCanvas.toBlob(resolve, 'image/jpeg', 0.85));
        if (!blob) {
            showToast('Failed to prepare photo for upload', 'danger');
            showStep('result');
            return;
        }

        const formData = new FormData();
        formData.append('photo', blob, 'photo.jpg');
        if (override) formData.append('override', 'true');

        try {
            const response = await fetch(`/profile/@${username}/photo`, { method: 'POST', body: formData });
            const body = await response.json().catch(() => ({}));
            if (response.ok && body.photoUrl) {
                const avatarImg = document.getElementById('profileAvatarImg');
                if (avatarImg) avatarImg.src = body.photoUrl;
                modal.hide();
                showToast(body.message || 'Profile photo updated');
                syncRemoveMenuItem();
            } else {
                showToast(body.message || 'Failed to save photo', 'danger');
                showStep('result');
            }
        } catch (err) {
            showToast('Failed to save photo', 'danger');
            showStep('result');
        }
    }

    // After a first-ever upload, the "Remove photo" item doesn't exist in the DOM yet (the
    // server only rendered it when has_uploaded_photo was already true) - add it in place rather
    // than requiring a page reload just to see it.
    function syncRemoveMenuItem() {
        if (document.getElementById('profilePhotoRemove')) return;
        const menu = document.querySelector('#profilePhotoBtn + .dropdown-menu');
        if (!menu) return;
        const li = document.createElement('li');
        li.innerHTML =
            '<a class="dropdown-item text-danger" href="#" id="profilePhotoRemove" data-action="remove">Remove photo</a>';
        menu.appendChild(li);
        li.querySelector('a').addEventListener('click', removePhoto);
    }
}
