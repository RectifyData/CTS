# Cubical Compare — Debian CLI

The standalone .NET / SkiaSharp CLI stages the selected Windows project's shared Core/Project and Core/Renderer source directories. Framework, SkiaSharp and Roslyn versions are read from the selected source. No GUI, Wine, or display server is required. FFmpeg is a system dependency.

Build from the CTS root with `bash linux-cli/build-debian.sh` (requires the matching .NET SDK, Python 3 and tar). The default output is a self-contained portable `.tar.gz` archive, not an installable Debian package. Extract it anywhere and execute directly, with no root permissions or .NET SDK/runtime installation needed. FFmpeg and system libraries must be available. For an optional `.deb`, pass `--deb` explicitly (requires dpkg).

```sh
# Substitute the actual archive filename for your version and architecture.
tar -xzf linux-cli/dist/cubical-compare_VERSION_ARCH-portable.tar.gz
./cubical-compare/cubical-compare doctor
./cubical-compare/cubical-compare import cards.csv -o project.ccproject
./cubical-compare/cubical-compare preview project.ccproject --renderer model.renderer3 --frame 240 -o frame.png
./cubical-compare/cubical-compare render project.ccproject --renderer model.renderer3 -o video.mp4
```

Use --help for additional options, --assets-root to relocate Windows assets by basename and --font for a matching licensed font. Renderer v2/v3, original SmartBadge evaluation, CSV and soundtrack support are integrated. Default encoder is libx264; select hardware encoders explicitly if supported by the local FFmpeg and drivers. FPS mismatches may change authored duration. No GUI, thumbnail editor, or MegaPack library manager is included. Exact cross-platform pixel parity is not certified.

The build stages shared files into ignored src/Upstream, changing only the built-in factory and isolated settings/override paths. The GitHub workflow builds AMD64 and ARM64 portable archives, tests direct execution on Debian 12/13 and publishes a prerelease only after both succeed.

## Build another version

The same builder supports compatible Windows branches, tags and checkouts. It never assumes a fixed product version. For a separate source checkout:

```sh
git fetch origin
git worktree add --detach /tmp/cts-source origin/windows/main-preview
bash linux-cli/build-debian.sh amd64 --source /tmp/cts-source --source-ref windows/main-preview
# Or select a different checkout and explicitly identify the release:
bash linux-cli/build-debian.sh arm64 --source /tmp/cts-source --version 5.1.0 --channel preview --revision 2
```

Version defaults to the selected Windows project's Version; for the current Windows preview branch it reads the version override from the V5 packaging workflow when present. Use --version for other pipeline overrides and --channel stable or preview for release identity. The portable archive version, executable --version, build provenance and release tag derive from that selection. The compiler fails explicitly if a future version changes the source layout or required adapter APIs. Older incompatible architectures are not silently emulated.

GitHub Actions supports a source ref (branch, tag or commit), optional version override, channel and package revision via workflow_dispatch. The checkout is pinned to one SHA for both architecture builds. Portable binary publication requires both architectures to pass Debian 12 and 13 tests. Releases contain the portable archives, checksums and source provenance; CI never builds a .deb.
