#!/usr/bin/env python3
"""Cubical Compare offline CLI renderer. Pillow + FFmpeg, no Qt required."""
from __future__ import annotations

import argparse
import base64
import csv
import io
import json
import math
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont, ImageOps

VERSION = '5.0.0-cli-preview.1'
BACK = (13, 18, 34)
TEXT = (245, 248, 255)
MUTED = (181, 193, 211)


class CLIError(Exception):
    pass


@dataclass
class Card:
    title: str = ''
    badge: str = ''
    header: str = ''
    unit: str = ''
    description: str = ''
    color: str = '#EC394D'
    artwork: str = ''
    artwork_b64: str = ''
    thumb_b64: str = ''
    transform: dict[str, Any] = field(default_factory=dict)
    thumb_transform: dict[str, Any] = field(default_factory=dict)


@dataclass
class Project:
    cards: list[Card]
    fps: int = 30
    seconds_per_card: float = 2.0
    duration: float = 0
    width: int = 1920
    height: int = 1080
    audio: list[str] = field(default_factory=list)
    audio_volume: float = 1.0
    audio_repeat: bool = True
    audio_fade: bool = True


def field(row: dict[str, Any], *names: str) -> str:
    normalized = {str(k).strip().lower().replace('_', '').replace(' ', ''): str(v or '') for k, v in row.items()}
    for name in names:
        result = normalized.get(name.lower().replace('_', '').replace(' ', ''))
        if result is not None:
            return result
    return ''


def _source(path: str, directory: Path) -> str:
    if not path or path.startswith(('https://', 'http://', 'content://')):
        return path
    candidate = Path(path).expanduser()
    return str((directory / candidate).resolve()) if not candidate.is_absolute() else str(candidate)


def load_project(path: Path) -> Project:
    if not path.is_file():
        raise CLIError(f'Input not found: {path}')
    base = path.resolve().parent
    if path.suffix.lower() in ('.csv', '.tsv'):
        with path.open('r', encoding='utf-8-sig', newline='') as f:
            reader = csv.DictReader(f, delimiter='\t' if path.suffix.lower() == '.tsv' else ',')
            if not reader.fieldnames:
                raise CLIError('CSV has no header row')
            cards = [Card(title=field(r, 'Title', 'Name'), badge=field(r, 'Badge Value', 'Number', 'Value', 'Highlight', 'Badge'),
                          header=field(r, 'Badge Header', 'Label'), unit=field(r, 'Badge Unit', 'Unit'),
                          description=field(r, 'Description'), color=field(r, 'Badge Color', 'Colour') or '#EC394D',
                          artwork=_source(field(r, 'Image', 'Artwork', 'Image URL', 'Web URL', 'Highlight Image'), base))
                     for r in reader if any((v or '').strip() for v in r.values())]
        return Project(cards)
    try:
        content = json.loads(path.read_text(encoding='utf-8-sig'))
    except (UnicodeError, json.JSONDecodeError) as e:
        raise CLIError(f'Invalid project JSON: {e}') from e
    if not isinstance(content, dict):
        raise CLIError('Project JSON must be an object')
    if 'spreadsheet' in content:
        sheet = content['spreadsheet']
        headers = sheet.get('headers', [])
        cards = []
        for values in sheet.get('rows', []):
            row = dict(zip(headers, values))
            cards.append(Card(title=field(row, 'Title'), badge=field(row, 'Value', 'Highlight'),
                              header=field(row, 'Label'), description=field(row, 'Description'),
                              artwork=_source(field(row, 'Image'), base)))
        settings = content.get('settings', {})
        project = Project(cards, fps=settings.get('fps', 30), duration=settings.get('custom_duration') or 0,
                          width=settings.get('width', 1920), height=settings.get('height', 1080))
        return project
    raw_cards = content.get('Cards', content.get('cards', []))
    if not isinstance(raw_cards, list):
        raise CLIError('Cards must be an array')
    cards = []
    for entry in raw_cards:
        if not isinstance(entry, dict):
            raise CLIError('Each card must be an object')
        cards.append(Card(title=field(entry, 'Title'), badge=field(entry, 'Badge', 'uploaded'),
                          header=field(entry, 'BadgeHeader', 'badge_label'), unit=field(entry, 'BadgeUnit'),
                          description=field(entry, 'Description'), color=field(entry, 'BadgeColor') or '#EC394D',
                          artwork=_source(field(entry, 'ArtworkSource', 'Image'), base),
                          artwork_b64=field(entry, 'ArtworkBase64'), thumb_b64=field(entry, 'ThumbnailArtworkBase64'),
                          transform=entry.get('ImageTransform') or {}, thumb_transform=entry.get('ThumbnailTransform') or {}))
    tracks = content.get('AudioTracks', [])
    return Project(cards, fps=content.get('Fps', 30), seconds_per_card=content.get('SecondsPerCard', 2),
                   duration=content.get('VideoDurationSeconds', 0), width=content.get('ExportWidth', 1920),
                   height=content.get('ExportHeight', 1080),
                   audio=[_source(str(t), base) for t in tracks] if isinstance(tracks, list) else [],
                   audio_volume=content.get('AudioVolume', 1.0),
                   audio_repeat=content.get('AudioRepeat', True), audio_fade=content.get('AudioFadeOut', True))


