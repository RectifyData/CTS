# Cubical Compare V5 Android M2 — Renderer3

The Android V5 app imports actual binary `.renderer3` files (CCRNDR03 container)
and Renderer3 ZIP packages via **Project menu → Import Renderer3**. Renderer3 scene
selection is saved in Android project metadata; the imported file is safely copied
to private app storage and used by **both preview and MP4 export**, so the output
uses the same integer-frame animation evaluator shown in the monitor.

## Supported on Android M2

- Renderer3 API 3 `scene-v3` manifests (gzip + CRC32 verified), including ZIP sidecar assets.
- Absolute frame clock and output duration derived from the Renderer3 scene (independent of card count).
- Image/raster layers, vector rectangles, ellipses, polygons, text and layer ordering.
- Dense frame values, keyframed interpolation, selector overrides, clipping, transforms, opacity and sourceExact conveyor offsets.
- Safe ZIP member paths, archive limits and bounded source image decoding.
- A clear import error for Renderer3 features the mobile runtime cannot execute faithfully.
- Existing Android editors, cards, soundtrack and low-memory export presets remain available.
- Renderer3 files stay private to the app and may be reimported if app data is cleared.
- V5 uses a separate package ID from the stable Android application.

## Not yet available

This is a focused Renderer3 milestone, **not complete desktop Renderer3 parity**.
Windows-only wrapped legacy engines, project-card-data interpretation, full SmartBadge
v2/SmartCard animation sequences, groups and exact overlay resources are rejected at
import rather than being silently approximated. Embedded renderer audio is not
currently read from the Renderer3 package; use the app's soundtrack selector.

## Build and test

With JDK 17, Gradle 8.13 and Android SDK 36:

```bash
gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug
```

GitHub Actions compiles, tests and publishes the Android M2 GitHub prerelease
with a **debug-signed APK**. An APK from a different signing key will need
reinstallation; permanent production signing is a future milestone.

## M3: Wrapped legacy ribbon Renderer3 compatibility

- The import now reads embedded `CCRNDR01` ribbon-exact renderers referenced by the outer `CCRNDR03` scene, instead of rejecting all wrappers.
- Native ribbon player uses the wrapped source animation tracks, including opening-card positions and segmented conveyor scroll.
- Live SmartCard text/artwork and overlays are composed from `desc.txt` + `smart.json` sidecars, and per-card SmartBadge v2 ZIP packages are selected from `smartbadge-v2.json`.
- Badge frames/shine, settled holds and author-specified frame text rectangles are sampled by integer frame; image layers are decoded using a small bounded cache suitable for the Snapdragon 429 class.
- Video length adapts to the number of cards instead of forcing the original reference card count. The renderer is shared by preview and MP4 export.
- Where present, the Renderer3 package's embedded M4A soundtrack is extracted to private app storage and selected automatically when no other soundtrack is already chosen.
- Renderer3 packages that use other Windows-only legacy engines still receive an explanatory error instead of an inaccurate approximation.
- Requires a genuine wrapped **ribbon-exact** engine. Non-ribbon Windows engine types and packages requiring unsupported overlays are still rejected instead of incorrectly rendering.


## M4: Rust + Jetpack Compose (preview)

Android v5 uses Kotlin / Jetpack Compose for editor UI, Android Canvas for painting
and MediaCodec for MP4 encoding. The dependency-free Rust core in `native/`
now evaluates built-in timeline easing, Renderer3 keyframe easing and SmartBadge
frame selection for both preview and export through JNI. Existing project
identifiers and Renderer3 archive formats are unchanged.

AI integrations are deprecated for Android v5. No AI SDK, provider API key or
AI background service is required. Local artwork and CSV import are unaffected;
optional user-specified remote artwork is not an AI integration. Desktop legacy
AI code is outside this Android migration.

Native build (JDK 17, Gradle 8.13, SDK 36, NDK 27.2.12479018, Rust stable):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
cargo test --manifest-path android/native/Cargo.toml --locked
CTS_BUILD_RUST=1 gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug
```

Set ANDROID_NDK_HOME and ANDROID_NDK_ROOT to the same installed NDK directory.
The Gradle task uses cargo-ndk 4.1.2 with `-P 26` (uppercase P selects the Android API; lowercase p selects a Cargo package). The preview workflow runs
Rust and Kotlin tests and verifies all three native libraries in the APK before
publishing the M4 debug prerelease. Non-native host builds retain the Kotlin
reference implementation. In a native Android build, a missing or incompatible
Rust library is an explicit error, not an unnoticed performance fallback.

This is an incremental native-core migration, not completion of every v5
feature. Android Canvas painting, archive decoding and MediaCodec remain Kotlin.
Device-level playback/export tests, unsupported legacy renderer engines and
production signing remain outstanding.
