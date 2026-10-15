# -*- coding: utf-8 -*-
"""
数值核对：源 .bbmodel 与转换后的原版模型，**几何是否忠实**。

    python tools/verify_conversion.py

做法：把两边的每个面都取 7x7 个采样点（3D），
然后逐点检查"这个点在对面模型里有没有被覆盖"。
覆盖判定用元素的轴对齐包围盒（原版元素必然是轴对齐的），
所以**拆面、90° 烘焙、整体上移**这些处理都能正确纳入。
不依赖任何渲染代码，结论可信。
"""
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

SOURCE_BB = r"C:\Users\30947\Desktop\星舰计划\通用终端.bbmodel"
VANILLA = os.path.join(ASSETS, "models", "block", "universal_terminal.json")
TOL = 0.06           # 采样点容差（单位：1/16 格）
SAMPLES = 7


def rot_mat(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == 0:
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == 1:
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def face_quad(low, high, face):
    x1, y1, z1 = low
    x2, y2, z2 = high
    if face == "north":
        return [(x1, y1, z1), (x2, y1, z1), (x2, y2, z1), (x1, y2, z1)]
    if face == "south":
        return [(x2, y1, z2), (x1, y1, z2), (x1, y2, z2), (x2, y2, z2)]
    if face == "east":
        return [(x2, y1, z1), (x2, y1, z2), (x2, y2, z2), (x2, y2, z1)]
    if face == "west":
        return [(x1, y1, z2), (x1, y1, z1), (x1, y2, z1), (x1, y2, z2)]
    if face == "up":
        return [(x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)]
    return [(x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)]


def bilinear(quad, u, v):
    """四边形上按 (u,v) 双线性取点"""
    top = [quad[0][i] + (quad[1][i] - quad[0][i]) * u for i in range(3)]
    bot = [quad[3][i] + (quad[2][i] - quad[3][i]) * u for i in range(3)]
    return [top[i] + (bot[i] - top[i]) * v for i in range(3)]


def sample_points(quad):
    pts = []
    for i in range(SAMPLES):
        for j in range(SAMPLES):
            pts.append(bilinear(quad, i / (SAMPLES - 1), j / (SAMPLES - 1)))
    return pts


def inside(pt, box, tol=TOL):
    lo, hi = box
    return all(lo[i] - tol <= pt[i] <= hi[i] + tol for i in range(3))


def load_source_faces():
    with open(SOURCE_BB, encoding="utf-8") as f:
        bb = json.load(f)
    faces = []
    for e in bb["elements"]:
        frm = [float(v) for v in e["from"]]
        to = [float(v) for v in e["to"]]
        low = [min(frm[i], to[i]) for i in range(3)]
        high = [max(frm[i], to[i]) for i in range(3)]
        rot = [float(v) for v in (e.get("rotation") or [0, 0, 0])]
        axes = [i for i, v in enumerate(rot) if abs(v) > 1e-6]
        origin = [float(v) for v in (e.get("origin") or e["from"])]
        m = None
        for ax in axes:
            mm = rot_mat(ax, rot[ax])
            m = mm if m is None else mat_mul(mm, m)
        for face in ("north", "south", "east", "west", "up", "down"):
            quad = face_quad(low, high, face)
            if m is not None:
                quad = [apply(m, [p[i] - origin[i] for i in range(3)]) for p in quad]
                quad = [[p[i] + origin[i] for i in range(3)] for p in quad]
            faces.append(quad)
    return faces


def load_vanilla_boxes():
    with open(VANILLA, encoding="utf-8") as f:
        m = json.load(f)
    return [(list(e["from"]), list(e["to"])) for e in m["elements"]]


def main():
    src_faces = load_source_faces()
    van_boxes = load_vanilla_boxes()

    # 整体上移量：两边最低点之差
    src_low = min(min(p[1] for p in q) for q in src_faces)
    van_low = min(b[0][1] for b in van_boxes)
    dy = van_low - src_low
    print("源最低点 y=%.3f，转换后最低点 y=%.3f → 上移量 %.3f" % (src_low, van_low, dy))
    print("源面 %d 个，转换后 element %d 个" % (len(src_faces), len(van_boxes)))
    print()

    # 1) 源 → 转换后：源的每个采样点都要被覆盖
    total = 0
    missed = 0
    for quad in src_faces:
        for pt in sample_points(quad):
            moved = [pt[0], pt[1] + dy, pt[2]]
            total += 1
            if not any(inside(moved, b) for b in van_boxes):
                missed += 1
    print("源 → 转换后：%d 个采样点，未覆盖 %d 个（%.2f%%）"
          % (total, missed, 100.0 * missed / max(1, total)))

    # 2) 转换后 → 源：转换后每个元素的角点都要落在源的某个面上
    total2 = 0
    missed2 = 0
    src_boxes = []
    for quad in src_faces:
        lo = [min(p[i] for p in quad) for i in range(3)]
        hi = [max(p[i] for p in quad) for i in range(3)]
        src_boxes.append((lo, hi))
    for lo, hi in van_boxes:
        pts = [[lo[0] if i & 1 == 0 else hi[0],
                lo[1] if i & 2 == 0 else hi[1],
                lo[2] if i & 4 == 0 else hi[2]] for i in range(8)]
        pts.append([(lo[i] + hi[i]) / 2 for i in range(3)])
        for pt in pts:
            back = [pt[0], pt[1] - dy, pt[2]]
            total2 += 1
            if not any(inside(back, b) for b in src_boxes):
                missed2 += 1
    print("转换后 → 源：%d 个角点，未命中 %d 个（%.2f%%）"
          % (total2, missed2, 100.0 * missed2 / max(1, total2)))
    print()
    if missed == 0 and missed2 == 0:
        print("✅ 几何一致：两边互相覆盖")
    else:
        print("⚠️ 有不一致，见上面的百分比")


if __name__ == "__main__":
    main()
