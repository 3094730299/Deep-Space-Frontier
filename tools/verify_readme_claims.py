# -*- coding: utf-8 -*-
"""
交接文档自检：验证 README-交接.md 里声称的事实是否与实际代码/资源一致。

    python tools/verify_readme_claims.py

为什么要它：交接文档里写"某功能已实现"，接手的人会信。
如果代码早就改掉了，文档就成了误导 —— 这个脚本把那些断言变成可执行的检查。
"""
import glob
import io
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join(ROOT, "src", "main", "java", "com", "deepspacedeployer")
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
D = os.path.join(ROOT, "src", "main", "resources", "data", "deepspacedeployer")


def _find_jar():
    """
    找 build/libs 里那个模组 jar。

    ⚠️ 以前这里把版本号写死在路径里（deepspacedeployer-0.1.0+mc1.21.1.jar），
    结果一提版本号这个脚本就报"构建产物不存在" —— 是脚本的问题，不是代码的问题。
    现在按 glob 找：谁在 build/libs 里就用谁（sources / javadoc 不算）。
    """
    import glob as _g
    cands = [p for p in _g.glob(os.path.join(ROOT, "build", "libs", "*.jar"))
             if "sources" not in os.path.basename(p) and "javadoc" not in os.path.basename(p)]
    return sorted(cands)[0] if cands else os.path.join(ROOT, "build", "libs", "(没构建过)")


JAR = _find_jar()


def read(*parts):
    with io.open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


