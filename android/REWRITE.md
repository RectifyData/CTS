# Android v5 rewrite — Alpha 2

A clean Android editor and lifecycle, built on Jetpack Compose and the validated Rust frame core.
The original M4 app remains on v5/development. The rewrite installs separately
(io.github.retrofrost.cts.android.v5.rewrite) and starts with an empty project.

## What changed

- New activity and editor, separate project session, atomic autosave, bounded undo/redo.
- Floating project actions, cards/media/export navigation, card editor and reordering.
- CSV/TSV file and paste import, project JSON import/export, artwork, soundtrack, intro and MegaPack.
- Renderer3 import, shared renderer preview, background MP4 export, progress and cancellation.
- No AI integrations or mandatory cloud services.

The new UI does not use the old CtsAndroidAppV2 entry point. Existing CTS project format,
Renderer3/SmartBadge compatibility, Rust JNI math, Android Canvas painting and MediaCodec
are deliberately reused instead of replacing tested frame behavior with an approximation.
Older UI source remains in this branch for compatibility tests, but is not launched.

The rewrite is an alpha. Physical-device tests, signing, full renderer parity, audio
preview, drag-and-drop ordering and guided image-strip import are not yet certified.
Projects referencing files in the old app's private storage must reimport those files.
The new package ID avoids overwriting the existing app.

Build on v5/android-rewrite with the standard Android M4 Rust build instructions.
The branch workflow runs shared-contract, Rust, Kotlin/JNI tests, APK library verification
and publishes the alpha if all checks pass.
