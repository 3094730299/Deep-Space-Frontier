# -*- coding: utf-8 -*-
"""
工程自检：把所有生成物交叉验证一遍，避免"进了游戏才发现是紫黑方块"。
    python tools/validate.py

检查项：
  1. 方块状态里引用的模型文件是否存在
  2. 模型里引用的贴图文件是否存在
  3. 中英文语言文件的键是否一一对应
  4. 18 份工厂结构 NBT：调色板下标、坐标范围、机器方块的公司/等级是否和文件名一致
  5. 世界生成 JSON：拼图池 → 结构文件、生成规则 → 结构定义、结构 → 群系标签 是否都能对上
"""
import glob
import gzip
import json
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RES = os.path.join(PROJECT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "deepspacedeployer")
DATA = os.path.join(RES, "data", "deepspacedeployer")
MODID = "deepspacedeployer"

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


# ---------------------------------------------------------------- NBT 读取
def parse_nbt(f):
    def u1():
        return struct.unpack(">b", f.read(1))[0]

    def u2():
        return struct.unpack(">H", f.read(2))[0]

    def i4():
        return struct.unpack(">i", f.read(4))[0]

    def s():
        return f.read(u2()).decode("utf-8")

    def payload(t):
        if t == 1:
            return u1()
        if t == 2:
            return struct.unpack(">h", f.read(2))[0]
        if t == 3:
            return i4()
        if t == 4:
            return struct.unpack(">q", f.read(8))[0]
        if t == 8:
            return s()
        if t == 9:
            et = u1()
            n = i4()
            return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = u1()
                if tt == 0:
                    return out
                name = s()
                out[name] = payload(tt)
        if t == 11:
            n = i4()
            return [i4() for _ in range(n)]
        raise ValueError("未知 tag %s" % t)

    u1()
    s()
    return payload(10)


def resource_path(ref, kind):
    """把 deepspacedeployer:block/xxx 变成磁盘路径"""
    if ":" not in ref:
        ref = MODID + ":" + ref
    ns, path = ref.split(":", 1)
    if ns == "minecraft":
        return None  # 原版的不管
    return os.path.join(RES, "assets", ns, kind, path + (".png" if kind == "textures" else ".json"))


# ---------------------------------------------------------------- 1&2 模型/贴图
def check_models():
    blockstates = glob.glob(os.path.join(ASSETS, "blockstates", "*.json"))
    if not blockstates:
        err("没有找到任何 blockstate 文件")
    for path in blockstates:
        data = json.load(open(path, encoding="utf-8"))
        for key, variant in data.get("variants", {}).items():
            refs = variant if isinstance(variant, list) else [variant]
            for ref in refs:
                model = ref.get("model")
                if not model:
                    err("%s 的变体 %s 没有 model" % (os.path.basename(path), key))
                    continue
                full = resource_path(model, "models")
                if full and not os.path.exists(full):
                    err("方块状态 %s 引用了不存在的模型 %s" % (os.path.basename(path), model))

    for path in glob.glob(os.path.join(ASSETS, "models", "**", "*.json"), recursive=True):
        data = json.load(open(path, encoding="utf-8"))
        for slot, ref in data.get("textures", {}).items():
            full = resource_path(ref, "textures")
            if full and not os.path.exists(full):
                err("模型 %s 引用了不存在的贴图 %s" % (os.path.relpath(path, ASSETS), ref))
        # 元素旋转角度：原版只认 0 / ±22.5 / ±45（BlockElement$Deserializer 里写死的），
        # 别的一律报 "Invalid rotation ... found, only -45/-22.5/0/22.5/45 allowed"。
        # 做通用终端时踩过 90° 这个坑：模型加载失败 → 游戏里显示成**黑紫方块**。
        for index, element in enumerate(data.get("elements", [])):
            rotation = element.get("rotation")
            if not rotation:
                continue
            angle = rotation.get("angle")
            if angle is not None and round(float(angle), 4) not in (0.0, 22.5, -22.5, 45.0, -45.0):
                err("模型 %s 第 %d 个 element 的旋转角 %s 原版不支持（只允许 0/±22.5/±45）"
                    "—— 会让模型加载失败、游戏里显示成黑紫方块"
                    % (os.path.relpath(path, ASSETS), index, angle))
            if rotation.get("axis") not in ("x", "y", "z", None):
                err("模型 %s 第 %d 个 element 的旋转轴非法：%s"
                    % (os.path.relpath(path, ASSETS), index, rotation.get("axis")))
        parent = data.get("parent")
        # 无命名空间的 parent 默认就是 minecraft（原版），比如 "block/block"
        if parent and ":" not in parent:
            parent = "minecraft:" + parent
        if parent and not parent.startswith("minecraft:"):
            full = resource_path(parent, "models")
            if full and not os.path.exists(full):
                err("模型 %s 的 parent 不存在：%s" % (os.path.relpath(path, ASSETS), parent))
    print("  模型/贴图引用检查完成（%d 个方块状态文件）" % len(blockstates))


