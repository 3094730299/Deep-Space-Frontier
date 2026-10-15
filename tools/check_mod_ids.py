# -*- coding: utf-8 -*-
"""
物品 id 校验器：拿整合包里真实的模组 jar，检查我们写的每个 id 是否真的存在、能不能当商品。
    python tools/check_mod_ids.py

为什么需要：价目表、蓝图材料、结构方块都是按 "命名空间:路径" 写的字符串，写错一个字母，
游戏里就是"空气"或整条商品失效。这里直接从各模组的资源里取出真实清单来对。

两种检查强度：
  valid_thing()  —— 方块或物品都行（结构里的方块、蓝图材料用这个）
  valid_item()   —— 必须是"真物品"（有 assets/<ns>/models/item/<路径>.json）
                    商店里只能卖物品；只靠语言文件会误判 ——
                    例如 simulated:black_symmetric_sail 有 block.* 语言键，
                    但它只是个方块、没有物品形态，卖不了（游戏里会被自检跳过）。

数据来源：原版 build/moddev/artifacts/…-client-extra…jar；模组 整合包 mods/*.jar（含 JarJar 内嵌 jar）
"""
import glob
import gzip
import io
import json
import os
import re
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
MODID = "deepspacedeployer"
MODS_DIR = r"C:\Users\30947\Desktop\.minecraft\versions\孢子危机\mods"
BLUEPRINT_DIRS = [
    r"C:\Users\30947\Desktop\.minecraft\versions\孢子危机\config\deepspacedeployer\blueprints",
    os.path.join(PROJECT, "examples"),
]
CLIENT_EXTRA = os.path.join(PROJECT, "build", "moddev", "artifacts",
                            "neoforge-21.1.248-client-extra-aka-minecraft-resources.jar")

problems = []


def note(msg):
    print(msg)


# ---------------------------------------------------------------- 从 jar 里取清单
def scan_names(zf, all_ids, item_keys, models):
    """把 jar 里的语言文件键与物品模型扫进三个集合"""
    names = zf.namelist()
    for name in names:
        m = re.match(r"assets/([^/]+)/models/item/(.+)\.json$", name)
        if m:
            models.add("%s:%s" % (m.group(1), m.group(2)))
    for ns in {m.group(1) for m in (re.match(r"assets/([^/]+)/lang/en_us\.json$", n) for n in names) if m}:
        try:
            data = json.loads(zf.read("assets/%s/lang/en_us.json" % ns).decode("utf-8", "replace"))
        except Exception:
            continue
        for key in data:
            if key.startswith("item." + ns + ".") or key.startswith("block." + ns + "."):
                path = key.split(".", 2)[2]
                all_ids.add("%s:%s" % (ns, path))
                if key.startswith("item."):
                    item_keys.add("%s:%s" % (ns, path))


def collect_vanilla():
    all_ids, item_keys, models = set(), set(), set()
    if not os.path.exists(CLIENT_EXTRA):
        note("  [警告] 找不到原版资源 jar，跳过原版校验")
        return None, None, None
    with zipfile.ZipFile(CLIENT_EXTRA) as zf:
        names = zf.namelist()
        if "assets/minecraft/lang/en_us.json" in names:
            scan_names(zf, all_ids, item_keys, models)
    return all_ids, item_keys, models


def collect_mods():
    all_ids, item_keys, models = set(), set(), set()

    def eat(zf):
        scan_names(zf, all_ids, item_keys, models)
        for name in zf.namelist():
            if re.match(r"META-INF/jarjar/.*\.jar$", name):
                try:
                    inner = zipfile.ZipFile(io.BytesIO(zf.read(name)))
                    eat(inner)
                    inner.close()
                except Exception:
                    pass

    for jar in glob.glob(os.path.join(MODS_DIR, "*.jar")):
        try:
            with zipfile.ZipFile(jar) as zf:
                eat(zf)
        except Exception as e:
            note("  [警告] 读不了 %s：%s" % (os.path.basename(jar), e))
    return all_ids, item_keys, models


def collect_own_ids():
    """我们自己的 id 从语言文件 + 代码里循环注册的名字展开"""
    own_all, own_items = set(), set()
    lang = os.path.join(PROJECT, "src", "main", "resources", "assets", MODID, "lang", "zh_cn.json")
    if os.path.exists(lang):
        with io.open(lang, encoding="utf-8") as f:
            data = json.load(f)
        for key in data:
            if key.startswith("item." + MODID + "."):
                own_all.add(MODID + ":" + key.split(".", 2)[2])
                own_items.add(MODID + ":" + key.split(".", 2)[2])
            elif key.startswith("block." + MODID + "."):
                own_all.add(MODID + ":" + key.split(".", 2)[2])
    for extra in ("blueprint_voucher", "tier_upgrade_kit", "bank_card"):
        own_all.add(MODID + ":" + extra)
        own_items.add(MODID + ":" + extra)
    for company in ("threshold", "whisperbelt", "brokenedge", "skimmer", "bedrock", "astralink"):
        own_all.add("%s:%s_scrip" % (MODID, company))
        own_all.add("%s:%s_license" % (MODID, company))
        own_items.add("%s:%s_scrip" % (MODID, company))
        own_items.add("%s:%s_license" % (MODID, company))
        for tier in ("low", "mid", "high"):
            for machine in ("factory_terminal", "factory_core", "welder", "buyer"):
                own_all.add("%s:%s_%s_%s" % (MODID, machine, company, tier))
    return own_all, own_items


# ---------------------------------------------------------------- 极简 NBT 读取
def read_nbt(f):
    tag_type = f.read(1)[0]
    if tag_type != 10:
        raise ValueError("根标签不是 Compound")
    name_len = struct.unpack(">H", f.read(2))[0]
    f.read(name_len)
    return read_payload(f, 10)


