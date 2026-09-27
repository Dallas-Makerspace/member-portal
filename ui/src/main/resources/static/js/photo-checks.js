/**
 * Pure validation functions for the profile-photo upload flow (see profile-photo.js). No DOM
 * side effects beyond reading pixels from an already-decoded canvas/ImageData - safe to unit test
 * in isolation. Thresholds are collected in one place (THRESHOLDS) so they can be tuned from
 * override-rate data in Kibana without touching the check logic itself.
 */

export const THRESHOLDS = Object.freeze({
    minDimension: 256,
    outputSize: 512,
    faceScoreMin: 0.6,
    faceSizeHardMin: 0.20, // below this fraction of crop width: "can't find a usable face"
    faceSizeSoftMin: 0.30, // between hard and soft min: usable but flagged
    faceSizeMax: 0.85, // above this: face fills (almost) the whole crop
    centerOffsetMax: 0.15, // fraction of crop width/height a face box center may drift
    yawInnerFraction: 0.6, // nose tip must fall within this middle fraction of the inter-eye span
    darkLumaMin: 70,
    brightLumaMax: 215,
    brightClippedMax: 0.25,
    backlitRatioMin: 0.75,
    unevenDiffMax: 60,
    blurVarianceMin: 60,
});

/** check id -> member-facing message. One place to edit copy without touching check logic. */
export const MESSAGES = Object.freeze({
    decode: "We couldn't read that file. Try a JPEG or PNG photo.",
    minResolution: 'This photo is too small. Try a larger or higher-quality picture.',
    noFace: "We couldn't find a face. Make sure your face is visible and try again.",
    multipleFaces: 'We found more than one face. Profile photos should show just you.',
    faceTooSmallHard: 'Move closer to the camera so your face fills more of the circle.',
    faceTooSmallSoft: 'Move closer to the camera so your face fills more of the circle.',
    faceTooClose: "You're a little too close - move back slightly.",
    offCenter: 'Center your face in the circle.',
    yaw: 'Look straight at the camera.',
    dark: 'Your photo is too dark. Face a light source like a window and try again.',
    bright: 'Your photo is overexposed. Move away from direct light or a bright window behind the camera.',
    backlit: 'The light is behind you. Turn to face the light source and try again.',
    uneven: 'One side of your face is in shadow. Face the light more directly.',
    blurry: 'Your photo looks blurry. Hold still (or wipe your camera lens) and try again.',
});

/** Checks that can never be overridden with "Use anyway". */
const HARD_CHECK_IDS = new Set(['decode', 'minResolution', 'noFace', 'multipleFaces', 'faceTooSmallHard']);

export function isHardCheck(id) {
    return HARD_CHECK_IDS.has(id);
}

/**
 * Decodes a File/Blob into an ImageBitmap, honoring EXIF orientation. Returns null (not a
 * rejection) on failure so callers can report the "decode" check rather than an unhandled error.
 */
export async function decodeToBitmap(fileOrBlob) {
    try {
        return await createImageBitmap(fileOrBlob, { imageOrientation: 'from-image' });
    } catch (e) {
        return null;
    }
}

/** { id: 'minResolution' } or null. */
export function checkMinResolution(bitmap) {
    if (bitmap.width < THRESHOLDS.minDimension || bitmap.height < THRESHOLDS.minDimension) {
        return { id: 'minResolution', message: MESSAGES.minResolution };
    }
    return null;
}

// Rec. 601 luma - matches the weighting already used server-side/elsewhere in this codebase for
// "how bright is this pixel" style checks.
function luma(r, g, b) {
    return 0.299 * r + 0.587 * g + 0.114 * b;
}

/**
 * Computes lighting stats (mean luma, fraction of clipped-bright pixels) over [region] of
 * [imageData] ({x, y, width, height} in pixels; defaults to the whole image). Also returns the
 * left-half/right-half mean luma split, used for the "uneven lighting" check.
 */
export function computeLumaStats(imageData, region) {
    const { data, width } = imageData;
    const r = region || { x: 0, y: 0, width, height: imageData.height };
    let sum = 0;
    let clipped = 0;
    let count = 0;
    let leftSum = 0;
    let leftCount = 0;
    let rightSum = 0;
    let rightCount = 0;
    const midX = r.x + r.width / 2;

    for (let y = r.y; y < r.y + r.height; y++) {
        for (let x = r.x; x < r.x + r.width; x++) {
            const i = (y * width + x) * 4;
            const l = luma(data[i], data[i + 1], data[i + 2]);
            sum += l;
            count++;
            if (l >= 250) clipped++;
            if (x < midX) {
                leftSum += l;
                leftCount++;
            } else {
                rightSum += l;
                rightCount++;
            }
        }
    }

    return {
        meanLuma: count > 0 ? sum / count : 0,
        clippedFraction: count > 0 ? clipped / count : 0,
        leftMeanLuma: leftCount > 0 ? leftSum / leftCount : 0,
        rightMeanLuma: rightCount > 0 ? rightSum / rightCount : 0,
    };
}

/**
 * Lighting checks. [faceRegion] is the detected face box in pixel coordinates, or null when no
 * face detector is available (Step 5, whole-image fallback) or none was found yet.
 * Returns the first failing check, or null.
 */