def check(p: Project) -> None:
    if not p.cards or len(p.cards) > 500:
        raise CLIError('Project must have between 1 and 500 cards')
    if any(not isinstance(v, (float, int)) or not math.isfinite(v) for v in (p.fps, p.width, p.height, p.duration, p.seconds_per_card, p.audio_volume)):
        raise CLIError('Nonfinite or nonnumeric project setting')
    if not (1 <= p.fps <= 120 and 320 <= p.width <= 3840 and 180 <= p.height <= 2160):
        raise CLIError('FPS or resolution is outside supported limits')
    if p.width % 2 or p.height % 2:
        raise CLIError('Export resolution must have even dimensions')
    if p.seconds_per_card <= 0 or p.duration < 0 or (0 < p.duration < 1/p.fps) or not (0 <= p.audio_volume <= 1):
        raise CLIError('Invalid duration, card speed, or audio volume')


def duration(p: Project) -> float:
    return float(p.duration or (2.3 + max(0, len(p.cards) - 4) * p.seconds_per_card + 1.8))


def _ease(v: float) -> float:
    v = max(0.0, min(1.0, v))
    return v * v * (3.0 - 2.0 * v)


def _color(value: str) -> tuple[int, int, int]:
    value = value.lstrip('#')
    if len(value) == 6:
        try:
            return tuple(int(value[i:i+2], 16) for i in (0, 2, 4))  # type: ignore
        except ValueError:
            pass
    return (236, 57, 77)


@lru_cache(maxsize=64)
def _font(px: int, bold: bool = False):
    candidates = ['C:/Windows/Fonts/arialbd.ttf' if bold else 'C:/Windows/Fonts/arial.ttf',
                  '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf' if bold else '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
                  '/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf' if bold else '/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf']
    for candidate in candidates:
        if Path(candidate).is_file():
            return ImageFont.truetype(candidate, max(9, px))
    return ImageFont.load_default(size=max(9, px))


