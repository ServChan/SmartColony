import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

WOODS = {
    "oak": (184, 148, 92), "spruce": (112, 82, 50), "birch": (214, 200, 140), "jungle": (170, 120, 84),
    "acacia": (186, 100, 54), "dark_oak": (70, 48, 26), "mangrove": (120, 50, 46), "cherry": (226, 178, 172),
    "pale_oak": (226, 220, 210), "poplar": (196, 170, 120),
}
LOG_DARKEN = 0.62

FIXED = [
    ("cobblestone_wall", (120, 120, 120)), ("mossy_cobblestone", (100, 120, 90)), ("cobblestone", (128, 128, 128)),
    ("stone_brick", (140, 140, 140)), ("polished", (160, 160, 160)), ("stone", (125, 125, 125)), ("andesite", (136, 136, 136)),
    ("deepslate", (80, 80, 86)), ("brick", (150, 80, 66)), ("glass", (190, 220, 235)), ("white_wool", (235, 235, 235)),
    ("white_carpet", (235, 235, 235)), ("red_wool", (170, 40, 40)), ("wool", (220, 220, 220)), ("carpet", (220, 220, 220)),
    ("lantern", (250, 200, 90)), ("torch", (255, 210, 80)), ("hay", (210, 180, 60)), ("barrel", (130, 96, 56)),
    ("chest", (160, 116, 50)), ("crafting_table", (150, 100, 60)), ("furnace", (100, 100, 100)), ("smoker", (90, 80, 70)),
    ("composter", (120, 90, 50)), ("dirt_path", (150, 120, 70)), ("farmland", (100, 70, 40)), ("dirt", (130, 92, 60)),
    ("grass", (90, 150, 60)), ("gravel", (130, 125, 120)), ("sand", (220, 210, 160)), ("water", (60, 100, 210)),
    ("leaves", (60, 120, 50)), ("flower", (220, 80, 120)), ("bell", (230, 190, 60)), ("anvil", (60, 60, 64)),
    ("cauldron", (70, 70, 74)), ("brewing", (180, 160, 90)), ("ladder", (160, 120, 70)), ("chain", (70, 70, 80)),
    ("iron", (200, 200, 200)), ("bed", (180, 40, 40)), ("target", (230, 220, 210)), ("lectern", (170, 120, 70)),
    ("bookshelf", (140, 100, 60)), ("sign", (180, 140, 90)), ("bricks", (150, 80, 66)), ("terracotta", (160, 90, 60)),
]

THIN = ("torch", "lantern", "carpet", "pressure_plate", "button", "sign", "ladder", "chain", "flower", "rail")
PARTIAL = ("slab", "stairs", "fence", "wall", "trapdoor", "door", "pane", "bars")