export function checkLighting(fullImageData, faceRegion) {
    const faceStats = computeLumaStats(fullImageData, faceRegion || undefined);

    if (faceStats.meanLuma < THRESHOLDS.darkLumaMin) {
        return { id: 'dark', message: MESSAGES.dark };
    }
    if (faceStats.meanLuma > THRESHOLDS.brightLumaMax && faceStats.clippedFraction >= THRESHOLDS.brightClippedMax) {
        return { id: 'bright', message: MESSAGES.bright };
    }
    if (faceRegion) {
        const fullStats = computeLumaStats(fullImageData);
        if (fullStats.meanLuma > 0 && faceStats.meanLuma / fullStats.meanLuma < THRESHOLDS.backlitRatioMin) {
            return { id: 'backlit', message: MESSAGES.backlit };
        }
    }
    if (Math.abs(faceStats.leftMeanLuma - faceStats.rightMeanLuma) > THRESHOLDS.unevenDiffMax) {
        return { id: 'uneven', message: MESSAGES.uneven };
    }
    return null;
}

/**
 * Blur check via variance of the Laplacian over a downscaled grayscale copy of [region] (or the
 * whole image). A sharp image has high-variance edges; a blurry one is smooth everywhere.
 */
export function checkBlur(canvas, region) {
    const size = 128;
    const work = document.createElement('canvas');
    work.width = size;
    work.height = size;
    const ctx = work.getContext('2d', { willReadFrequently: true });
    const r = region || { x: 0, y: 0, width: canvas.width, height: canvas.height };
    ctx.drawImage(canvas, r.x, r.y, r.width, r.height, 0, 0, size, size);

    const { data } = ctx.getImageData(0, 0, size, size);
    const gray = new Float32Array(size * size);
    for (let i = 0; i < size * size; i++) {
        gray[i] = luma(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
    }

    let sum = 0;
    let sumSq = 0;
    let count = 0;
    for (let y = 1; y < size - 1; y++) {
        for (let x = 1; x < size - 1; x++) {
            const idx = y * size + x;
            const laplacian =
                4 * gray[idx] - gray[idx - 1] - gray[idx + 1] - gray[idx - size] - gray[idx + size];
            sum += laplacian;
            sumSq += laplacian * laplacian;
            count++;
        }
    }
    const mean = sum / count;
    const variance = sumSq / count - mean * mean;

    if (variance < THRESHOLDS.blurVarianceMin) {
        return { id: 'blurry', message: MESSAGES.blurry };
    }
    return null;
}

/**
 * Face-geometry checks against a single MediaPipe FaceDetector detection: `boundingBox` is in
 * pixel coordinates matching the input image, `keypoints` are normalized (0..1) in order
 * [rightEye, leftEye, noseTip, mouthCenter, rightEarTragion, leftEarTragion] per BlazeFace - only
 * ever compared against each other here, so their absolute scale doesn't matter. [cropSize] is the
 * (square) crop's pixel dimensions.
 */
export function checkFaceGeometry(detection, cropSize) {
    const box = detection.boundingBox;
    const faceWidthFraction = box.width / cropSize;

    if (faceWidthFraction < THRESHOLDS.faceSizeHardMin) {
        return { id: 'faceTooSmallHard', message: MESSAGES.faceTooSmallHard };
    }
    if (faceWidthFraction > THRESHOLDS.faceSizeMax) {
        return { id: 'faceTooClose', message: MESSAGES.faceTooClose };
    }
    if (faceWidthFraction < THRESHOLDS.faceSizeSoftMin) {
        return { id: 'faceTooSmallSoft', message: MESSAGES.faceTooSmallSoft };
    }

    const faceCenterX = box.originX + box.width / 2;
    const faceCenterY = box.originY + box.height / 2;
    const cropCenter = cropSize / 2;
    if (
        Math.abs(faceCenterX - cropCenter) / cropSize > THRESHOLDS.centerOffsetMax ||
        Math.abs(faceCenterY - cropCenter) / cropSize > THRESHOLDS.centerOffsetMax
    ) {
        return { id: 'offCenter', message: MESSAGES.offCenter };
    }

    const keypoints = detection.keypoints || [];
    if (keypoints.length >= 3) {
        const [rightEye, leftEye, noseTip] = keypoints;
        const eyeMinX = Math.min(rightEye.x, leftEye.x);
        const eyeMaxX = Math.max(rightEye.x, leftEye.x);
        const eyeSpan = eyeMaxX - eyeMinX;
        if (eyeSpan > 0) {
            const margin = (eyeSpan * (1 - THRESHOLDS.yawInnerFraction)) / 2;
            if (noseTip.x < eyeMinX + margin || noseTip.x > eyeMaxX - margin) {
                return { id: 'yaw', message: MESSAGES.yaw };
            }
        }
    }

    return null;
}

/** Converts a MediaPipe detection's normalized boundingBox (0..1) to pixel coordinates. */
export function faceBoxToPixelRegion(detection, cropSize) {
    const box = detection.boundingBox;
    return {
        x: Math.max(0, Math.round(box.originX)),
        y: Math.max(0, Math.round(box.originY)),
        width: Math.min(cropSize, Math.round(box.width)),
        height: Math.min(cropSize, Math.round(box.height)),
    };
}
