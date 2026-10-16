# -*- coding: utf-8 -*-
"""
安全安装：把构建产物复制到整合包 mods 目录，并**校验到字节级一致**。

    python tools/install.py

为什么需要它：以前直接 Copy-Item，出现过"复制不完整"→ jar 里的 zip 条目损坏
→ 游戏读世界生成数据时抛 ZipException → **卡在加载界面 / 旧存档进不去**。

这个脚本：
  1) 复制前先删掉旧文件（避免占用/半覆盖）
  2) **顺手清掉 mods 目录里其它版本的本模组 jar**（版本号一升，旧 jar 不会自己消失，
     两个同 modid 的 jar 同时在 mods 里会让游戏起不来）
  3) 复制后比对大小 + SHA256
  4) 逐个 zip 条目解压校验（testzip + 真读一遍）
  5) 任何一步不对就重试，重试 3 次仍失败就报错退出
"""
import glob
import hashlib
import os
import shutil
import sys
import time
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DST_DIR = r"C:\Users\30947\Desktop\.minecraft\versions\孢子危机\mods"
JAR_PREFIX = "deepspacedeployer-"


def find_jar():
    """
    在 build/libs 里找模组 jar。

    ⚠️ **不要把版本号写死在路径里** —— 以前这里写的是
    `deepspacedeployer-0.1.0+mc1.21.1.jar`，版本一升到 0.1.1 就"找不到构建产物"了
    （2026-10-16 真发生过）。按 glob 找：谁在 build/libs 里就用谁（sources/javadoc 不算）。
    """
    cands = [p for p in glob.glob(os.path.join(ROOT, "build", "libs", "*.jar"))
             if "sources" not in os.path.basename(p) and "javadoc" not in os.path.basename(p)]
    return max(cands, key=os.path.getmtime) if cands else None


SRC = find_jar()
DST = os.path.join(DST_DIR, os.path.basename(SRC)) if SRC else ""


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_zip(path):
    """返回 (是否完好, 说明)"""
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            bad = z.testzip()
            if bad:
                return False, "条目损坏: %s" % bad
            for n in names:
                z.read(n)                      # 真读一遍（testzip 不够严）
            wg = [n for n in names if "worldgen" in n]
            return True, "%d 个条目全部可读（worldgen %d 个）" % (len(names), len(wg))
    except Exception as e:
        return False, str(e)[:90]


def main():
    if not SRC or not os.path.exists(SRC):
        print("找不到构建产物（build/libs 里没有 %s*.jar）" % JAR_PREFIX)
        print("先跑一次 gradle build")
        return 1
    print("构建产物：%s" % os.path.basename(SRC))

    ok, why = verify_zip(SRC)
    print("构建产物校验：%s（%s）" % ("✓" if ok else "✗", why))
    if not ok:
        return 1

    src_hash = sha256(SRC)
    src_size = os.path.getsize(SRC)
    print("构建产物：%d 字节  SHA256=%s" % (src_size, src_hash[:16]))

    if not os.path.isdir(DST_DIR):
        print("找不到整合包 mods 目录：%s" % DST_DIR)
        return 1

    # 0) 清掉 mods 里【其它版本】的本模组 jar
    #    ⚠️ 版本号一升（0.1.0 → 0.1.1），旧 jar 不会自动消失。
    #    两个同 modid 的 jar 同时在 mods 里 → 游戏判定重复模组，起不来。
    #    （`.jar.disabled` 不会被游戏加载，属于老板自己的备份，不动它。）
    stale = sorted(p for p in glob.glob(os.path.join(DST_DIR, JAR_PREFIX + "*.jar"))
                   if os.path.basename(p) != os.path.basename(SRC))
    for p in stale:
        try:
            os.remove(p)
            print("清理旧版本：%s" % os.path.basename(p))
        except OSError as e:
            print("⚠ 删不掉旧版本 %s（游戏可能还开着）：%s" % (os.path.basename(p), e))
            return 2

    for attempt in range(1, 4):
        print()
        print("--- 第 %d 次安装 ---" % attempt)
        # 1) 删掉旧文件
        if os.path.exists(DST):
            try:
                os.remove(DST)
            except OSError as e:
                print("删不掉旧文件（游戏可能还开着）：%s" % e)
                return 2
        # 2) 复制
        shutil.copyfile(SRC, DST)
        time.sleep(0.15)                       # 等文件系统落盘
        # 3) 校验
        if not os.path.exists(DST):
            print("复制后文件不存在，重试")
            continue
        dst_size = os.path.getsize(DST)
        dst_hash = sha256(DST)
        if dst_size != src_size or dst_hash != src_hash:
            print("大小/哈希不一致（%d vs %d），重试" % (dst_size, src_size))
            continue
        ok, why = verify_zip(DST)
        if not ok:
            print("复制后 zip 损坏（%s），重试" % why)
            continue
        print("✓ 安装成功：%d 字节  时间=%s" % (dst_size, time.strftime("%Y/%m/%d %H:%M:%S")))
        print("  SHA256=%s" % dst_hash)
        print("  %s" % why)
        return 0

    print()
    print("✗ 重试 3 次仍未成功 —— 请先关闭游戏再试")
    return 1


if __name__ == "__main__":
    sys.exit(main())
