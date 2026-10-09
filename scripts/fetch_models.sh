#!/usr/bin/env bash
# Downloads the on-device ML models Shuddh bundles in app/src/main/assets.
# All are public Google MediaPipe models (Apache-2.0); they are not committed to keep the repo small.
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/assets" 2>/dev/null || { mkdir -p "$(dirname "$0")/../app/src/main/assets"; cd "$(dirname "$0")/../app/src/main/assets"; }
B=https://storage.googleapis.com/mediapipe-models
fetch() { [ -s "$1" ] && { echo "✓ $1"; return; }; echo "↓ $1"; curl -fSL -C - --retry 10 -o "$1" "$B/$2"; }
fetch image_embedder.tflite       image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite
fetch text_embedder.tflite        text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite
fetch efficientdet_lite0.tflite   object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite
fetch hand_landmarker.task        hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
fetch face_landmarker.task        face_landmarker/face_landmarker/float16/latest/face_landmarker.task
fetch pose_landmarker_lite.task   pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
echo "Models ready."
