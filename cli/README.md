# Cubical Compare V5 — standalone CLI

Cross-platform command-line comparison-video renderer requiring no Qt, Android device or Windows GUI. Source is `ccrender.py`; packaged builds use PyInstaller to include Python and an FFmpeg executable via `imageio-ffmpeg`.

## Use

```sh
python -m pip install -r cli/requirements.txt
python cli/ccrender.py inspect cli/examples/demo.csv
python cli/ccrender.py preview cli/examples/demo.csv -o frame.png --time 2
python cli/ccrender.py render cli/examples/demo.csv -o comparison.mp4 --fps 30
python cli/ccrender.py render project.ccv5 -o comparison.mp4 --width 1920 --height 1080 --fps 60
```

Downloaded standalone executable:

```sh
ccrender inspect project.ccv5
ccrender render project.ccv5 -o comparison.mp4
ccrender preview project.ccv5 -o screenshot.png --time 2.5 --thumbnail
```

Supported sources: UTF-8 CSV/TSV, Windows/Android V5 `.ccv5` (portable JSON model and embedded Base64 artwork), and CTS v2 spreadsheet-style JSON. Supports per-card image transforms (scale, flip, rotation and offset), four fixed-width cards, staged reveals, eased one-card scrolling, end fade, duration override, basic audio input and MP4/H.264 encoding. The `--audio` switch can be repeated; audio is concatenated in the supplied order. A project's `AudioTracks` can be used when file paths are valid on the current machine.

**Limitations:** This standalone renderer uses its own Pillow design rather than the exact Windows WPF/Android Canvas pixels, and does not execute arbitrary .renderer3 scripts, ZIP plugins or InkCanvas ISF drawing strokes. Remote image URLs and Android `content://` references require local/embedded equivalents. Multi-track pattern repeat is not currently implemented and may end with the video. Preview and MP4 use the same Pillow rendering path. Do not claim frame-perfect native GUI parity until pixel-diff tests prove it.

Tests: `python -m unittest discover -s cli -p 'test_*.py' -v`. CI builds Ubuntu and Windows standalone commands, Windows V5 GUI installer/portable ZIP and Android V5 sideload APK. Native binaries are experimental prereleases until real device testing verifies them.