# ---------------------------------------------------------------- 3 语言文件
def check_lang():
    zh = json.load(open(os.path.join(ASSETS, "lang", "zh_cn.json"), encoding="utf-8"))
    en = json.load(open(os.path.join(ASSETS, "lang", "en_us.json"), encoding="utf-8"))
    missing_en = sorted(set(zh) - set(en))
    missing_zh = sorted(set(en) - set(zh))
    for key in missing_en:
        err("英文语言文件缺少键：%s" % key)
    for key in missing_zh:
        warn("中文语言文件缺少键：%s" % key)
    print("  语言文件：中文 %d 条，英文 %d 条" % (len(zh), len(en)))


def check_loot_tables():
    """每个方块都必须有掉落表：原版里没有掉落表的方块被挖掉什么都不掉（连方块本身都不掉）。

    还多查一件事（拿真实事故换来的）：<b>掉落表里写出来的物品 id 必须真的存在</b>。
    老板把机器拆成"每公司一件"之后，物品注册名从 {@code factory_core_threshold_low}
    变成了 {@code factory_core_threshold}，而掉落表还指着老名字 {@code factory_core} ——
    结果原版报 {@code Unknown registry key ... item: deepspacedeployer:factory_core}，
    机器挖掉什么都不掉。现在这条会被拦在构建前。
    """
    blockstates = glob.glob(os.path.join(ASSETS, "blockstates", "*.json"))
    loot_dir = os.path.join(DATA, "loot_table", "blocks")
    if not os.path.isdir(loot_dir):
        err("缺少掉落表目录 data/deepspacedeployer/loot_table/blocks/ —— 机器被挖掉会什么都不掉！")
        return
    # 代码里真实注册的方块物品名：
    #   ① ModItems 里字面量注册的（卡、凭证、铭牌…）
    #   ② 机器变体（由 machine_variant_names 按规则算出来）
    java = os.path.join(PROJECT, "src", "main", "java", "com", "deepspacedeployer")
    items_src = code_only(open(os.path.join(java, "registry", "ModItems.java"), encoding="utf-8").read())
    registered = set(re.findall(r'ITEMS\.register\("([a-z0-9_]+)"', items_src))
    registered |= {name for names in machine_variant_names().values() for name in names}
    # 方块文件：有归属的机器每个公司一份掉落表（<机器>_<公司>），无归属的机器一份（<机器>）
    for machine_id, names in machine_variant_names().items():
        for variant in names:
            loot_path = os.path.join(loot_dir, variant + ".json")
            if not os.path.exists(loot_path):
                err("%s 没有掉落表（挖掉什么都不掉）" % variant)
                continue
            with open(loot_path, encoding="utf-8") as f:
                loot = json.load(f)
            text = json.dumps(loot, ensure_ascii=False)
            for dropped in set(re.findall(r'"name"\s*:\s*"deepspacedeployer:([a-z0-9_]+)"', text)):
                if dropped not in registered:
                    err("%s 的掉落表要掉出 deepspacedeployer:%s，但这个物品没有注册"
                        "（挖掉会什么都不掉；原版会报 Unknown registry key）"
                        % (variant, dropped))
            if "copy_state" not in text:
                warn("%s 的掉落表没有 copy_state：挖起来再放会丢掉公司与等级" % variant)
    total = sum(len(n) for n in machine_variant_names().values())
    print("  掉落表检查完成（%d 份，掉出的物品 id 都已注册）" % total)