def main():
    checks = []

    be = read(J, "blockentity", "MachineBlockEntity.java")
    checks += [
        ("forceRestart 用方块坐标 hash 取管理器",
         "getManagerForId(instanceId())" in be and "worldPosition.hashCode()" in be),
        ("AnimPhase 阶段机存在", "enum AnimPhase" in be),
        ("PlaceAnimDone 写入 NBT", 'tag.putBoolean("PlaceAnimDone", placeAnimDone);' in be),
        ("PlaceAnimDone 从 NBT 读回", 'placeAnimDone = tag.getBoolean("PlaceAnimDone");' in be),
        ("服务端置位入口 onPlaceAnimDone", "public void onPlaceAnimDone()" in be),
        ("客户端回执 sendPlaceAnimDone", "sendPlaceAnimDone()" in be),
        ("握手兜底 ANIM_FALLBACK_MS = 1500", "ANIM_FALLBACK_MS = 1500L" in be),
        ("阶段推进挂在控制器回调里", "clientAnimTick();" in be),
        ("首次渲染兜底 placeAnimTriggered", "placeAnimTriggered" in be),
        ("多方块共用开界面入口 openMenuFor", "public void openMenuFor(" in be),
        ("创造板条箱诊断", "lastCreativeDiag" in be),
        ("投送时序诊断", "[投送时序]" in be),
        ("forceRestart 改用 forceAnimationReset（不再产生触发式动画）",
         "controller.forceAnimationReset();" in be and "manager.tryTriggerAnimation" not in be),
        ("控制器不再注册 triggerableAnim（触发式动画 = 播两次的元凶）",
         ".triggerableAnim(ANIM_PLACE" not in be and ".triggerableAnim(ANIM_DELIVER" not in be),
        ("首次渲染兜底日志带实例标识（便于判断方块实体是否被重建）",
         "System.identityHashCode(this)" in be),
    ]

    r = read(J, "client", "gecko", "MachineGeoRenderer.java")
    checks += [
        ("渲染器有 usesGeo 守卫（防 null.geo.json 崩溃）",
         "usesGeo(animatable)" in r),
    ]

    m = read(J, "client", "gecko", "MachineGeoModel.java")
    checks += [
        ("按机器种类挑模型（BUYER/CORE）",
         '"buyer"' in m and '"factory_core"' in m),
        # 商店那套模型只有星联动力联盟用（老板 2026-10-16 定：三个等级共用一套）
        ("星联商店模型：按「种类 + 公司」挑，贴图独占（不带公司后缀）",
         '"terminal_astralink"' in m and "Company.ASTRALINK == be.company()" in m
         and 'geo.startsWith("terminal_")' in m),
    ]

    sa = read(J, "client", "gecko", "TerminalShopAnim.java")
    checks += [
        ("商店动画：展开/闭合且停在最后一帧（HOLD_ON_LAST_FRAME）",
         'ANIM_OPEN = "展开"' in sa and 'ANIM_CLOSE = "闭合"' in sa
         and "Animation.LoopType.HOLD_ON_LAST_FRAME" in sa),
        ("商店动画：正在播时不被打断（播完才接受下一次切换）",
         "if (nowMs - moveStartedMs < ANIM_MS)" in sa),
        ("商店动画：重启走 forceAnimationReset（restartAnimation → forceRestart）",
         "be.restartAnimation(CTRL" in sa
         and "public void restartAnimation(String controllerName, String animName)" in be),
        ("商店：只在 TERMINAL 上注册控制器，且只对星联那套模型生效",
         "if (kind() == MachineKind.TERMINAL) {" in be
         and "MachineGeoModel.usesGeo(be)" in sa),
        # 2026-10-16 实测踩到：不推时间轴的话动画永远停在第 0 帧，
        # 状态机日志一切正常但画面一动不动（「展开」第 0 帧正好是闭合姿势）
        ("商店动画会推进 GeckoLib 时间轴（不推就永远停在第 0 帧）",
         "public void advanceAnimClockOnly()" in be
         and "be.advanceAnimClockOnly();" in sa),
    ]

    ps = read(J, "multiblock", "PrinterStructure.java")
    flat = ps.replace(" ", "")
    checks += [
        ("多方块 6 列 × 2 层", "COLUMNS=6" in flat and "HEIGHT=2" in flat),
        ("OFF_Z 已后移一格 {-1,-1,-1,0,0,0}",
         "{-1,-1,-1,0,0,0}" in flat),
        ("OFF_X = {0,1,2,0,1,2}", "{0,1,2,0,1,2}" in flat),
    ]

    sp = read(J, "block", "PrinterPartBlock.java")
    checks += [
        ("结构件没有方块实体", "newBlockEntity" not in sp),
        ("结构件碰撞 = 整方块", "Shapes.block()" in sp),
        ("结构件可反推控制器", "controllerPos" in sp),
        ("拆多方块有重入保护", "TEARDOWN" in read(J, "block", "MachineBlock.java")),
    ]

    mb = read(J, "block", "MachineBlock.java")
    checks += [
        ("载具打印机「12 格缺一不放」（放置前先查 canPlaceAll）",
         "canPlaceAll(context.getLevel(), context.getClickedPos(), facing)" in mb),
        ("控制器那格仍然是实心整方块（不让别的结构重叠）",
         "CORE_SHAPE = Shapes.block();" in mb),
    ]

    # 收购投送机：顶上有方块 → 不工作 + 界面提示
    mm = read(J, "menu", "MachineMenu.java")
    checks += [
        ("投送机「顶上有方块不工作」：数据槽（14/15）与判定都在",
         "DATA_BLOCKED_ABOVE = 14" in mm and "DATA_COUNT = 15" in mm
         and "public boolean blockedAbove()" in mm),
        ("投送机顶被挡时真的不收购（自动 + 手动两处拦截）",
         "case MachineMenu.DATA_BLOCKED_ABOVE -> blockedAbove() ? 1 : 0;" in be
         and be.count("if (blockedAbove()) {") >= 2),
        ("顶上被挡的界面提示已接上语言键",
         "gui.deepspacedeployer.blocked_above" in read(J, "client", "MachineScreen.java")
         and "blocked_above" in read(A, "lang", "zh_cn.json")
         and "blocked_above" in read(A, "lang", "en_us.json")),
        ("顶上判定：从机器上方一直扫到世界顶部，且只认「占满整格」的方块",
         "shape.isEmpty()" in be and "shape.min(Direction.Axis.Y) <= 0.0D" in be
         and "getMaxBuildHeight()" in be and "worldPosition.getY() + 1" in be
         and "BLOCKED_ABOVE_CACHE_TICKS" in be),
    ]

    # 资源隔离（老板 2026-10-15："每个物品单独做一个隔离"）
    gt = read(ROOT, "tools", "gen_textures.py")
    checks += [
        ("贴图生成器默认不覆盖已存在文件（改一个物品不会波及其他）",
         "class Writer" in gt and "if os.path.exists(path) and not self.force:" in gt
         and '"--force"' in gt),
        ("手绘定稿永不覆盖，且清单自动来自 art/手绘定稿/（不是硬编码）",
         "HAND_DIR" in gt and "hand_painted_names" in gt and "self.protected" in gt),
        ("贴图生成器支持只处理点名的物品",
         "def selected(" in gt and '"--list"' in gt),
    ]

    # 发布包不能是"新代码配旧包"（2026-10-16 真发生过：zip 是 4 天前的，
    # 里面装的 jar 没有商店模型/贴图修复。两边都存在时才校验，空仓库不算错）
    import glob as _glob
    import zipfile as _zipfile
    # ⚠️ 两个坑（都踩过）：
    #   ① glob 不保证顺序，release/ 里留着旧版本的包时会拿到旧包 → 断言假红。
    #      所以显式按修改时间取【最新】的那个。
    #   ② jar 名不能写死版本号（一提版本就找不到）→ 统一按 glob 找。
    _zips = sorted(_glob.glob(os.path.join(ROOT, "release", "*.zip")),
                   key=os.path.getmtime)
    _jars = [j for j in _glob.glob(os.path.join(ROOT, "build", "libs", "*.jar"))
             if "sources" not in os.path.basename(j)]
    if _zips and _jars:
        with open(_jars[0], "rb") as _fh:
            _built = _fh.read()
        with _zipfile.ZipFile(_zips[-1]) as _z:
            _inner = [n for n in _z.namelist() if n.startswith("mods/") and n.endswith(".jar")]
            _payload = _z.read(_inner[0]) if _inner else b""
        checks.append(("发布包 release/*.zip 里装的就是当前 build/libs 的 jar（不是旧包）",
                       _payload == _built))

    lt = os.path.join(D, "loot_table", "blocks", "printer_part.json")
    checks.append(("结构件掉落表为空（只掉控制器）",
                   os.path.exists(lt) and json.load(io.open(lt, encoding="utf-8"))["pools"] == []))

    # geo 声明 vs 贴图尺寸
    #
    # ⚠️ 规则（2026-10-15 用字节码确认后改正）：**声明保持不动，PNG 是它的整数倍**。
    #    GeckoLib 的 GeoQuad.build 里算的是 normalizedU = uv / texture_width，
    #    所以「声明 64 + PNG 128」= 同一块图案、两倍分辨率（正确且更清晰）；
    #    而「把声明改成 128」会让 UV 空间变小 → 采样区域错位 → 整片花屏（真发生过）。
    for geo, tex, size in (("buyer", "buyer_threshold", 256),
                           ("factory_core", "factory_core_threshold", 256),
                           ("universal_terminal", "universal_terminal", 64)):
        gp = os.path.join(A, "geo", "block", geo + ".geo.json")
        d = json.load(io.open(gp, encoding="utf-8"))["minecraft:geometry"][0]["description"]
        decl = (d.get("texture_width"), d.get("texture_height"))
        tp = os.path.join(A, "textures", "block", tex + ".png")
        ok = decl == (size, size) and os.path.exists(tp)
        if ok:
            # 直接读 PNG 的 IHDR 拿宽高（不引第三方库）
            try:
                with open(tp, "rb") as fh:
                    head = fh.read(24)
                if head[:8] == b"\x89PNG\r\n\x1a\n":
                    w = int.from_bytes(head[16:20], "big")
                    h = int.from_bytes(head[20:24], "big")
                    ok = (w % size == 0 and h % size == 0 and w >= size and h >= size)
                else:
                    ok = False
            except OSError:
                ok = False
        checks.append(("%s：geo 声明 %dx%d、贴图是它的整数倍" % (geo, size, size), ok))

    fvs = {json.load(io.open(p, encoding="utf-8")).get("format_version")
           for p in glob.glob(os.path.join(A, "geo", "block", "*.geo.json"))}
    checks.append(("所有 geo 的 format_version = 1.12.0", fvs == {"1.12.0"}))

    # 动画文件的 GeckoLib 关键帧规则（「0.0」必须是通道里的第一项，否则整份文件作废）
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import check_gecko_animations as cga
    anim_problems = []
    for p in sorted(glob.glob(os.path.join(A, "animations", "block", "*.json"))):
        anim_problems += cga.check_file(p)[1]
    checks.append(("动画文件过 GeckoLib 规则（「0.0」是第一项、时间递增、loop 合法）",
                   not anim_problems))

    # 工具齐备
    for tool in ("install.py", "validate.py", "check_mod_ids.py", "make_github_repo.py",
                 "check_gecko_textures.py", "check_gecko_animations.py",
                 "gen_machine_shapes.py", "render_geo_anim.py"):
        checks.append(("工具存在：%s" % tool, os.path.exists(os.path.join(ROOT, "tools", tool))))

    # jar 完整性
    if os.path.exists(JAR):
        with zipfile.ZipFile(JAR) as z:
            names = z.namelist()
            ok = z.testzip() is None and all(
                len(z.read(n)) >= 0 for n in names)
            checks.append(("构建产物 zip 完好（%d 条目）" % len(names), ok))
            checks.append(("jar 里有 worldgen 数据",
                           sum(1 for n in names if "worldgen" in n) > 0))
    else:
        checks.append(("构建产物存在（先跑 gradle clean build）", False))

    print("%-52s %s" % ("断言", "结果"))
    print("-" * 66)
    bad = 0
    for name, ok in checks:
        print("%-52s %s" % (name, "✓" if ok else "✗ 【与文档不符】"))
        if not ok:
            bad += 1
    print()
    print("共 %d 条，%s" % (len(checks), "全部符合 ✓" if bad == 0 else "【%d 条不符，文档需更新】" % bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
