#!/bin/sh
# Downloads the three "hey buddy" models at the pinned Hugging Face revision into app/src/wakeword/assets/wakeword and
# checks them against SHA256SUMS there (so a change to the files is a change to the sums, in review).
# Models: benjamin-paine/hey-buddy (code Apache-2.0, model card CC BY 4.0). The two feature models are byte-identical
# to openWakeWord v0.5.1's melspectrogram.onnx and embedding_model.onnx. Usage: tools/wakeword-models.sh [--check]
set -eu
REV=68a13d58e216e8e4837559ae665ca3e9769b41c2
DIR=$(cd "$(dirname "$0")/.." && pwd)/app/src/wakeword/assets/wakeword
cd "$DIR"
if [ "${1:-}" != --check ]; then
  for f in models/hey-buddy.onnx pretrained/mel-spectrogram.onnx pretrained/speech-embedding.onnx; do
    curl -fsSL -o "$(basename "$f")" "https://huggingface.co/benjamin-paine/hey-buddy/resolve/$REV/$f"
  done
fi
sha256sum -c SHA256SUMS
