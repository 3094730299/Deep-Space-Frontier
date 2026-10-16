#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把老板给的图标原图处理成模组能用的贴图（只用 Python 标准库，不装任何东西）。

背景：老板给的 PNG 是 631×629、**没有 alpha 通道**（color type 2），
      背景是"画进图里的浅蓝格子"（模拟透明背景的棋盘），必须抠掉，
      否则放进界面里会是一块浅蓝色方块。

做的事：
  1. 读 PNG（自己解 zlib + 逐行反滤波，不依赖 Pillow）；
  2. 从**四边**做 flood fill 把背景变透明（只删与边界连通的部分，
     物体内部同色像素不会被误删）；
  3. 裁到不透明内容的包围盒（留一点边距），再居中放进正方形画布
     （这样缩小时不会被拉伸变形）；
  4. 用【最近邻】缩放出需要的尺寸（像素画必须最近邻，不然会糊）；
  5. 写回带 alpha 的 PNG。

用法：
    python tools/process_theme_icon.py <输入.png> [--report]
        --report  只测量不写文件（用来挑阈值 / 看包围盒）
"""
import io
import os
import struct
import sys
import zlib

# ---- 背景判定 ----
# 这张图的背景是"画进图里的浅蓝棋盘格"，实测四种颜色：
#     (117,148,240) (124,157,251) (140,169,255) (158,184,255) (181,204,255)
# 规律非常干净：**蓝通道明显高于红通道**（B-R = 123 / 127 / 115 / 97 / 74）。
# 物体是灰 / 白 / 橙：B-R ≈ 0（灰白）、负数（橙），全部远远小于 60。
#
# 所以只用一条判据就够，别再加"亮度范围"之类的条件 ——
# 加了反而会把背景漏掉（第一版就是那样，396899 个像素只认出 3322 个）。
BG_MIN_BLUE_SHIFT = 60      # B - R 要大于这个值才算背景
BG_MIN_BLUE = 180           # 蓝通道本身也要够蓝（防误伤深蓝物体）


def is_background(r, g, b):
    return (b - r) > BG_MIN_BLUE_SHIFT and b >= BG_MIN_BLUE


# ---------------------------------------------------------------- PNG 读写

def read_png(path):
    """返回 (width, height, rgba: bytearray)。只支持 8 位非隔行 PNG。"""
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("不是 PNG 文件")
    pos = 8
    width = height = 0
    color_type = 0
    bit_depth = 8
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
            if chunk[12] != 0:
                raise ValueError("不支持隔行 (interlaced) PNG")
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
    if bit_depth != 8:
        raise ValueError("只支持 8 位深 PNG（deeper：%d）" % bit_depth)

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    raw = zlib.decompress(idat)
    stride = width * channels
    out = bytearray(stride * height)
    prev = bytearray(stride)
    src = 0
    for y in range(height):
        ftype = raw[src]
        src += 1
        line = bytearray(raw[src:src + stride])
        src += stride
        if ftype == 1:                      # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:                    # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:                    # Average
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:                    # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        elif ftype != 0:
            raise ValueError("未知的滤波类型 %d" % ftype)
        out[y * stride:(y + 1) * stride] = line
        prev = line

    # 统一成 RGBA
    rgba = bytearray(width * height * 4)
    for i in range(width * height):
        s = i * channels
        d = i * 4
        if color_type == 2:                 # RGB
            rgba[d] = out[s]
            rgba[d + 1] = out[s + 1]
            rgba[d + 2] = out[s + 2]
            rgba[d + 3] = 255
        elif color_type == 6:               # RGBA
            rgba[d:d + 4] = out[s:s + 4]
        elif color_type == 0:               # 灰度
            v = out[s]
            rgba[d] = rgba[d + 1] = rgba[d + 2] = v
            rgba[d + 3] = 255
        elif color_type == 4:               # 灰度 + alpha
            v = out[s]
            rgba[d] = rgba[d + 1] = rgba[d + 2] = v
            rgba[d + 3] = out[s + 1]
        else:
            raise ValueError("不支持的颜色类型 %d（调色板 PNG）" % color_type)
    return width, height, rgba


def write_png(path, width, height, rgba):
    """写 8 位 RGBA PNG（每行用 filter 0，简单但文件略大，图标尺寸下无所谓）。"""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]
    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    body = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(body)


# ---------------------------------------------------------------- 抠背景

def is_background(r, g, b):
    return (b - r) > BG_MIN_BLUE_SHIFT and b >= BG_MIN_BLUE


# 旧的复杂版判据（保留作对照，已不用）：
#   if not (BG_R_MIN <= r <= BG_R_MAX and BG_G_MIN <= g <= BG_G_MAX and b >= BG_B_MIN): ...
def _is_background_legacy(r, g, b):
    return False


def strip_background(width, height, rgba, report=False):
    """
    两步走，避免"物体顶到画布边缘"时泛洪被挡住：

      ① 先按颜色把**所有**像背景的像素标出来（candidate）；
      ② 再从四边泛洪，只保留与边界连通的那些（candidate 内部不连通的小块
         ——比如物体身上的浅蓝高光——不会被误删）。

    为什么不能只做 ②：这张图的物体在上下左右都顶到边缘，
    直接泛洪第一步就撞上物体，只抠掉 2816 个像素（实测）。
    """
    candidate = bytearray(width * height)
    for i in range(width * height):
        d = i * 4
        if is_background(rgba[d], rgba[d + 1], rgba[d + 2]):
            candidate[i] = 1

    transparent = bytearray(width * height)
    stack = []
    for x in range(width):
        stack.append((x, 0))
        stack.append((x, height - 1))
    for y in range(height):
        stack.append((0, y))
        stack.append((width - 1, y))

    removed = 0
    while stack:
        x, y = stack.pop()
        if x < 0 or y < 0 or x >= width or y >= height:
            continue
        idx = y * width + x
        if transparent[idx] or not candidate[idx]:
            continue
        transparent[idx] = 1
        removed += 1
        stack.append((x + 1, y))
        stack.append((x - 1, y))
        stack.append((x, y + 1))
        stack.append((x, y - 1))

    # 统计包围盒（不透明部分）
    minx, miny, maxx, maxy = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if not transparent[y * width + x]:
                if x < minx:
                    minx = x
                if x > maxx:
                    maxx = x
                if y < miny:
                    miny = y
                if y > maxy:
                    maxy = y
    return transparent, removed, (minx, miny, maxx, maxy)


def apply_alpha(width, height, rgba, transparent):
    """把背景像素的 alpha 设成 0，并给边缘做一圈半透明羽化（避免锯齿白边）"""
    for i in range(width * height):
        if transparent[i]:
            rgba[i * 4 + 3] = 0
    # 羽化：紧挨着透明像素的不透明像素，alpha 降到 ~70%（去掉抠图留下的硬边）
    for y in range(1, height - 1):
        for x in range(1, width - 1):
            i = y * width + x
            if transparent[i]:
                continue
            near = (transparent[i - 1] or transparent[i + 1]
                    or transparent[i - width] or transparent[i + width])
            if near:
                rgba[i * 4 + 3] = 180


# ---------------------------------------------------------------- 缩放 / 排版

def crop(width, height, rgba, box, pad=8):
    minx, miny, maxx, maxy = box
    minx = max(0, minx - pad)
    miny = max(0, miny - pad)
    maxx = min(width - 1, maxx + pad)
    maxy = min(height - 1, maxy + pad)
    w = maxx - minx + 1
    h = maxy - miny + 1
    out = bytearray(w * h * 4)
    for y in range(h):
        src = ((miny + y) * width + minx) * 4
        out[y * w * 4:(y + 1) * w * 4] = rgba[src:src + w * 4]
    return w, h, out


def center_square(width, height, rgba, fill_ratio=0.96):
    """把图居中放进正方形画布（边长取长边 / fill_ratio），多余处透明"""
    side = int(round(max(width, height) / fill_ratio))
    out = bytearray(side * side * 4)
    ox = (side - width) // 2
    oy = (side - height) // 2
    for y in range(height):
        src = y * width * 4
        dst = ((oy + y) * side + ox) * 4
        out[dst:dst + width * 4] = rgba[src:src + width * 4]
    return side, side, out


def resize_nearest(width, height, rgba, new_w, new_h):
    """最近邻缩放（像素画必须用这个，不然会糊）"""
    out = bytearray(new_w * new_h * 4)
    for y in range(new_h):
        sy = min(height - 1, int((y + 0.5) * height / new_h))
        for x in range(new_w):
            sx = min(width - 1, int((x + 0.5) * width / new_w))
            s = (sy * width + sx) * 4
            d = (y * new_w + x) * 4
            out[d:d + 4] = rgba[s:s + 4]
    return new_w, new_h, out


def resize_smooth(width, height, rgba, new_w, new_h):
    """面积平均缩放（给非像素画的图用；像素画别用，会糊）"""
    out = bytearray(new_w * new_h * 4)
    for y in range(new_h):
        y0 = int(y * height / new_h)
        y1 = max(y0 + 1, int((y + 1) * height / new_h))
        for x in range(new_w):
            x0 = int(x * width / new_w)
            x1 = max(x0 + 1, int((x + 1) * width / new_w))
            r = g = b = a = 0
            n = 0
            for sy in range(y0, min(y1, height)):
                for sx in range(x0, min(x1, width)):
                    s = (sy * width + sx) * 4
                    r += rgba[s]
                    g += rgba[s + 1]
                    b += rgba[s + 2]
                    a += rgba[s + 3]
                    n += 1
            d = (y * new_w + x) * 4
            out[d] = r // n
            out[d + 1] = g // n
            out[d + 2] = b // n
            out[d + 3] = a // n
    return new_w, new_h, out


# ---------------------------------------------------------------- 主流程

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    report = "--report" in sys.argv
    out_dir = None
    if "--out" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--out") + 1]
        args = [a for a in args if a != out_dir]
    if not args:
        print(__doc__)
        return 1
    src = args[0]
    width, height, rgba = read_png(src)
    print("读入 %s：%dx%d" % (os.path.basename(src), width, height))

    transparent, removed, box = strip_background(width, height, rgba, report)
    if box[2] < 0:
        print("× 整张图都被判定成背景了 —— 阈值不对，什么都没剩下")
        return 1
    print("背景已抠：%d 个像素；内容包围盒 %s" % (removed, box))
    if report:
        w = box[2] - box[0] + 1
        h = box[3] - box[1] + 1
        print("内容尺寸：%dx%d（占原图 %.0f%% x %.0f%%）"
              % (w, h, 100.0 * w / width, 100.0 * h / height))
        return 0

    if not out_dir:
        print("× 请用 --out <目录> 指定输出目录")
        return 1
    os.makedirs(out_dir, exist_ok=True)

    apply_alpha(width, height, rgba, transparent)
    cw, ch, cropped = crop(width, height, rgba, box)
    print("裁到内容：%dx%d" % (cw, ch))

    # 全尺寸透明版：存档 + 发布页/文档里用（保留原始像素）
    write_png(os.path.join(out_dir, "theme_icon_transparent.png"), cw, ch, cropped)
    print("✔ theme_icon_transparent.png（%dx%d）" % (cw, ch))

    # 正方形底板（长边决定大小），再做两种输出：
    #   · 128×128 → 模组图标（logoFile，模组列表/发布页用；缩小不可避免，就这样）
    #   · 48×48   → 界面按钮贴图（按钮上显示 24px，缩小交给游戏，交给它更清晰）
    sq_w, sq_h, squared = center_square(cw, ch, cropped)
    for size, name in ((128, "deepspacedeployer_logo.png"),
                       (48, "theme_button.png")):
        rw, rh, small = resize_nearest(sq_w, sq_h, squared, size, size)
        write_png(os.path.join(out_dir, name), rw, rh, small)
        print("✔ %s（%dx%d，最近邻）" % (name, rw, rh))
    return 0


if __name__ == "__main__":
    sys.exit(main())
