# Cubical Compare V5 — Debian CLI

The standalone .NET 10 / SkiaSharp CLI stages 19 source files directly from the Windows V5 renderer on this branch. No GUI, Wine, or display server is required. FFmpeg is a system dependency.

Build from the CTS root with `bash linux-cli/build-debian.sh` (requires .NET 10 SDK, Python 3 and dpkg). Install with `sudo apt install ./linux-cli/dist/cubical-compare_*_$(dpkg --print-architecture).deb`.

```sh
cubical-compare doctor
cubical-compare import cards.csv -o project.ccproject
cubical-compare preview project.ccproject --renderer model.renderer3 --frame 240 -o frame.png
cubical-compare render project.ccproject --renderer model.renderer3 -o video.mp4
```

Use --help for additional options, --assets-root to relocate Windows assets by basename and --font for a matching licensed font. Renderer v2/v3, original SmartBadge evaluation, CSV and soundtrack support are integrated. Default encoder is libx264; select hardware encoders explicitly if supported by the local FFmpeg and drivers. FPS mismatches may change authored duration. No GUI, thumbnail editor, or MegaPack library manager is included. Exact cross-platform pixel parity is not certified.

The build stages shared files into ignored src/Upstream, changing only the built-in factory and isolated settings/override paths. The GitHub workflow builds AMD64 and ARM64 packages, tests Debian 12/13 installations and publishes a prerelease only after both succeed.