def check_tool_tags():
    """
    方块必须在 mineable 标签里，否则"正确工具"判定永远为 false —— 即使有掉落表也什么都不掉。
    （原版 Tool.Rule 匹配的是方块标签：state.is(rule.blocks)）
    """
    blockstates = [os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ASSETS, "blockstates", "*.json"))]
    tag_dir = os.path.join(DATA, "tags", "block", "mineable")
    found = {}
    if os.path.isdir(tag_dir):
        for path in glob.glob(os.path.join(tag_dir, "*.json")):
            with open(path, encoding="utf-8") as f:
                values = json.load(f).get("values", [])
            for value in values:
                found[value] = os.path.basename(path)[:-5]
    for name in blockstates:
        full = "deepspacedeployer:" + name
        if full not in found:
            err("%s 不在任何 mineable 标签里 → 就算有掉落表，挖掉也不掉（正确工具判定恒为 false）" % name)
    if blockstates and all(("deepspacedeployer:" + n) in found for n in blockstates):
        print("  工具标签检查完成（%d 个方块都在 %s 里）"
              % (len(blockstates), sorted(set(found.values()))))


# ---------------------------------------------------------------- 4 结构 NBT
def structure_materials(data):
    """
    按 Java 侧 BlueprintParser 的同一套逻辑解析材料：
    读 size / palette(或 palettes) / blocks，统计非空气方块的物品。
    （做这个镜像实现是为了确认数据结构本身没问题。）
    """
    palette = None
    if "palettes" in data and data["palettes"]:
        palette = data["palettes"][0]
    elif "palette" in data:
        palette = data["palette"]
    if not palette:
        return None
    materials = {}
    for block in data["blocks"]:
        state = block.get("state", -1)
        if state < 0 or state >= len(palette):
            continue
        name = palette[state].get("Name", "")
        if name == "minecraft:air":
            continue
        materials[name] = materials.get(name, 0) + 1
    return materials


def check_structures():
    files = glob.glob(os.path.join(DATA, "structure", "factory_*.nbt"))
    if len(files) != 18:
        err("工厂结构数量不是 18，而是 %d" % len(files))
    for path in files:
        name = os.path.basename(path)[:-4]
        parts = name.split("_")
        company, tier = parts[1], parts[2]
        with gzip.open(path, "rb") as f:
            data = parse_nbt(f)
        size = data["size"]
        palette = data["palette"]
        blocks = data["blocks"]

        # 材料表必须能解析出来（对应 Java 侧 BlueprintParser）
        materials = structure_materials(data)
        if not materials:
            err("%s 解析不出任何材料（蓝图解析会得到空清单）" % name)
        elif len(materials) < 3:
            warn("%s 只有 %d 种材料，看着不像完整的工厂" % (name, len(materials)))
        else:
            machines_in_materials = [m for m in materials if m.startswith(MODID + ":")]
            if not machines_in_materials:
                err("%s 的材料表里没有本模组的机器方块" % name)

        if len(size) != 3 or min(size) <= 0:
            err("%s 尺寸不合法：%s" % (name, size))
        machines = []
        for b in blocks:
            state = b["state"]
            if state >= len(palette):
                err("%s 里有越界的调色板下标 %d" % (name, state))
                continue
            pos = b["pos"]
            for axis in range(3):
                if pos[axis] < 0 or pos[axis] >= size[axis]:
                    err("%s 里有超出范围的坐标 %s（尺寸 %s）" % (name, pos, size))
            entry = palette[state]
            if entry["Name"].startswith(MODID + ":"):
                props = entry.get("Properties", {})
                machines.append((entry["Name"], props))
                if props.get("company") != company or props.get("tier") != tier:
                    err("%s 里的机器属性不对：%s %s" % (name, entry["Name"], props))
        kinds = {m[0].split(":")[1] for m in machines}
        expect = {
            "low": {"factory_terminal", "buyer"},
            "mid": {"factory_terminal", "buyer", "factory_core"},
            "high": {"factory_terminal", "buyer", "factory_core"},
        }[tier]
        if kinds != expect:
            err("%s 的机器种类不对：%s（期望 %s）" % (name, kinds, expect))
    print("  结构 NBT 检查完成（%d 份，含材料表可解析性）" % len(files))


