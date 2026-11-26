#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
# Optional first positional architecture preserves the existing interface.
arch_arg=$(uname -m)
if [[ $# -gt 0 && "$1" != --* ]]; then arch_arg=$1; shift; fi
if [[ "${1:-}" == --help ]]; then
  echo 'Usage: bash build-debian.sh [amd64|arm64] [--source DIR] [--source-ref LABEL] [--version X.Y.Z] [--channel auto|stable|preview] [--revision N] [--deb]'
  exit 0
fi
case "$arch_arg" in
  x86_64|amd64|linux-x64) arch=amd64; rid=linux-x64;;
  aarch64|arm64|linux-arm64) arch=arm64; rid=linux-arm64;;
  *) echo 'Supported architectures: amd64, arm64' >&2; exit 2;;
esac
command -v dotnet >/dev/null || { echo 'Install the .NET SDK required by the source' >&2; exit 1; }
# Portable is always built. Debian packaging is opt-in.
make_deb=false
source_args=()
while (($#)); do
  case "$1" in
    --deb) make_deb=true; shift;;
    --source|--source-ref|--version|--channel|--revision)
      if (($# < 2)); then echo "Missing value for $1" >&2; exit 2; fi
      source_args+=("$1" "$2"); shift 2;;
    *) echo "Unknown argument: $1" >&2; exit 2;;
  esac
done
if $make_deb; then command -v dpkg-deb >/dev/null || { echo 'Install dpkg for --deb' >&2; exit 1; }; fi
python3 sync-upstream.py "${source_args[@]}"
version=$(python3 -c 'import json; print(json.load(open("src/upstream.json"))["package_version"])')
source=$(python3 -c 'import json; print(json.load(open("src/upstream.json"))["source_ref"])')
echo "Building $version from $source ($arch)"
mkdir -p dist
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
name="cubical-compare_${version}_${arch}"
portable="$work/cubical-compare"
dotnet publish src/CubicalCompare.Cli.csproj -c Release -r "$rid" --self-contained true -o "$portable"
install -m 644 ../LICENSE "$portable/LICENSE"
install -m 644 README.md "$portable/README.md"
install -m 644 src/upstream.json "$portable/UPSTREAM.json"
test -x "$portable/cubical-compare"
archive="$name-portable.tar.gz"
tar -C "$work" -czf "$work/$archive" cubical-compare
cp "$work/$archive" "dist/$archive"
cp src/upstream.json "dist/UPSTREAM-$arch.json"
artifacts=("$archive" "UPSTREAM-$arch.json")
if $make_deb; then
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
 Standalone Debian CLI based on the selected Windows renderer source.
CONTROL
dpkg-deb --root-owner-group --build "$root" "dist/$name.deb"
artifacts+=("$name.deb")
fi
(cd dist; sha256sum "${artifacts[@]}" > "SHA256SUMS-$arch.txt")
printf 'Portable archive: %s\n' "$PWD/dist/$archive"
if $make_deb; then printf 'Optional Debian package: %s\n' "$PWD/dist/$name.deb"; fi
