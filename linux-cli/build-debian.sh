#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
case "${1:-$(uname -m)}" in
  x86_64|amd64|linux-x64) arch=amd64; rid=linux-x64;;
  aarch64|arm64|linux-arm64) arch=arm64; rid=linux-arm64;;
  *) echo 'Supported architectures: amd64, arm64' >&2; exit 2;;
esac
command -v dotnet >/dev/null || { echo 'Install .NET 10 SDK' >&2; exit 1; }
python3 sync-upstream.py
version=5.0.0~preview.1
mkdir -p dist
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
name="cubical-compare_${version}_${arch}"
portable="$work/cubical-compare"
dotnet publish src/CubicalCompare.Cli.csproj -c Release -r "$rid" --self-contained true -o "$portable"
install -m 644 ../LICENSE "$portable/LICENSE"
install -m 644 README.md "$portable/README.md"
test -x "$portable/cubical-compare"
root="$work/pkg"
mkdir -p "$root/DEBIAN" "$root/usr/lib/cubical-compare" "$root/usr/bin" "$root/usr/share/doc/cubical-compare"
cp -a "$portable/." "$root/usr/lib/cubical-compare/"
cat > "$root/usr/bin/cubical-compare" <<'SH'
#!/bin/sh
exec /usr/lib/cubical-compare/cubical-compare "$@"
SH
chmod 755 "$root/usr/bin/cubical-compare"
cp ../LICENSE "$root/usr/share/doc/cubical-compare/copyright"
cat > "$root/DEBIAN/control" <<CONTROL
Package: cubical-compare
Version: $version
Section: video
Priority: optional
Architecture: $arch
Maintainer: Cubical Compare project
Depends: ffmpeg, libfontconfig1, libfreetype6, libc6, libgcc-s1, libstdc++6, zlib1g, libicu76 | libicu72, libssl3t64 | libssl3, fonts-dejavu-core
Description: Cubical Compare headless renderer and MP4 exporter
 Standalone Debian CLI based on the Windows V5 renderer source.
CONTROL
dpkg-deb --root-owner-group --build "$root" "dist/$name.deb"
tar -C "$work" -czf "dist/$name.tar.gz" cubical-compare
(cd dist; sha256sum "$name.deb" "$name.tar.gz" > "SHA256SUMS-$arch.txt")
