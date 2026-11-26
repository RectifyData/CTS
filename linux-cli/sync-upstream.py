#!/usr/bin/env python3
"""Stage the original Windows renderer with the three isolated CLI adaptations."""
from pathlib import Path
import shutil
base = Path(__file__).resolve().parent
core = base.parent / 'windows/CubicalCompare/Core'
out = base / 'src/Upstream'
if not core.is_dir():
    raise SystemExit('Run inside the CTS repository.')
project = ['AppDataPaths','CsvImportService','ImageTransformScale','ProjectFileService','ProjectModel','RenderFontSelection','WebImageSource']
renderer = ['InternalCodeOverrideManager','LegacyRendererAdapter','RendererPackageProbe','RendererV3PackageCompatibility']
legacy = ['InfiniteTimelineRenderer','Models','RelationshipsRenderer','RendererBundle','RendererEngine','RendererExportRegressionChecks','RendererRuntimeVersion','SmartBadgeSequence']
if out.exists():
    shutil.rmtree(out)
for folder, names in [('Project', project), ('Renderer', renderer), ('Renderer/Legacy', legacy)]:
    for name in names:
        src = core / folder / (name + '.cs')
        dest = out / folder / src.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        data = src.read_text(encoding='utf-8-sig')
        changes = {
            'AppDataPaths': ('"CubicalCompareV5Preview"', '"CubicalCompareDebianCLI"'),
            'InternalCodeOverrideManager': ('"CubicalCompare",\n        "code-overrides"', '"CubicalCompareDebianCLI",\n        "code-overrides"'),
            'LegacyRendererAdapter': ('    public static LegacyRendererAdapter Load(string path)', '    public static LegacyRendererAdapter CreateBuiltIn() => new(Legacy.RendererSpec.BuiltIn(), "<built-in>");\n\n    public static LegacyRendererAdapter Load(string path)')
        }
        if name in changes:
            old, new = changes[name]
            if data.count(old) != 1:
                raise SystemExit(f'Upstream changed: {src}; review CLI adapter.')
            data = data.replace(old, new, 1)
        dest.write_text(data, encoding='utf-8')
print('Staged 19 upstream source files.')
