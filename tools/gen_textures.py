# -*- coding: utf-8 -*-
"""
深空铸造体 —— 贴图与模型生成器
================================
    python tools/gen_textures.py                      # 生成【缺失的】文件（已存在的一律不动）
    python tools/gen_textures.py buyer_threshold_mid  # ★ 只处理这一个物品
    python tools/gen_textures.py buyer_threshold      # 只处理这一家公司的那台机器
    python tools/gen_textures.py bank_card            # 只处理这张物品贴图
    python tools/gen_textures.py --list               # 只列出会涉及哪些物品，不写文件
    python tools/gen_textures.py --force              # 允许覆盖已存在的文件（慎用）

生成 4 种功能方块 × 6 家公司 × 3 个等级（共 72 种外观，含点亮版 144 张贴图）：

  assets/deepspacedeployer/textures/block/<机器>_<公司>_<等级>.png        (+ _lit)
  assets/deepspacedeployer/blockstates/<机器>.json                        18 种变体 × 点亮
  assets/deepspacedeployer/models/block/<机器>_<公司>_<等级>.json          (+ _lit)
  assets/deepspacedeployer/textures/item/*.png                            银行卡 + 6 家票据
  deepspacedeployer_logo.png                                              模组图标

## ★ 隔离规则（老板 2026-10-15 要求："每个物品单独做一个隔离"）

1. **默认不覆盖任何已存在的文件** —— 只补缺失的。
   所以你手改过的任何一张图 / 一个模型，跑这个脚本都**不会被碰**；
   想重新生成必须显式加 `--force`。
2. **可以只处理一个物品**（位置参数），其余物品一个字节都不读写。
3. **手绘定稿永不覆盖**：`art/手绘定稿/` 里出现过的文件名，
   连 `--force` 都跳过（要更新手绘图请手动覆盖）。
4. 跑完会**按物品列出**"写了什么 / 跳过了什么"，方便一眼确认波及范围。
"""
import json
import os
import random
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RES = os.path.join(PROJECT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "deepspacedeployer")
MODID = "deepspacedeployer"

BASE = (58, 63, 74)
BASE_DARK = (30, 33, 40)
BASE_LIGHT = (86, 92, 106)
SCREEN = (14, 16, 22)

MACHINES = [
    ("factory_terminal", "screen"),
    ("factory_core", "gear"),
    ("buyer", "funnel"),
    ("market", "tag"),
]

COMPANIES = [
    ("threshold", (232, 244, 255)),
    ("whisperbelt", (180, 124, 255)),
    ("brokenedge", (255, 90, 90)),
    ("skimmer", (255, 179, 71)),
    ("bedrock", (154, 140, 120)),
    ("astralink", (84, 200, 255)),
]

TIERS = [("low", 0), ("mid", 1), ("high", 2)]


def shift(color, amount):
    return tuple(max(0, min(255, c + amount)) for c in color)


def noisy(img, amount=9, seed=1):
    rnd = random.Random(seed)
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            d = rnd.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), a)
    return img


def plate(draw, company_color, tier_rank):
    """底板：等级越高越"厚重"（边框更亮、加金边）"""
    body = shift(BASE, tier_rank * 6)
    draw.rectangle([0, 0, 15, 15], fill=body + (255,))
    draw.rectangle([0, 0, 15, 15], outline=BASE_DARK + (255,), width=1)
    draw.rectangle([1, 1, 14, 1], fill=BASE_LIGHT + (255,))
    draw.rectangle([1, 14, 14, 14], fill=shift(BASE_DARK, -6) + (255,))
    # 公司色条纹（左上到右下的小斜条简化成一条横线）
    draw.rectangle([2, 2, 6, 2], fill=company_color + (255,))
    # 铆钉
    for (x, y) in [(2, 3), (13, 3), (2, 12), (13, 12)]:
        draw.point((x, y), fill=BASE_DARK)
    # 等级标记：左下角 1/2/3 个小方块
    for i in range(tier_rank + 1):
        draw.point((2 + i * 2, 13), fill=(255, 214, 102, 255))
    # 高级加一圈金边
    if tier_rank == 2:
        draw.rectangle([1, 1, 14, 14], outline=(255, 214, 102, 120), width=1)


