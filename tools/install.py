# -*- coding: utf-8 -*-
"""
安全安装：把构建产物复制到整合包 mods 目录，并**校验到字节级一致**。

    python tools/install.py

为什么需要它：以前直接 Copy-Item，出现过"复制不完整"→ jar 里的 zip 条目损坏
→ 游戏读世界生成数据时抛 ZipException → **卡在加载界面 / 旧存档进不去**。

这个脚本：
  1) 复制前先删掉旧文件（避免占用/半覆盖）
  2) 复制后比对大小 + SHA256
  3) 逐个 zip 条目解压校验（testzip + 真读一遍）
  4) 任何一步不对就重试，重试 3 次仍失败就报错退出
"""
import hashlib
import os
import shutil
import sys
import time
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "build", "libs", "deepspacedeployer-0.1.0+mc1.21.1.jar")
DST_DIR = r"C:\Users\30947\Desktop\.minecraft\versions\孢子危机\mods"
DST = os.path.join(DST_DIR, os.path.basename(SRC))


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
    if not os.path.exists(SRC):
        print("找不到构建产物：%s" % SRC)
        print("先跑一次 gradle build")
        return 1

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