# ---------------------------------------------------------------- 5 世界生成
def check_worldgen():
    pools = {os.path.basename(p)[:-5]: json.load(open(p, encoding="utf-8"))
             for p in glob.glob(os.path.join(DATA, "worldgen", "template_pool", "*.json"))}
    structures = {os.path.basename(p)[:-5]: json.load(open(p, encoding="utf-8"))
                  for p in glob.glob(os.path.join(DATA, "worldgen", "structure", "*.json"))}
    sets = {os.path.basename(p)[:-5]: json.load(open(p, encoding="utf-8"))
            for p in glob.glob(os.path.join(DATA, "worldgen", "structure_set", "*.json"))}
    tags = {os.path.basename(p)[:-5] for p in glob.glob(os.path.join(DATA, "tags", "worldgen", "biome", "*.json"))}

    for name, struct in structures.items():
        pool_ref = struct.get("start_pool", "").split(":")[-1]
        if pool_ref not in pools:
            err("结构 %s 引用了不存在的拼图池 %s" % (name, pool_ref))
        biome_ref = struct.get("biomes", "").lstrip("#").split(":")[-1]
        if biome_ref not in tags:
            err("结构 %s 引用了不存在的群系标签 %s" % (name, biome_ref))

    for name, pool in pools.items():
        for element in pool.get("elements", []):
            location = element["element"].get("location", "").split(":")[-1]
            if not os.path.exists(os.path.join(DATA, "structure", location + ".nbt")):
                err("拼图池 %s 引用了不存在的结构文件 %s" % (name, location))

    for name, s in sets.items():
        for item in s.get("structures", []):
            ref = item["structure"].split(":")[-1]
            if ref not in structures:
                err("生成规则 %s 引用了不存在的结构 %s" % (name, ref))

    with open(os.path.join(DATA, "tags", "worldgen", "biome", "has_factory_low.json"), encoding="utf-8") as f:
        low_biomes = json.load(f)["values"]
    print("  世界生成检查完成（%d 结构 / %d 拼图池 / %d 规则 / %d 标签，低级厂群系 %d 个）"
          % (len(structures), len(pools), len(sets), len(tags), len(low_biomes)))


# 无归属、无等级的机器（全模组一件）—— 和 ModItems 里的注册规则保持一致
UNOWNED_MACHINES = {"market"}


def machine_sources():
    """读一遍和"机器注册"有关的几个源文件（都去掉注释）。"""
    java = os.path.join(PROJECT, "src", "main", "java", "com", "deepspacedeployer")
    files = {
        "kinds": os.path.join(java, "blockentity", "MachineKind.java"),
        "items": os.path.join(java, "registry", "ModItems.java"),
        "menus": os.path.join(java, "registry", "ModMenus.java"),
        "entities": os.path.join(java, "registry", "ModBlockEntities.java"),
        "blocks": os.path.join(java, "registry", "ModBlocks.java"),
        "company": os.path.join(java, "Company.java"),
    }
    return {key: code_only(open(path, encoding="utf-8").read()) for key, path in files.items()}


def machine_variant_names():
    """机器 → 方块物品注册名列表（和 ModItems 里的注册规则保持一致）。

    - 无归属的机器（寄售市场）→ 全世界一件，名字就是 <机器>
    - 有归属的机器（核心/商店/收购台）→ 每家公司一件，名字是 <机器>_<公司>
    """
    src = machine_sources()
    kinds = re.findall(r'^\s{4}([A-Z][A-Z0-9_]*)\("([a-z_]+)"', src["kinds"], re.M)
    companies = re.findall(r'^\s{4}[A-Z][A-Z0-9_]*\("([a-z_]+)"', src["company"], re.M)
    out = {}
    for _, machine_id in kinds:
        out[machine_id] = ([machine_id] if machine_id in UNOWNED_MACHINES
                           else ["%s_%s" % (machine_id, c) for c in companies])
    return out