def motif(draw, kind, company_color, lit):
    accent = company_color if lit else tuple(c // 2 for c in company_color)
    if kind == "screen":
        draw.rectangle([3, 4, 12, 9], fill=SCREEN + (255,))
        if lit:
            draw.rectangle([4, 5, 11, 8], fill=accent + (255,))
            for x in range(5, 11, 2):
                draw.line([(x, 5), (x, 8)], fill=(255, 255, 255, 200))
        else:
            draw.rectangle([4, 5, 11, 8], fill=tuple(c // 3 for c in company_color) + (255,))
        draw.rectangle([6, 11, 9, 11], fill=(accent if lit else BASE_DARK) + (255,))
    elif kind == "gear":
        draw.ellipse([4, 4, 11, 10], fill=BASE_DARK + (255,))
        draw.ellipse([5, 5, 10, 9], fill=accent + (255,))
        draw.ellipse([7, 6, 8, 7], fill=SCREEN + (255,))
        for dx in (-4, 4):
            draw.rectangle([int(7.5 + dx) - 1, 6, int(7.5 + dx) + 1, 8], fill=BASE_DARK + (255,))
    elif kind == "spark":
        draw.rectangle([6, 9, 9, 12], fill=BASE_DARK + (255,))
        draw.rectangle([7, 4, 8, 9], fill=(126, 132, 146, 255))
        if lit:
            draw.rectangle([5, 2, 10, 4], fill=accent + (255,))
            draw.point((6, 1), fill=(255, 255, 210, 255))
            draw.point((9, 1), fill=(255, 255, 210, 255))
        else:
            draw.rectangle([6, 3, 9, 4], fill=(70, 74, 84, 255))
    elif kind == "funnel":
        draw.polygon([(4, 4), (11, 4), (9, 9), (6, 9)], fill=BASE_DARK + (255,))
        draw.polygon([(5, 5), (10, 5), (8, 8), (7, 8)], fill=accent + (255,))
        draw.rectangle([6, 10, 9, 11], fill=BASE_DARK + (255,))
    elif kind == "tag":
        # 寄售市场：一块价签（带小孔）+ 底下一条"价格条"
        draw.polygon([(3, 4), (11, 4), (13, 7), (11, 10), (3, 10)], fill=BASE_DARK + (255,))
        draw.polygon([(4, 5), (10, 5), (12, 7), (10, 9), (4, 9)], fill=accent + (255,))
        draw.point((5, 6), fill=SCREEN)
        if lit:
            draw.rectangle([5, 11, 11, 11], fill=(255, 214, 102, 255))
        else:
            draw.rectangle([5, 11, 11, 11], fill=shift(BASE_DARK, -4) + (255,))


def block_texture(kind, motif_name, company_color, tier_rank, lit):
    img = Image.new("RGBA", (16, 16), BASE + (255,))
    draw = ImageDraw.Draw(img)
    plate(draw, company_color, tier_rank)
    motif(draw, motif_name, company_color, lit)
    return noisy(img, 7, seed=hash((kind, motif_name, company_color, tier_rank, lit)) & 0xFFFF)


def item_texture_scrip(accent, seed):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([1, 4, 14, 11], fill=(238, 238, 230, 255))
    draw.rectangle([1, 4, 14, 11], outline=(120, 120, 112, 255))
    draw.rectangle([2, 5, 13, 10], outline=accent + (255,))
    draw.ellipse([6, 6, 9, 9], fill=accent + (255,))
    draw.point((7, 7), fill=(255, 255, 255, 255))
    draw.line([(3, 6), (3, 9)], fill=accent + (160,))
    draw.line([(12, 6), (12, 9)], fill=accent + (160,))
    return noisy(img, 6, seed=seed)


def item_texture_card():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([1, 3, 14, 12], fill=(28, 34, 52, 255))
    draw.rectangle([1, 3, 14, 12], outline=(12, 16, 26, 255))
    draw.rectangle([1, 5, 14, 6], fill=(210, 176, 84, 255))
    draw.rectangle([3, 8, 6, 10], fill=(232, 196, 104, 255))
    draw.point((4, 9), fill=(120, 96, 40, 255))
    draw.rectangle([9, 9, 13, 10], fill=(84, 200, 255, 255))
    draw.point((2, 11), fill=(84, 200, 255, 200))
    return noisy(img, 6, seed=99)


def item_texture_voucher():
    """蓝图凭证：一卷图纸 + 蜡封"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([2, 3, 13, 12], fill=(226, 220, 200, 255))
    draw.rectangle([2, 3, 13, 12], outline=(140, 132, 112, 255))
    draw.rectangle([1, 3, 2, 12], fill=(196, 188, 168, 255))
    draw.rectangle([13, 3, 14, 12], fill=(196, 188, 168, 255))
    for y in (5, 7, 9):
        draw.line([(4, y), (11, y)], fill=(84, 150, 220, 255))
    draw.rectangle([6, 6, 9, 9], outline=(84, 150, 220, 255))
    draw.ellipse([6, 11, 9, 14], fill=(200, 60, 60, 255))
    return noisy(img, 6, seed=555)


def item_texture_license(color):
    """公司授权铭牌：金属牌 + 公司色条"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([2, 3, 13, 12], fill=(58, 63, 74, 255))
    draw.rectangle([2, 3, 13, 12], outline=(30, 33, 40, 255))
    draw.rectangle([3, 4, 12, 6], fill=color + (255,))
    draw.rectangle([4, 8, 11, 9], fill=(200, 205, 215, 255))
    draw.rectangle([4, 10, 9, 11], fill=(150, 156, 168, 255))
    draw.point((12, 11), fill=(255, 214, 102, 255))
    return noisy(img, 5, seed=777)


def item_texture_tier_kit():
    """等级升级套件：工具箱 + 三个金星"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([6, 3, 9, 6], fill=(90, 62, 38, 255))
    draw.rectangle([2, 6, 13, 13], fill=(120, 84, 52, 255))
    draw.rectangle([2, 6, 13, 13], outline=(70, 48, 30, 255))
    draw.rectangle([2, 8, 13, 9], fill=(180, 140, 80, 255))
    for i in range(3):
        draw.rectangle([4 + i * 3, 10, 5 + i * 3, 11], fill=(255, 214, 102, 255))
    return noisy(img, 5, seed=778)


def logo():
    size = 128
    img = Image.new("RGBA", (size, size), (10, 12, 20, 255))
    draw = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(90):
        x, y = rnd.randrange(size), rnd.randrange(size // 2)
        v = rnd.randint(120, 255)
        draw.point((x, y), fill=(v, v, 255, 255))
    draw.ellipse([86, 8, 122, 44], fill=(46, 60, 96, 255))
    draw.ellipse([92, 14, 116, 38], fill=(70, 92, 140, 255))
    draw.rectangle([14, 70, 114, 116], fill=(40, 46, 58, 255))
    draw.rectangle([14, 70, 114, 116], outline=(84, 200, 255, 255))
    for cx in (28, 50, 72):
        draw.rectangle([cx, 46, cx + 12, 70], fill=(52, 58, 72, 255))
        draw.rectangle([cx, 46, cx + 12, 70], outline=(84, 200, 255, 180))
        draw.rectangle([cx + 3, 38, cx + 9, 46], fill=(120, 126, 140, 255))
    for i in range(4):
        x = 20 + i * 24
        draw.rectangle([x, 84, x + 14, 106], fill=(84, 200, 255, 220))
        draw.rectangle([x + 3, 88, x + 11, 102], fill=(16, 18, 24, 255))
    draw.rectangle([14, 116, 114, 120], fill=(84, 200, 255, 255))
    return img


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------- 隔离机制

HAND_DIR = os.path.join(PROJECT, "art", "手绘定稿")


def hand_painted_names():
    """art/手绘定稿/ 里出现过的文件名 = 永不覆盖的手绘图（连 --force 都跳过）"""
    names = set()
    if os.path.isdir(HAND_DIR):
        for root, _dirs, files in os.walk(HAND_DIR):
            for f in files:
                names.add(f)
    return names


class Writer:
    """
    所有写盘都走这里 —— 这就是"隔离"的落点。

    规则：① 默认不覆盖已存在的文件；② 手绘定稿永不覆盖；③ 每个文件都记下它属于哪个物品。
    """

    def __init__(self, force=False, dry=False):
        self.force = force
        self.dry = dry
        self.protected = hand_painted_names()
        self.written = []      # (物品, 路径)
        self.skipped = []      # (物品, 路径) —— 已存在，默认不覆盖
        self.blocked = []      # (物品, 路径) —— 手绘保护挡下

    def _decide(self, item, path):
        if os.path.basename(path) in self.protected:
            self.blocked.append((item, path))
            return False
        if os.path.exists(path) and not self.force:
            self.skipped.append((item, path))
            return False
        if self.dry:
            self.written.append((item, path))     # --list：只登记，不写
            return False
        return True

    def png(self, item, path, factory):
        """factory 传可调用对象（lambda）→ 被跳过时连画都不画，省时间"""
        if not self._decide(item, path):
            return
        img = factory() if callable(factory) else factory
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        self.written.append((item, path))

    def json(self, item, path, data):
        if self._decide(item, path):
            write_json(path, data)
            self.written.append((item, path))

    def report(self, title):
        by_item = {}
        for item, path in self.written:
            by_item.setdefault(item, []).append(path)
        print()
        print("%s：写入 %d 个物品 / %d 个文件" % (title, len(by_item), len(self.written)))
        for item in sorted(by_item):
            print("   %-30s %d 个文件" % (item, len(by_item[item])))
        if self.skipped:
            print("   跳过（已存在，默认不覆盖）：%d 个文件" % len(self.skipped))
        if self.blocked:
            names = sorted({os.path.basename(p) for _i, p in self.blocked})
            print("   手绘保护（永不覆盖）：%s" % "、".join(names))
        if not self.written and not self.skipped and not self.blocked:
            print("   （没有匹配的物品 —— 检查一下你写的名字？）")


def main():
    args = sys.argv[1:]
    force = "--force" in args
    dry = "--list" in args
    only = [a for a in args if not a.startswith("--")]

    def selected(*keys):
        """这个物品要不要处理：没写名字 = 全都要；写了 = 命中任意一个即可"""
        return (not only) or any(f in keys for f in only)

    block_tex_dir = os.path.join(ASSETS, "textures", "block")
    item_tex_dir = os.path.join(ASSETS, "textures", "item")
    w = Writer(force=force, dry=dry)

    if only:
        print("只处理：%s" % "、".join(only))
    elif dry:
        print("--list：只看会涉及哪些物品，不写任何文件")
    else:
        print("生成【缺失的】文件（已存在的一律不动；要覆盖请加 --force）")

    for machine, motif_name in MACHINES:
        variants = {}
        for company_id, color in COMPANIES:
            for tier_id, tier_rank in TIERS:
                base_name = f"{machine}_{company_id}_{tier_id}"
                # 这个变体要不要生成
                do_this = selected(machine, f"{machine}_{company_id}", base_name)
                for lit in (False, True):
                    suffix = "_lit" if lit else ""
                    texture_name = base_name + suffix
                    if do_this:
                        w.png(base_name,
                              os.path.join(block_tex_dir, texture_name + ".png"),
                              lambda m=machine, n=motif_name, c=color, r=tier_rank, l=lit:
                              block_texture(m, n, c, r, l))
                        w.json(base_name,
                               os.path.join(ASSETS, "models", "block", texture_name + ".json"), {
                                   "parent": "minecraft:block/cube_all",
                                   "textures": {"all": f"{MODID}:block/{texture_name}"},
                               })
                    # ⚠️ 方块状态必须【始终】填满所有变体（它引用全部 18 种），
                    #    所以这个字典在筛选之外照样组装。
                    variants[f"company={company_id},tier={tier_id},lit=false"] = {
                        "model": f"{MODID}:block/{base_name}"}
                    variants[f"company={company_id},tier={tier_id},lit=true"] = {
                        "model": f"{MODID}:block/{base_name}_lit"}

        if selected(machine):
            w.json(machine, os.path.join(ASSETS, "blockstates", machine + ".json"),
                   {"variants": variants})
            # 物品默认显示"阈限实验集团 / 低级"那一版
            w.json(machine, os.path.join(ASSETS, "models", "item", machine + ".json"), {
                "parent": f"{MODID}:block/{machine}_threshold_low",
            })

    # ---------------- 物品贴图 ----------------
    # ⚠️ 银行卡 / 蓝图凭证 / 等级升级套件现在是**手绘定稿图**：
    #    art/手绘定稿/ 下出现过同名文件 → Writer 会自动跳过，连 --force 都不覆盖。
    def item_png(name, factory):
        if selected(name):
            w.png(name, os.path.join(item_tex_dir, name + ".png"), factory)

    def item_json(name, texture):
        if selected(name):
            w.json(name, os.path.join(ASSETS, "models", "item", name + ".json"), {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": f"{MODID}:item/{texture}"},
            })

    item_png("bank_card", item_texture_card)
    item_json("bank_card", "bank_card")

    for i, (cid, color) in enumerate(COMPANIES):
        name = f"{cid}_scrip"
        item_png(name, lambda c=color, s=1000 + i: item_texture_scrip(c, seed=s))
        item_json(name, name)

    item_png("blueprint_voucher", item_texture_voucher)
    item_json("blueprint_voucher", "blueprint_voucher")

    for cid, color in COMPANIES:
        name = f"{cid}_license"
        item_png(name, lambda c=color: item_texture_license(c))
        item_json(name, name)

    item_png("tier_upgrade_kit", item_texture_tier_kit)
    item_json("tier_upgrade_kit", "tier_upgrade_kit")

    if selected("logo", "deepspacedeployer_logo"):
        w.png("logo", os.path.join(RES, "deepspacedeployer_logo.png"), logo)

    w.report("贴图与模型")
    print()
    print("全量规模：%d 机器 × %d 公司 × %d 等级 × 2 = %d 张方块贴图"
          % (len(MACHINES), len(COMPANIES), len(TIERS),
             len(MACHINES) * len(COMPANIES) * len(TIERS) * 2))
    if not force and not dry:
        print("提示：想重新生成某一张，直接点名即可，例如："
              "python tools\\gen_textures.py buyer_threshold_mid --force")


if __name__ == "__main__":
    main()

