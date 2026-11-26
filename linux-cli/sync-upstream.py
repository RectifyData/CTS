#!/usr/bin/env python3
"""Stage one compatible Windows checkout and derive its Linux build metadata."""
import argparse
import json
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

base = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, default=base.parent)
parser.add_argument("--version", help="Override the Windows product version (e.g. 5.1.0)")
parser.add_argument("--channel", choices=["auto", "stable", "preview"], default="auto")
parser.add_argument("--revision", type=int, default=1, help="Debian packaging revision")
parser.add_argument("--source-ref", default="", help="Source ref label for detached CI checkout")
args = parser.parse_args()
root = args.source.resolve()
core = root / "windows/CubicalCompare/Core"
module = root / "windows/CubicalCompare.Modules/Renderer/CubicalCompare.Renderer.csproj"
app = root / "windows/CubicalCompare/CubicalCompare.csproj"
def git(*cmd):
    try:
        return subprocess.check_output(["git", "-C", str(root), *cmd], text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""
def xml(path):
    if not path.is_file():
        raise SystemExit(f"Unsupported source layout: {path} missing.")
    return ET.parse(path).getroot()
def required(tree, key):
    value = tree.findtext(".//" + key)
    if not value or "$(" in value:
        raise SystemExit(f"Cannot resolve {key}; update the adapter for this source version.")
    return value.strip()
app_tree, module_tree = xml(app), xml(module)
source_ref = args.source_ref or git("symbolic-ref", "--short", "HEAD") or "detached"
source_sha = git("rev-parse", "HEAD") or "unknown"
preview = args.channel == "preview" or (args.channel == "auto" and source_ref.removeprefix("refs/heads/") in {"windows/main-preview", "linux/debian-cli"})
version = args.version
if not version and preview:
    workflow = root / ".github/workflows/v5-windows.yml"
    if workflow.is_file():
        versions = set(re.findall(r"-p:Version=(\d+(?:\.\d+){1,3})(?=\s|$)", workflow.read_text(encoding="utf-8")))
        if len(versions) == 1:
            version = versions.pop()
        elif len(versions) > 1:
            raise SystemExit("Preview workflow has multiple product versions; pass --version.")
version = version or required(app_tree, "Version")
if not re.fullmatch(r"\d+(?:\.\d+){1,3}", version) or any(int(x) > 65534 for x in version.split(".")):
    raise SystemExit("Product version must have 2–4 numeric components, each at most 65534.")
if args.revision < 1:
    raise SystemExit("--revision must be positive.")
framework = required(module_tree, "TargetFramework")
match = re.fullmatch(r"net(\d+)\.0", framework)
if not match:
    raise SystemExit(f"Unsupported renderer target framework: {framework}")
packages = {p.attrib["Include"]: p.attrib.get("Version", "") for p in module_tree.findall(".//PackageReference")}
for name in ("SkiaSharp", "Microsoft.CodeAnalysis.CSharp"):
    if not re.fullmatch(r"[0-9][0-9A-Za-z.+-]*", packages.get(name, "")):
        raise SystemExit(f"Cannot resolve upstream package version for {name}.")
package_version = version + (f"~preview.{args.revision}" if preview else f"-{args.revision}")
app_version = version + (f"-debian-preview.{args.revision}" if preview else f"-debian.{args.revision}")
required_files = ["Project/AppDataPaths.cs", "Project/CsvImportService.cs", "Project/ProjectFileService.cs", "Project/ProjectModel.cs", "Project/RenderFontSelection.cs", "Project/WebImageSource.cs", "Renderer/LegacyRendererAdapter.cs", "Renderer/InternalCodeOverrideManager.cs", "Renderer/Legacy/RendererExportRegressionChecks.cs"]
for file in required_files:
    if not (core / file).is_file():
        raise SystemExit(f"Unsupported source layout: {file} missing. No fallback renderer will be used.")
metadata = dict(product_version=version, package_version=package_version, app_version=app_version, channel="preview" if preview else "stable", revision=args.revision, source_ref=source_ref, source_sha=source_sha, source_dirty=bool(git("status","--porcelain","--untracked-files=no")), framework=framework, sdk=match.group(1)+".0.x", skia=packages["SkiaSharp"], roslyn=packages["Microsoft.CodeAnalysis.CSharp"])
def replace_once(data, old, new, name):
    if data.count(old) != 1:
        raise SystemExit(f"Upstream changed: {name}; review CLI adapter.")
    return data.replace(old, new, 1)
src = base / "src"
with tempfile.TemporaryDirectory(dir=src) as temp:
    stage = Path(temp) / "Upstream"
    count = 0
    for folder in ("Project", "Renderer"):
        for path in sorted((core / folder).rglob("*.cs")):
            relative = path.relative_to(core)
            if any(part in ("obj", "bin") for part in relative.parts):
                continue
            data = path.read_text(encoding="utf-8-sig")
            if relative.as_posix() == "Project/AppDataPaths.cs":
                # Isolate stable and preview regardless of upstream conditional symbols.
                data = replace_once(data, '"CubicalCompareV5Preview"', '"CubicalCompareDebianCLIPreview"', relative) if '"CubicalCompareV5Preview"' in data else data
                data = replace_once(data, '"CubicalCompare"', '"CubicalCompareDebianCLI"', relative)
            elif relative.as_posix() == "Renderer/InternalCodeOverrideManager.cs":
                data = replace_once(data, '"CubicalCompare",\n        "code-overrides"', '"CubicalCompareDebianCLI",\n        "code-overrides"', relative)
            elif relative.as_posix() == "Renderer/LegacyRendererAdapter.cs":
                data = replace_once(data, "    public static LegacyRendererAdapter Load(string path)", '    public static LegacyRendererAdapter CreateBuiltIn() => new(Legacy.RendererSpec.BuiltIn(), "<built-in>");\n\n    public static LegacyRendererAdapter Load(string path)', relative)
            dest = stage / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(data, encoding="utf-8")
            count += 1
    props = ET.Element("Project")
    group = ET.SubElement(props, "PropertyGroup")
    for k,v in {"TargetFramework":framework, "Version":version, "InformationalVersion":app_version, "SkiaVersion":metadata["skia"], "RoslynVersion":metadata["roslyn"]}.items():
        ET.SubElement(group,k).text = v
    if preview:
        ET.SubElement(group,"DefineConstants").text = "$(DefineConstants);V5_M1_PREVIEW"
    out = src / "Upstream"
    if out.exists():
        shutil.rmtree(out)
    stage.rename(out)
    ET.ElementTree(props).write(src / "Upstream.props", encoding="utf-8", xml_declaration=True)
    (src / "upstream.json").write_text(json.dumps(metadata, indent=2)+"\n", encoding="utf-8")
print(f"Staged {count} source files: {source_ref} ({source_sha}), {app_version}, {framework}")
