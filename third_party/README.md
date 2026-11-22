# Third-party components

Buddy's built-in Linux runs through **proot**, which ships inside the APK as native libraries
(named `lib*.so` so Android extracts them). They are built from source by [`native/build.sh`](../native/build.sh)
with the Android NDK in CI (or Termux's clang on a phone), the way Termux builds them. The sources are here:
proot and libandroid-shmem as git submodules at their release tags, and the three files of talloc that the build
uses (`talloc.c`, `talloc.h`, `lib/replace/replace.h`), unchanged from the talloc 2.5.0 release.

| File in the APK | Component | Version | Licence | Source |
|---|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | PRoot (Termux fork) | 5.1.107.102 | GPL-2.0 ([text](proot-LICENSE.txt)) | [`proot/`](proot), https://github.com/termux/proot (tag v5.1.107.102) |
| `libtalloc.so` | talloc | 2.5.0 | LGPL-3.0-or-later ([text](talloc-LICENSE.txt)) | [`talloc/`](talloc), from https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| `libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-3-Clause ([text](libandroid-shmem-LICENSE.txt)) | [`libandroid-shmem/`](libandroid-shmem), https://github.com/termux/libandroid-shmem (tag v0.7) |

The GitHub build (not F-Droid) also listens for "hey buddy" on the phone (feature 032). Its detector runs on ONNX Runtime
with three small models, all unchanged and checked against [`SHA256SUMS`](../app/src/wakeword/assets/wakeword/SHA256SUMS)
(fetch script: [`tools/wakeword-models.sh`](../tools/wakeword-models.sh)):

| File in the APK | Component | Licence | Source |
|---|---|---|---|
| `lib/arm64-v8a/libonnxruntime.so`, `libonnxruntime4j_jni.so` | ONNX Runtime 1.22.0 | MIT | https://github.com/microsoft/onnxruntime |
| `assets/wakeword/hey-buddy.onnx` | "hey buddy" classifier, by Benjamin Paine | Apache-2.0 (code repository), CC BY 4.0 (model card) | https://huggingface.co/benjamin-paine/hey-buddy (revision 68a13d58) |
| `assets/wakeword/mel-spectrogram.onnx`, `speech-embedding.onnx` | feature models (Google's speech embedding, as shipped by openWakeWord v0.5.1; same bytes) | Apache-2.0 | https://github.com/dscripka/openWakeWord, https://huggingface.co/benjamin-paine/hey-buddy |

The unit tests use three short clips ("hey buddy", an augmented copy, a sound-alike) from the dataset
https://huggingface.co/datasets/benjamin-paine/hey-buddy (CC BY 4.0, Benjamin Paine), converted to 16 kHz WAV with
silence added; they are not in the APK.

Downloaded at runtime, not bundled: Alpine Linux (minirootfs and packages, various open-source licences,
https://alpinelinux.org), Claude Code (Anthropic, installed with Anthropic's official installer under
Anthropic's terms), Codex (OpenAI, from npm), Android build-tools and the Android platform (Google,
Android SDK terms), fetched only when developer mode's toolchain is set up.

Fonts: Geist and Geist Mono (SIL Open Font License, `app/src/main/assets/fonts/OFL-Geist.txt`).
Icons: Material Symbols (Apache-2.0).