def _fit_text(d: ImageDraw.ImageDraw, content: str, max_width: int, size: int, bold: bool = False):
    content = content.strip()
    for px in range(size, max(10, size // 2) - 1, -1):
        font = _font(px, bold)
        if d.textbbox((0, 0), content, font=font)[2] <= max_width:
            return content, font
    font = _font(max(10, size // 2), bold)
    while content and d.textbbox((0, 0), content + '…', font=font)[2] > max_width:
        content = content[:-1]
    return content + ('…' if content else ''), font


class Assets:
    def __init__(self):
        self.cache: dict[str, Image.Image | None] = {}
        self.layers: dict[tuple[int, int, int, bool], Image.Image] = {}

    def load(self, card: Card, thumbnail: bool = False) -> Image.Image | None:
        encoded = (card.thumb_b64 if thumbnail and card.thumb_b64 else card.artwork_b64)
        key = ('b64:' + encoded) if encoded else card.artwork
        if not key:
            return None
        if key in self.cache:
            return self.cache[key]
        if len(key) > 60_000_000:
            raise CLIError('Artwork exceeds maximum supported size')
        try:
            if encoded:
                raw = base64.b64decode(encoded, validate=True)
                if len(raw) > 32_000_000:
                    raise CLIError('Decoded artwork is too large')
                img = Image.open(io.BytesIO(raw))
            elif card.artwork.startswith(('http://', 'https://', 'content://')):
                raise CLIError('Remote or Android content:// artwork is not portable: use embedded artwork or a local image')
            else:
                img = Image.open(card.artwork)
            img.load()
            result = ImageOps.exif_transpose(img).convert('RGBA')
        except (OSError, ValueError, base64.binascii.Error) as e:
            raise CLIError(f'Could not load artwork {card.artwork or "(embedded)"}: {e}') from e
        self.cache[key] = result
        return result


def _artwork(assets: Assets, card: Card, w: int, h: int, thumbnail: bool) -> Image.Image | None:
    image = assets.load(card, thumbnail)
    if image is None:
        return None
    trans = card.thumb_transform if thumbnail else card.transform
    def get(name, default):
        return trans.get(name, trans.get(name[0].lower() + name[1:], default))
    scale = float(get('Scale', 1) or 1)
    scale = max(.05, min(scale, 15))
    image = ImageOps.contain(image, (max(1, round(w*scale)), max(1, round(h*scale))))
    if get('FlipHorizontal', False):
        image = ImageOps.mirror(image)
    if get('FlipVertical', False):
        image = ImageOps.flip(image)
    rotation = float(get('Rotation', 0) or 0)
    if rotation:
        image = image.rotate(-rotation, resample=Image.Resampling.BICUBIC, expand=True)
    canvas = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ox = round(float(get('X', 0) or 0) * w / 960)
    oy = round(float(get('Y', 0) or 0) * h / 540)
    canvas.alpha_composite(image, ((w-image.width)//2+ox, (h-image.height)//2+oy))
    return canvas


def _card_layer(card: Card, w: int, h: int, assets: Assets, thumbnail: bool = False) -> Image.Image:
    card_img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(card_img)
    radius = max(5, w // 35)
    d.rounded_rectangle((0, 0, w-2, h-2), radius, fill=(31, 41, 65, 255), outline=(66, 79, 108, 255), width=max(1, w//240))
    pad = max(8, w//18)
    badge_h = round(h*.17)
    d.rounded_rectangle((pad, pad, w-pad, badge_h), max(5, radius//2), fill=_color(card.color)+(255,))
    label, font = _fit_text(d, card.header.upper(), w-3*pad, max(12, w//27), True)
    d.text((pad*1.5, pad*1.14), label, font=font, fill=TEXT)
    value_text = ' '.join(v for v in (card.badge, card.unit) if v)
    val, font = _fit_text(d, value_text, w-3*pad, max(17, w//13), True)
    d.text((pad*1.5, pad*2.25), val, font=font, fill=TEXT)
    title, title_font = _fit_text(d, card.title, w-2*pad, max(12, w//14), True)
    d.text((pad, round(h*.20)), title, font=title_font, fill=TEXT)
    art_y = round(h*.30)
    art_h = round(h*.48)
    d.rounded_rectangle((pad, art_y, w-pad, art_y+art_h), radius//2, fill=(48, 63, 87, 255))
    art = _artwork(assets, card, w-2*pad, art_h, thumbnail)
    if art:
        card_img.alpha_composite(art, (pad, art_y))
    else:
        hint, f = _fit_text(d, 'ARTWORK', w-3*pad, max(11, w//23))
        d.text((pad*1.7, art_y+art_h//2), hint, font=f, fill=MUTED)
    remaining = card.description.replace('\n', ' ').strip()
    lines = []
    while remaining and len(lines) < 3:
        words = remaining.split(' ')
        line = words.pop(0)
        f = _font(max(10, w//28))
        while words and d.textbbox((0, 0), line+' '+words[0], font=f)[2] < w-2*pad:
            line += ' '+words.pop(0)
        lines.append(line)
        remaining = ' '.join(words)
    for i, line in enumerate(lines):
        d.text((pad, round(h*.81)+i*max(12, w//24)), line, font=_font(max(10, w//28)), fill=MUTED)
    return card_img


def render_frame(p: Project, t: float, assets: Assets, thumbnail: bool = False) -> Image.Image:
    w, h = int(p.width), int(p.height)
    frame = Image.new('RGB', (w, h), BACK)
    n = len(p.cards)
    gap = round(w*.013)
    cw = (w-gap*5)//4
    ch = round(h*.79)
    y = (h-ch)//2
    total = duration(p)
    # Rescale the authored timeline so an override never clips the final card.
    authored = 2.3 + max(0, n - 4) * p.seconds_per_card + 1.8
    model_t = t * authored / total
    base_scroll = max(0, n-4)
    if base_scroll:
        # Eased one-card steps; pause after each card, never shrink the card width.
        progress = max(0.0, model_t-2.3) / p.seconds_per_card
        full = min(base_scroll, int(progress))
        frac = _ease(progress-full) if full < base_scroll else 0
        scroll = min(float(base_scroll), full + frac)
    else:
        scroll = 0.0
    first = max(0, int(scroll)-1)
    last = min(n, int(scroll)+6)
    for index in range(first, last):
        entrance = _ease((model_t - index*.33)/.53) if index < 4 else 1.0
        if entrance <= 0:
            continue
        x = round(gap + index * (cw+gap) - scroll*(cw+gap))
        if x >= w or x+cw < 0:
            continue
        cache_key = (id(p.cards[index]), cw, ch, thumbnail)
        if cache_key not in assets.layers:
            assets.layers[cache_key] = _card_layer(p.cards[index], cw, ch, assets, thumbnail)
        layer = assets.layers[cache_key]
        if entrance < 1:
            # Wipe only the contents; card size/position never changes.
            layer = layer.crop((0, 0, max(1, round(cw*entrance)), ch))
        frame.paste(layer, (x, y), layer)
    if t > total-.8:
        f = _ease((t-(total-.8))/.8)
        frame = Image.blend(frame, Image.new('RGB', frame.size, BACK), f)
    return frame


def ffmpeg_executable() -> str:
    executable = shutil.which('ffmpeg')
    if executable:
        return executable
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, RuntimeError) as e:
        raise CLIError('FFmpeg unavailable. Install FFmpeg or pip install imageio-ffmpeg') from e


def render_video(p: Project, out: Path, fps: int, strict: bool = False) -> None:
    check(p)
    assets = Assets()
    if strict:
        for card in p.cards:
            if card.artwork or card.artwork_b64:
                assets.load(card)
    count = max(1, math.ceil(duration(p) * fps))
    out = out.resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    temp = out.with_name(out.stem+'.partial'+out.suffix)
    if temp.exists():
        temp.unlink()
    ff = ffmpeg_executable()
    args = [ff, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo', '-vcodec', 'rawvideo',
            '-pix_fmt', 'rgb24', '-s', f'{p.width}x{p.height}', '-r', str(fps), '-i', 'pipe:0']
    tracks = [Path(a) for a in p.audio if a]
    for a in tracks:
        if not a.is_file():
            raise CLIError(f'Audio file not found: {a}')
        args += ['-i', str(a)]
    if tracks:
        if len(tracks) == 1:
            audio_src = '[1:a]'
        else:
            audio_src = ''.join(f'[{i}:a:0]' for i in range(1, len(tracks)+1))
        filters = [audio_src + (f'concat=n={len(tracks)}:v=0:a=1,' if len(tracks)>1 else '') +
                   f'volume={p.audio_volume},atrim=0:{duration(p):.4f}' +
                   (f',afade=t=out:st={max(0,duration(p)-.8):.4f}:d=.8' if p.audio_fade else '')+'[finala]']
        args += ['-filter_complex', ';'.join(filters), '-map', '0:v:0', '-map', '[finala]', '-c:a', 'aac', '-b:a', '192k']
    else:
        args += ['-an']
    args += ['-c:v', 'libx264', '-preset', 'medium', '-crf', '19', '-pix_fmt', 'yuv420p',
             '-movflags', '+faststart', '-t', f'{count/fps:.6f}', str(temp)]
    proc = None
    try:
        proc = subprocess.Popen(args, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            assert proc.stdin is not None
            for idx in range(count):
                rendered = render_frame(p, idx/fps, assets)
                proc.stdin.write(rendered.tobytes('raw', 'RGB'))
                if idx % max(1, fps*2) == 0:
                    print(f'\rFrames {idx+1}/{count}', end='', file=sys.stderr, flush=True)
        finally:
            if proc.stdin:
                proc.stdin.close()
        err = proc.stderr.read().decode('utf-8', 'replace') if proc.stderr else ''
        rc = proc.wait()
        if rc != 0:
            raise CLIError('FFmpeg failed: '+err[-3000:])
        os.replace(temp, out)
        print(f'\rRendered {count} frames -> {out}', file=sys.stderr)
    except (OSError, BrokenPipeError) as e:
        raise CLIError(f'Export interrupted: {e}') from e
    finally:
        if proc and proc.poll() is None:
            proc.kill()
            proc.wait()
        if proc and proc.stderr:
            proc.stderr.close()
        temp.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog='ccrender', description='Cubical Compare CLI — offline project, CSV and MP4 renderer')
    parser.add_argument('--version', action='version', version=VERSION)
    subs = parser.add_subparsers(dest='command', required=True)
    for cmd in ('inspect', 'preview', 'render'):
        sub = subs.add_parser(cmd)
        sub.add_argument('input', type=Path, help='.ccv5/.json project or CSV/TSV dataset')
        if cmd != 'inspect':
            sub.add_argument('-o', '--output', type=Path, required=True)
            sub.add_argument('--width', type=int)
            sub.add_argument('--height', type=int)
            sub.add_argument('--fps', type=int)
        if cmd == 'preview':
            sub.add_argument('--time', type=float, default=1.5)
            sub.add_argument('--thumbnail', action='store_true')
        if cmd == 'render':
            sub.add_argument('--duration', type=float)
            sub.add_argument('--seconds-per-card', type=float)
            sub.add_argument('--audio', action='append', help='Replace project audio with audio file (repeatable)')
            sub.add_argument('--strict-assets', action='store_true')
    args = parser.parse_args(argv)
    try:
        p = load_project(args.input)
        if args.command != 'inspect':
            for prop in ('width', 'height', 'fps'):
                value = getattr(args, prop, None)
                if value is not None:
                    setattr(p, prop, value)
        if args.command == 'render':
            if args.duration is not None:
                p.duration = args.duration
            if args.seconds_per_card is not None:
                p.seconds_per_card = args.seconds_per_card
            if args.audio is not None:
                p.audio = [_source(a, Path.cwd()) for a in args.audio]
        check(p)
        if args.command == 'inspect':
            print(json.dumps({'cards':len(p.cards), 'duration_seconds':duration(p), 'fps':p.fps,
                              'resolution':[p.width,p.height], 'frames':math.ceil(duration(p)*p.fps),
                              'audio_tracks':len(p.audio)}, indent=2))
        elif args.command == 'preview':
            if not math.isfinite(args.time) or not 0<=args.time<=duration(p):
                raise CLIError('Preview time is outside the video timeline')
            args.output.parent.mkdir(parents=True, exist_ok=True)
            render_frame(p, args.time, Assets(), args.thumbnail).save(args.output, 'PNG')
            print(args.output)
        else:
            render_video(p, args.output, int(p.fps), args.strict_assets)
        return 0
    except (CLIError, OSError, ValueError, TypeError) as e:
        print(f'ccrender: {e}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())