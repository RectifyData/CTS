#!/usr/bin/env bash
set -euo pipefail
cli=${1:-cubical-compare}
if [[ "$cli" == */* ]]; then cli=$(realpath "$cli"); fi
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cd "$work"
export DISPLAY= WAYLAND_DISPLAY=
"$cli" --version
"$cli" doctor
"$cli" self-test
cat > cards.csv <<'CSV'
Number,Title,Badge Header,Badge Value,Badge Unit,Image,Description
1,Alpha,1 in,20,People,,"A quoted, description"
2,Beta,1 in,30,People,,Another card
3,Gamma,1 in,40,People,,Third card
4,Delta,1 in,50,People,,Fourth card
5,Epsilon,1 in,60,People,,Fifth card
CSV
python3 - <<'PY'
import gzip, json, struct, zlib
for filename,magic,manifest in [
 ('test.renderer2', b'CCRNDR01', {'formatVersion':2,'rendererApi':2,'id':'cli.smoke.v2','name':'CLI smoke v2','engine':'native-standard','referenceFps':60}),
 ('test.renderer3', b'CCRNDR03', {'api':3,'id':'cli.smoke.v3','name':'CLI smoke v3','canvas':{'width':1920,'height':1080,'fps':60},'timeline':{'frames':60,'clock':'absolute'},'objects':[], 'checkpoints':[0]})]:
    payload=gzip.compress(json.dumps(manifest).encode(),mtime=0)
    open(filename,'wb').write(magic+struct.pack('>III',1,len(payload),zlib.crc32(payload))+payload)
PY
"$cli" import cards.csv -o project.ccproject --width 640 --height 360 --fps 60
"$cli" inspect project.ccproject > info.json
"$cli" renderer-info test.renderer2
"$cli" renderer-info test.renderer3
"$cli" preview project.ccproject --renderer test.renderer2 --frame 0 -o frame.png
"$cli" preview project.ccproject --renderer test.renderer3 --frame 0 -o frame3.png
"$cli" render project.ccproject --renderer test.renderer2 --frames 12 --no-audio --preset ultrafast -o silent.mp4
ffmpeg -hide_banner -loglevel error -f lavfi -i sine=frequency=440:duration=1 -y music.wav
"$cli" render project.ccproject --renderer test.renderer2 --frames 12 --audio music.wav --preset ultrafast -o audio.mp4
ffprobe -v error -count_frames -show_streams -of json audio.mp4 > audio.json
ffprobe -v error -count_frames -show_streams -of json silent.mp4 > silent.json
python3 - <<'PY'
import json,struct
from pathlib import Path
p=json.load(open('project.ccproject'));assert len(p['Project']['Cards'])==5
assert p['Project']['Cards'][0]['BadgeValue']=='20'
assert p['Project']['Cards'][0]['Description']=='A quoted, description'
for name in ['frame.png','frame3.png']:
 b=Path(name).read_bytes();assert b[:8]==b'\x89PNG\r\n\x1a\n';assert struct.unpack('>II',b[16:24])==(640,360)
for name,audio in [('silent.json',False),('audio.json',True)]:
 streams=json.load(open(name))['streams'];v=next(s for s in streams if s['codec_type']=='video');assert int(v['nb_read_frames'])==12,v;assert (v['width'],v['height'])==(640,360);assert any(s['codec_type']=='audio' for s in streams)==audio
PY
if "$cli" preview project.ccproject --frame 0 -o frame.png; then exit 1; fi
if "$cli" preview project.ccproject --frame 99999999 -o invalid.png; then exit 1; fi
if "$cli" render project.ccproject --frames 2 --ffmpeg /nonexistent -o bad.mp4; then exit 1; fi
[[ ! -e bad.mp4 && ! -e invalid.png ]]
! compgen -G '*.partial-*' >/dev/null
printf 'CLI smoke passed\n'