def read_payload(f, tag_type):
    if tag_type == 1:
        return struct.unpack(">b", f.read(1))[0]
    if tag_type == 2:
        return struct.unpack(">h", f.read(2))[0]
    if tag_type == 3:
        return struct.unpack(">i", f.read(4))[0]
    if tag_type == 4:
        return struct.unpack(">q", f.read(8))[0]
    if tag_type == 5:
        return struct.unpack(">f", f.read(4))[0]
    if tag_type == 6:
        return struct.unpack(">d", f.read(8))[0]
    if tag_type == 7:
        n = struct.unpack(">i", f.read(4))[0]
        return list(f.read(n))
    if tag_type == 8:
        n = struct.unpack(">H", f.read(2))[0]
        return f.read(n).decode("utf-8", "replace")
    if tag_type == 9:
        item_type = f.read(1)[0]
        n = struct.unpack(">i", f.read(4))[0]
        return [read_payload(f, item_type) for _ in range(n)]
    if tag_type == 10:
        out = {}
        while True:
            t = f.read(1)[0]
            if t == 0:
                return out
            name_len = struct.unpack(">H", f.read(2))[0]
            name = f.read(name_len).decode("utf-8", "replace")
            out[name] = read_payload(f, t)
    if tag_type == 11:
        n = struct.unpack(">i", f.read(4))[0]
        return [struct.unpack(">i", f.read(4))[0] for _ in range(n)]
    if tag_type == 12:
        n = struct.unpack(">i", f.read(4))[0]
        return [struct.unpack(">q", f.read(8))[0] for _ in range(n)]
    raise ValueError("未知标签类型 %d" % tag_type)


def main():
    print("读取原版物品清单……")
    v_all, v_keys, v_models = collect_vanilla()
    print("读取模组物品清单（含内嵌 jar）……")
    m_all, m_keys, m_models = collect_mods()
    own_all, own_items = collect_own_ids()
    print("  模组命名空间共 %d 个 id，其中 %d 个有物品模型（能当商品卖）"
          % (len(m_all), len(m_models)))
    print("  我们自己的 id：%d 个（物品 %d 个）" % (len(own_all), len(own_items)))

    def valid_thing(item_id):
        ns = item_id.partition(":")[0]
        if ns == "minecraft":
            return v_all is None or item_id in v_all
        if ns == MODID:
            return item_id in own_all
        return item_id in m_all

    def valid_item(item_id):
        ns, _, path = item_id.partition(":")
        if ns == "minecraft":
            if v_models is None:
                return True
            return item_id in v_models or ("item.%s.%s" % (ns, path)) in v_keys
        if ns == MODID:
            return item_id in own_items
        if item_id in m_models:
            return True
        # 少数物品没有模型文件（纯代码渲染），退一步看有没有 item.* 语言键
        return ("item.%s.%s" % (ns, path)) in m_keys

    # 0) 检查器自测：假 id 与"方块冒充物品"都必须被抓到
    if valid_item("aeronautics:definitely_not_a_real_item") or valid_thing("deepspacedeployer:nope"):
        print("[FAIL] 检查器失效：假 id 也通过了")
        return 1
    if valid_item("simulated:black_symmetric_sail"):
        print("[FAIL] 检查器失效：方块冒充物品应该被抓到")
        return 1
    print("  检查器自测通过")

    # 1) 价目表（必须都是真物品）
    shops = glob.glob(os.path.join(PROJECT, "src", "main", "resources", "data", MODID, "shop", "*.json"))
    checked = 0
    for path in shops:
        with io.open(path, encoding="utf-8") as f:
            data = json.load(f)
        name = os.path.basename(path)
        for entry in data.get("buy", []) + data.get("sell", []):
            checked += 1
            if not valid_item(entry["item"]):
                problems.append("%s 里的商品不是可用物品（可能是方块或 id 写错）：%s"
                                % (name, entry["item"]))
    print("  价目表检查完成（%d 条）" % checked)

    # 2) 结构里的方块
    struct_count = 0
    for path in glob.glob(os.path.join(PROJECT, "src", "main", "resources", "data", MODID, "structure", "*.nbt")):
        with gzip.open(path, "rb") as f:
            data = read_nbt(f)
        struct_count += 1
        for entry in data.get("palette", []):
            block = entry.get("Name", "")
            if block and not valid_thing(block):
                problems.append("%s 用到的方块不存在：%s" % (os.path.basename(path), block))
    print("  结构方块检查完成（%d 份）" % struct_count)

    # 3) 蓝图材料
    bp_count = 0
    for folder in BLUEPRINT_DIRS:
        for path in glob.glob(os.path.join(folder, "*.nbt")):
            try:
                with gzip.open(path, "rb") as f:
                    data = read_nbt(f)
            except Exception as e:
                note("  [警告] 蓝图读不了 %s：%s" % (os.path.basename(path), e))
                continue
            bp_count += 1
            for entry in data.get("palette", []):
                block = entry.get("Name", "")
                if block and not valid_thing(block):
                    problems.append("蓝图 %s 用到的方块不存在：%s" % (os.path.basename(path), block))
    print("  蓝图材料检查完成（%d 份）" % bp_count)

    print()
    if problems:
        for p in problems:
            print("[FAIL] " + p)
        print("\n共 %d 个问题，需要修" % len(problems))
        return 1
    print("[OK] 所有引用的物品/方块 id 都能在整合包里找到，且商店商品都是真物品")
    return 0


if __name__ == "__main__":
    sys.exit(main())
