# MediaPipe BlazeFace short-range model

`blaze_face_short_range.tflite` is downloaded unmodified from Google's MediaPipe Model Maker
distribution:

https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0

Self-hosted here (rather than fetched from Google at runtime) so the profile-photo flow doesn't
depend on an external CDN being reachable. The `@mediapipe/tasks-vision` JS runtime that loads this
model is loaded lazily from jsDelivr - see `static/js/profile-photo.js`.