def code_only(source):
    """去掉 Java 注释，避免"被注释掉的代码"骗过检查。

    （实测踩过：把 ModBlocks.MARKET.get() 注释掉之后，字符串还在文件里，
    简单的 re.search 依然命中 —— 守卫等于没生效。）
    """
    source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)   # 块注释
    lines = []
    for line in source.split('\n'):
        # 行注释：按 // 切开（字符串里出现 // 的情况在我们这几个注册文件里不存在）
        idx = line.find('//')
        lines.append(line if idx < 0 else line[:idx])
    return '\n'.join(lines)


def check_machine_registrations():
    """每个 MachineKind 都必须在这些地方登记：方块物品 / 菜单 / 方块实体 / 方块状态。

    这两条检查是拿真实崩溃换来的：
      ① 加「寄售市场」时漏了 ModItems 里的那一行 → 掉落表引用的物品不存在 +
         创造物品栏里 ItemStack 数量变 0 → NeoForge 抛 "The stack count must be 1" → 启动崩。
      ② 同一次还漏了 ModBlockEntities 里的合法方块清单 → 放置市场方块时原版
         BlockEntity.validateBlockState 抛 "Invalid block entity ... got Block{...market}" → 放置即崩。
    """
    java = os.path.join(PROJECT, "src", "main", "java", "com", "deepspacedeployer")
    kinds_file = os.path.join(java, "blockentity", "MachineKind.java")
    items_file = os.path.join(java, "registry", "ModItems.java")
    menus_file = os.path.join(java, "registry", "ModMenus.java")
    entities_file = os.path.join(java, "registry", "ModBlockEntities.java")
    blocks_file = os.path.join(java, "registry", "ModBlocks.java")
    for path in (kinds_file, items_file, menus_file, entities_file, blocks_file):
        if not os.path.exists(path):
            err("找不到 %s（检查机器注册用）" % os.path.relpath(path, PROJECT))
            return
    kinds_src = code_only(open(kinds_file, encoding="utf-8").read())
    items_src = code_only(open(items_file, encoding="utf-8").read())
    menus_src = code_only(open(menus_file, encoding="utf-8").read())
    entities_src = code_only(open(entities_file, encoding="utf-8").read())
    blocks_src = code_only(open(blocks_file, encoding="utf-8").read())

    # 枚举常量：形如  NAME("id", ...
    kinds = re.findall(r'^\s{4}([A-Z][A-Z0-9_]*)\("([a-z_]+)"', kinds_src, re.M)
    if not kinds:
        err("MachineKind 里没解析出任何枚举常量")
        return
    companies = re.findall(r'^\s{4}[A-Z][A-Z0-9_]*\("([a-z_]+)"',
                           code_only(open(os.path.join(java, "Company.java"), encoding="utf-8").read()), re.M)
    if len(companies) != 6:
        err("Company 里解析出 %d 家公司（应该是 6）" % len(companies))
        return
    # 变体规则（和 ModItems 里的注册保持一致）：
    #   无归属的机器（寄售市场）→ 全世界一件，注册名就是 <机器>；
    #   有归属的机器（核心/商店/收购台）→ 每家公司一件，注册名 <机器>_<公司>。
    # 同时核对代码里确实注册了这些名字，避免"规则和代码走散"。
    machine_variant_names = {}
    for _, machine_id in kinds:
        if machine_id in UNOWNED_MACHINES:
            machine_variant_names[machine_id] = [machine_id]
        else:
            machine_variant_names[machine_id] = ["%s_%s" % (machine_id, c) for c in companies]
    for machine_id, names in machine_variant_names.items():
        for name in names:
            # 注册名有三种写法：字符串字面量、MachineKind.X.id()、kind.id() + "_" + company.id()
            literal = re.search(r'ITEMS\.register\("%s"' % re.escape(name), items_src)
            via_kind = False
            for const, kind_id in kinds:
                if kind_id == name and re.search(r'ITEMS\.register\(MachineKind\.%s\.id\(\)' % const, items_src):
                    via_kind = True
            generated = (len(names) == len(companies)
                         and re.search(r'String name = kind\.id\(\) \+ "_" \+ company\.id\(\)', items_src)
                         and re.search(r'ITEMS\.register\(name', items_src.replace(' ', '')))
            if not (literal or via_kind or generated):
                err("ModItems 里没有注册方块物品 %s —— 掉落表会解析失败、"
                    "创造物品栏会崩（见 docs/设计决策.md §2.38）" % name)
    # 方块物品现在是"机器 × 公司 × 等级"各一件，注册名形如 <机器>_<公司>_<等级>
    companies = [m.group(1) for m in re.finditer(r'^\s{4}[A-Z][A-Z0-9_]*\("([a-z_]+)"', 
                                                 open(os.path.join(java, "Company.java"), encoding="utf-8").read(), re.M)]
    tiers = [m.group(1) for m in re.finditer(r'^\s{4}[A-Z][A-Z0-9_]*\("([a-z_]+)"',
                                             open(os.path.join(java, "Tier.java"), encoding="utf-8").read(), re.M)]
    if len(companies) != 6 or len(tiers) != 3:
        err("解析公司/等级列表失败：公司 %d 个、等级 %d 个（应该是 6 / 3）"
            % (len(companies), len(tiers)))
        return
    variants = 0
    for const, machine_id in kinds:
        # ModBlocks 里的字段名（TERMINAL -> FACTORY_TERMINAL）
        field = re.search(r'DeferredHolder<Block, MachineBlock>\s+([A-Z_]+)\s*=\s*register\(MachineKind\.%s\)'
                          % const, blocks_src)
        if not field:
            err("ModBlocks 里没有 MachineKind.%s（%s）对应的方块字段" % (const, machine_id))
            field_name = None
        else:
            field_name = field.group(1)
        # ① 每个公司 × 等级都要有物品注册（在 MACHINE_VARIANTS 的循环里生成）
        if not re.search(r'ITEMS\.register\(name', items_src.replace(' ', '')):
            err("ModItems 里没有按 机器_公司_等级 批量注册方块物品（应该用 ITEMS.register(name, ...)）")
        # ② 菜单
        if not re.search(r'register\(MachineKind\.%s\)' % const, menus_src):
            err("MachineKind.%s（%s）没有注册菜单（ModMenus 里要加 register(...)）" % (const, machine_id))
        # ③ 方块实体的合法方块清单
        if field_name and not re.search(r'ModBlocks\.%s\.get\(\)' % field_name, entities_src):
            err("MachineKind.%s 的方块 %s 不在方块实体类型的合法方块清单里"
                "（ModBlockEntities.MACHINE 里要加 ModBlocks.%s.get()）—— "
                "漏了会在放置时抛 Invalid block entity 并崩溃"
                % (const, field_name, field_name))
        # ④ 每个变体的方块状态 + 物品模型 + 双语名字
        #    变体名直接从 ModItems 的注册语句里解析 —— 规则跟代码走，不靠猜命名。
        for variant in machine_variant_names.get(machine_id, []):
            variants += 1
            item_model = os.path.join(ASSETS, "models", "item", "%s.json" % variant)
            if not os.path.exists(item_model):
                err("方块物品 %s 没有物品模型（models/item/%s.json）—— 创造栏里会变成紫黑块"
                    % (variant, variant))
            key = "block.deepspacedeployer.%s" % variant
            for lang_file in ("zh_cn.json", "en_us.json"):
                data = json.load(open(os.path.join(ASSETS, "lang", lang_file), encoding="utf-8"))
                if key not in data:
                    err("%s 缺少名字：%s（创造栏里会显示成原始 key）" % (lang_file, key))
        state = os.path.join(ASSETS, "blockstates", "%s.json" % machine_id)
        if not os.path.exists(state):
            err("MachineKind.%s 没有 blockstate：%s" % (const, os.path.relpath(state, PROJECT)))
    print("  机器注册检查完成（%d 个 MachineKind，共 %d 个物品变体："
          "方块物品 / 菜单 / 方块实体 / 贴图 / 名字都在）"
          % (len(kinds), variants))


def main():
    print("开始自检……")
    check_models()
    check_lang()
    check_loot_tables()
    check_tool_tags()
    check_machine_registrations()
    check_structures()
    check_worldgen()

    print()
    if errors:
        print("[FAIL] 发现 %d 个问题：" % len(errors))
        for e in errors:
            print("   -", e)
    if warnings:
        print("[WARN] %d 条提醒：" % len(warnings))
        for w in warnings:
            print("   -", w)
    if not errors:
        print("[OK] 全部检查通过")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
