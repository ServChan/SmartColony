import json
import os
from collections import deque

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'smartcolony', 'blueprints')
PASS = ('torch', 'carpet', 'pressure_plate', 'sign', 'button', 'flower', 'poppy', 'dandelion', 'short_grass', 'rail', 'lever',
        'vine', 'ladder', 'tripwire', 'snow')
FUNCTIONAL = ('_bed', 'chest', 'barrel', 'crafting_table', 'furnace', 'smoker', 'lectern', 'brewing_stand', 'stonecutter', 'anvil',
              'smithing_table', 'fletching_table', 'composter', 'cauldron', 'beehive', 'cartography', 'loom', 'grindstone', 'bell',
              'enchanting_table', 'fence')


def path_of(state):
    return state.split('[')[0].split(':')[-1]


def props(state):
    if '[' not in state:
        return {}
    return dict(p.split('=') for p in state[state.index('[') + 1:-1].split(','))


def passable(state):
    if state is None:
        return True
    p = path_of(state)
    pr = props(state)
    if p.endswith('_door'):
        return True
    if p.endswith('_trapdoor'):
        return pr.get('open') == 'true'
    if p.endswith('_fence_gate'):
        return False
    return any(t in p for t in PASS)


def floor(state):
    if state is None:
        return False
    p = path_of(state)
    if p.endswith('_door') or p.endswith('_trapdoor') and props(state).get('open') == 'true':
        return False
    if p.endswith('_fence') or p.endswith('_wall') or p.endswith('_fence_gate'):
        return False
    if passable(state):
        return False
    return True


def analyse(path):
    d = json.load(open(path, encoding='utf-8'))
    pal = d['palette']
    L = d['layers']
    sy, sz, sx = len(L), len(L[0]), len(L[0][0])

    def at(x, y, z):
        if 0 <= x < sx and 0 <= z < sz and 0 <= y < sy:
            c = L[y][z][x]
            if c in '. ':
                return None
            return pal[c]
        return None

    def inside(x, z):
        return 0 <= x < sx and 0 <= z < sz

    def ground(x, y, z):
        return y == 0 and inside(x, z) and L[0][z][x] == ' '

    def stand(x, y, z):
        if y < 1:
            return False
        below = at(x, y - 1, z)
        return (floor(below) or ground(x, y - 1, z)) and passable(at(x, y, z)) and passable(at(x, y + 1, z))

    dx, dz = d['door']
    start = None
    for y in range(1, sy):
        if stand(dx, y, dz - 1) or not inside(dx, dz - 1):
            start = (dx, 1, dz - 1)
            break
    if start is None:
        start = (dx, 1, dz - 1)
    seen = {start}
    q = deque([start])
    while q:
        x, y, z = q.popleft()
        for ax, az in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + ax, z + az
            for ny in (y, y + 1, y - 1):
                if (nx, ny, nz) in seen:
                    continue
                if not inside(nx, nz):
                    if ny == 1 and passable(at(x, y + 1, z)):
                        pass
                    continue
                if ny == y + 1 and not passable(at(x, y + 2, z)):
                    continue
                if ny == y - 1 and not passable(at(nx, ny + 2, nz)):
                    continue
                if stand(nx, ny, nz):
                    seen.add((nx, ny, nz))
                    q.append((nx, ny, nz))
                    break
    missing = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                s = at(x, y, z)
                if not s:
                    continue
                p = path_of(s)
                if not any(p.endswith(f) or f in p for f in FUNCTIONAL) or p.endswith('_fence'):
                    continue
                if p.endswith('_bed') and props(s).get('part') == 'head':
                    continue
                ok = False
                for ax, az in ((1, 0), (-1, 0), (0, 1), (0, -1), (0, 0)):
                    for oy in (0, -1, 1, -2):
                        if (x + ax, y + oy, z + az) in seen:
                            ok = True
                if not ok:
                    missing.append((p, x, y, z))
    return len(seen), missing


for f in sorted(os.listdir(DIR)):
    n, miss = analyse(os.path.join(DIR, f))
    if miss:
        print(f, 'reach', n, 'UNREACHABLE', miss[:8])