def color_for(state, wood):
    block = state.split("[")[0].replace("minecraft:", "")
    block = block.replace("$wood", wood)
    for name, rgb in WOODS.items():
        if block.startswith(name + "_"):
            base = rgb
            if block.endswith("_log") or block.endswith("_wood"):
                return tuple(int(c * LOG_DARKEN) for c in base)
            return base
    for key, rgb in FIXED:
        if key in block:
            return rgb
    h = abs(hash(block))
    return (80 + h % 120, 80 + (h // 7) % 120, 80 + (h // 49) % 120)


def shade(rgb, factor):
    return tuple(max(0, min(255, int(c * factor))) for c in rgb)


def load(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    palette = data["palette"]
    layers = data["layers"]
    return data, palette, layers


def voxels(palette, layers, wood):
    cells = []
    for y, layer in enumerate(layers):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                if ch in (" ", "."):
                    continue
                state = palette.get(ch)
                if state is None:
                    continue
                block = state.split("[")[0]
                kind = "thin" if any(t in block for t in THIN) else "partial" if any(p in block for p in PARTIAL) else "full"
                height = 1.0
                if kind == "thin":
                    height = 0.25
                elif "slab" in block and "type=top" not in state:
                    height = 0.5
                cells.append((x, y, z, color_for(state, wood), kind, height))
    return cells


def render(path, out, wood="oak", scale=10, view=0):
    data, palette, layers = load(path)
    cells = voxels(palette, layers, wood)
    sx = max(len(r) for layer in layers for r in layer) if layers else 1
    sz = max(len(layer) for layer in layers) if layers else 1
    sy = len(layers)

    def rot(x, z):
        if view == 0:
            return x, z
        if view == 1:
            return sz - 1 - z, x
        if view == 2:
            return sx - 1 - x, sz - 1 - z
        return z, sx - 1 - x

    w = (sx + sz) * scale + 4 * scale
    h = (sx + sz) * scale // 2 + sy * scale + 6 * scale
    img = Image.new("RGB", (w, h), (236, 240, 244))
    draw = ImageDraw.Draw(img)
    ox = sz * scale + 2 * scale
    oy = sy * scale + 2 * scale
    placed = []
    for x, y, z, rgb, kind, height in cells:
        rx, rz = rot(x, z)
        placed.append((rx + rz, y, rx, rz, rgb, kind, height))
    placed.sort(key=lambda c: (c[1], c[0]))
    for _, y, x, z, rgb, kind, height in placed:
        px = ox + (x - z) * scale
        py = oy + (x + z) * scale // 2 - y * scale
        top = py - int(scale * height) + scale
        inset = scale // 3 if kind == "thin" else scale // 6 if kind == "partial" else 0
        t = [(px + inset, top - scale // 2 + inset // 2), (px + scale - inset, top), (px + inset, top + scale // 2 - inset // 2), (px - scale + inset, top)]
        left = [(px - scale + inset, top), (px + inset, top + scale // 2 - inset // 2), (px + inset, py + scale + scale // 2), (px - scale + inset, py + scale)]
        right = [(px + scale - inset, top), (px + inset, top + scale // 2 - inset // 2), (px + inset, py + scale + scale // 2), (px + scale - inset, py + scale)]
        draw.polygon(left, fill=shade(rgb, 0.78), outline=shade(rgb, 0.55))
        draw.polygon(right, fill=shade(rgb, 0.62), outline=shade(rgb, 0.45))
        draw.polygon(t, fill=shade(rgb, 1.08), outline=shade(rgb, 0.7))
    draw.text((6, 6), "%s  %dx%dx%d  view %d" % (Path(path).stem, sx, sz, sy, view), fill=(30, 30, 30))
    img.save(out)
    return out


def sheet(paths, out, wood="oak"):
    tiles = []
    for p in paths:
        for view in (0, 2):
            tmp = Path(out).with_suffix(".%s.%d.png" % (Path(p).stem, view))
            render(p, tmp, wood, 9, view)
            tiles.append(Image.open(tmp))
            tmp.unlink()
    cols = 2
    rows = (len(tiles) + cols - 1) // cols
    cw = max(t.width for t in tiles)
    ch = max(t.height for t in tiles)
    img = Image.new("RGB", (cw * cols, ch * rows), (255, 255, 255))
    for i, t in enumerate(tiles):
        img.paste(t, ((i % cols) * cw, (i // cols) * ch))
    img.save(out)
    return out


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print("usage: render_blueprint.py <blueprint.json>... --out file.png [--wood spruce] [--view 0..3]")
        sys.exit(1)
    wood = "oak"
    out = "render.png"
    view = None
    files = []
    i = 0
    while i < len(args):
        if args[i] == "--wood":
            wood = args[i + 1]
            i += 2
        elif args[i] == "--out":
            out = args[i + 1]
            i += 2
        elif args[i] == "--view":
            view = int(args[i + 1])
            i += 2
        else:
            files.append(args[i])
            i += 1
    if len(files) == 1 and view is not None:
        print(render(files[0], out, wood, 12, view))
    else:
        print(sheet(files, out, wood))
