"""Render matched maps from native candidate exports and the existing seed-atlas web renderer."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

INK = '#ebf1ef'
MUTED = '#a9b9b2'
BACKGROUND = '#101e1d'
PANEL = '#192b28'
COLORS = ('#ffdc6d', '#ff9794')


def font(size, bold=False):
    for path in (f'C:/Windows/Fonts/segoeui{"b" if bold else ""}.ttf',
                 f'/usr/share/fonts/truetype/dejavu/DejaVuSans{"-Bold" if bold else ""}.ttf'):
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size=size)


def labels(image, xy, text, size=20, fill=INK, bold=False, **kwargs):
    ImageDraw.Draw(image).text(xy, text, font=font(size, bold), fill=fill, **kwargs)


def terrain(background, coarse, native_cell, row, width):
    full_extent = (coarse['cj1'] - coarse['cj0']) * native_cell
    native_extent = row['extentBlocks'] / row['scale']
    half = background.width * native_extent / full_extent / 2
    center = background.width / 2
    return background.crop((center-half, center-half, center+half, center+half)).resize(
        (width, width), Image.Resampling.LANCZOS).convert('RGB')


def map_image(base, points, extent, radius):
    result = base.copy()
    draw = ImageDraw.Draw(result)
    width = base.width
    for x, z, kind in points:
        px, py = (x / extent + .5) * width, (z / extent + .5) * width
        draw.ellipse((px-radius, py-radius, px+radius, py+radius), fill=COLORS[kind],
                     outline='#18231f', width=1)
    # Constant scale bar in world blocks, independently labeled for every scale.
    bar_blocks = extent / 4
    start, end, y = 24, 24 + width / 4, width - 30
    draw.line((start, y, end, y), fill='white', width=4)
    draw.line((start, y-5, start, y+5), fill='white', width=2)
    draw.line((end, y-5, end, y+5), fill='white', width=2)
    draw.text((start, y-28), f'{bar_blocks:,.0f} blocks', font=font(17), fill='white',
              stroke_width=2, stroke_fill='#18231f')
    draw.text((width-24, 16), 'N ↑', font=font(21, True), fill='white', anchor='ra',
              stroke_width=2, stroke_fill='#18231f')
    return result


def counts(points):
    ordinary = sum(point[2] == 0 for point in points)
    return f'{len(points):,} candidates  ·  {ordinary:,} vanilla + {len(points)-ordinary:,} Terralith'


def pair(background, data, row, map_width=780):
    margin, gap = 30, 24
    total_width = 2 * map_width + 2 * margin + gap
    result = Image.new('RGB', (total_width, map_width + 280), BACKGROUND)
    framing = 'Same terrain region, enlarged with scale' if row['framing'] == 'regional' else 'Same 8,192 × 8,192-block area'
    labels(result, (margin, 20), f'SCALE {row["scale"]}  /  Before and after', 30, bold=True)
    labels(result, (margin, 66), f'{framing}  ·  {row["extentBlocks"]:,} blocks across  ·  seed {data["seed"]}', 20, MUTED)
    base = terrain(background, data['coarse'], data['nativeBlocksPerCoarseCell'], row, map_width)
    for column, mode in enumerate(('before', 'after')):
        left = margin + column * (map_width + gap)
        labels(result, (left, 108), 'BEFORE · fixed grid' if mode == 'before' else 'AFTER · spacing × world scale', 23, bold=True)
        labels(result, (left, 144), counts(row[mode]), 19, MUTED)
        result.paste(map_image(base, row[mode], row['extentBlocks'], 3.5), (left, 182))
    footer = map_width + 199
    labels(result, (margin+18, footer), 'Vanilla village grid', 18)
    labels(result, (margin+268, footer), 'Terralith rare-village grid', 18)
    ImageDraw.Draw(result).ellipse((margin, footer+6, margin+9, footer+15), fill=COLORS[0])
    ImageDraw.Draw(result).ellipse((margin+250, footer+6, margin+259, footer+15), fill=COLORS[1])
    labels(result, (margin, footer+32), 'CANDIDATES, NOT GENERATED VILLAGES · Native frequency/exclusion checks applied; biome and terrain checks not applied.', 17, MUTED)
    labels(result, (margin, footer+57), 'Coarse-model terrain for context. Ocean candidates are shown and normally fail generation. Bounds are half-open; north is −Z.', 16, MUTED)
    return result


def overview(background, data, framing):
    rows = [r for r in data['comparisons'] if r['framing'] == framing]
    card_width, map_width, card_height = 900, 420, 555
    result = Image.new('RGB', (2*card_width+60, 3*card_height+230), BACKGROUND)
    labels(result, (30, 22), 'VILLAGE PLACEMENT  /  Six scales, same seed', 34, bold=True)
    subtitle = 'Same terrain region enlarged with scale: old grids accumulate candidates; scaled grids retain regional opportunities.' if framing == 'regional' else 'Fixed 8,192 × 8,192-block area: candidate density falls as spacing grows with world scale.'
    labels(result, (30, 76), subtitle, 20, MUTED)
    for index, row in enumerate(rows):
        left, top = 30 + index % 2 * card_width, 125 + index // 2 * card_height
        draw = ImageDraw.Draw(result)
        draw.rounded_rectangle((left-8, top-6, left+card_width-20, top+card_height-20), radius=14, fill=PANEL)
        labels(result, (left+8, top+7), f'Scale {row["scale"]}   ·   {row["extentBlocks"]:,} blocks across', 23, bold=True)
        base = terrain(background, data['coarse'], data['nativeBlocksPerCoarseCell'], row, map_width)
        for column, mode in enumerate(('before', 'after')):
            x = left + 8 + column * (map_width+16)
            labels(result, (x, top+49), f'{mode.upper()}   {len(row[mode]):,} candidates', 20)
            result.paste(map_image(base, row[mode], row['extentBlocks'], 2.5), (x, top+83))
        reduction = 100 * (1 - len(row['after']) / len(row['before'])) if row['before'] else 0
        labels(result, (left+8, top+512), f'{reduction:.1f}% fewer in this window  ·  grid-cell area × {row["scale"]**2}', 18, MUTED)
    bottom = 3*card_height + 125
    labels(result, (30, bottom), 'Yellow: vanilla village candidates   /   Coral: Terralith rare-village candidates', 19)
    labels(result, (30, bottom+30), 'Candidate grids before biome and terrain rejection; includes ocean positions. These are not confirmed generated villages.', 19, MUTED)
    labels(result, (30, bottom+60), f'Native Minecraft placement + exclusion checks · seed {data["seed"]} · matching terrain and bounds within each before/after pair', 18, MUTED)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--map-renderer', type=Path, default=Path(__file__).with_name('map_background.py'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.data.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('existing_seed_atlas_renderer', args.map_renderer)
    renderer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)
    background_path = args.output / 'coarse-terrain.png'
    native_extent = (data['coarse']['cj1'] - data['coarse']['cj0']) * data['nativeBlocksPerCoarseCell']
    # Existing atlas renderer expresses hill-shading distances at historical scale 4.
    renderer.paint_continents(data['coarse'], background_path, blocks=native_extent*4)
    background = Image.open(background_path).convert('RGB')
    summary = []
    for row in data['comparisons']:
        folder = args.output / row['framing']
        folder.mkdir(exist_ok=True)
        pair(background, data, row).save(folder / f'scale-{row["scale"]}.png')
        summary.append({key: row[key] for key in ('scale', 'framing', 'extentBlocks')} | {
            'before': len(row['before']), 'after': len(row['after']),
            'beforeByFamily': counts(row['before']), 'afterByFamily': counts(row['after'])})
        if row['scale'] == 1:
            assert row['before'] == row['after'], 'Scale-1 parity must be exact'
    for framing in ('regional', 'fixed-area'):
        overview(background, data, framing).save(args.output / f'overview-{framing}.png')
    receipt = dict(seed=data['seed'], markerMeaning=data['markerMeaning'],
                   dataSha256=hashlib.sha256(args.data.read_bytes()).hexdigest(),
                   webRendererSha256=hashlib.sha256(args.map_renderer.read_bytes()).hexdigest(),
                   frequencyMultipliers=data['frequencyMultipliers'], comparisons=summary,
                   limitations='Coarse terrain backdrop; no biome/slope/structure-start generation. Candidates include oceans.')
    (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
