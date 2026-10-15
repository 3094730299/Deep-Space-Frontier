# -*- coding: utf-8 -*-
"""
把模组整理成「可以推到 GitHub 的仓库」放到桌面 深空铸造 文件夹里。

    python tools/make_github_repo.py

包含：源码、资源、工具脚本、文档、美术、发布材料、构建配置、gradle wrapper、LICENSE
排除：build/、.gradle/、run/ 等构建产物与缓存

⚠️ 只清理仓库目录里**由本脚本管理的部分**（DIRS / FILES / release），
   其余文件（README / CONTRIBUTING 等手写的）保持不动。
"""
import io
import os
import shutil

SRC = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
DST = r"C:\Users\30947\Desktop\深空铸造"

# 要带的目录（整个）
DIRS = ["src", "tools", "docs", "art", "publish", "examples", "gradle", "libs"]
# 要带的根文件
FILES = ["build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
         "LICENSE", ".gitignore", ".gitattributes",
         # 手写的说明文档（README-交接.md 是给接手者/AI 的自述文档，必须同步）
         "README.md", "README-交接.md", "CONTRIBUTING.md"]

# 不带的东西
SKIP_DIRS = {"build", ".gradle", "run", "runs", "logs", "__pycache__", ".idea", ".vscode",
             "moddev", "artifacts", "tmp", "classes", "generated", "reports"}
SKIP_EXT = {".class", ".pyc", ".jar.orig"}
SKIP_NAMES = {"_collision_boxes.txt"}
# 超过这个大小的单个文件不复制（避免把构建产物带进仓库）
MAX_FILE_MB = 5.0


def copy_tree(src, dst):
    copied = 0
    skipped = 0
    for root, dirs, files in os.walk(src):
        # 跳过构建/缓存目录（按名字，并排除软链接/junction，避免绕回 build/）
        safe = []
        for d in dirs:
            p = os.path.join(root, d)
            if d in SKIP_DIRS or os.path.islink(p):
                continue
            safe.append(d)
        dirs[:] = safe

        rel = os.path.relpath(root, src)
        target = dst if rel == "." else os.path.join(dst, rel)
        if SKIP_DIRS.intersection(rel.split(os.sep)):
            continue
        os.makedirs(target, exist_ok=True)
        for f in files:
            p = os.path.join(root, f)
            if os.path.splitext(f)[1] in SKIP_EXT or f in SKIP_NAMES:
                skipped += 1
                continue
            try:
                if os.path.getsize(p) > MAX_FILE_MB * 1024 * 1024:
                    skipped += 1
                    continue
            except OSError:
                skipped += 1
                continue
            shutil.copyfile(p, os.path.join(target, f))
            copied += 1
    return copied, skipped


def main():
    print("目标：%s" % DST)
    os.makedirs(DST, exist_ok=True)

    # 先清掉上次同步留下的 build 之类（本脚本不管的目录会留着）
    for junk in ("build", ".gradle", "run", "runs"):
        p = os.path.join(DST, junk)
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
            print("  已清理 %s" % junk)

    total = 0
    for d in DIRS:
        s = os.path.join(SRC, d)
        if not os.path.isdir(s):
            print("  · 跳过不存在的目录 %s" % d)
            continue
        # 目标先清空，保证不残留旧文件
        t = os.path.join(DST, d)
        if os.path.isdir(t):
            shutil.rmtree(t, ignore_errors=True)
        n, sk = copy_tree(s, t)
        total += n
        print("  %-12s %4d 个文件%s" % (d, n, ("（跳过 %d）" % sk) if sk else ""))

    for f in FILES:
        s = os.path.join(SRC, f)
        if os.path.isfile(s):
            shutil.copyfile(s, os.path.join(DST, f))
            total += 1
        else:
            print("  · 缺少根文件 %s" % f)

    # 发布包（可选）
    rel = os.path.join(SRC, "release")
    if os.path.isdir(rel):
        t = os.path.join(DST, "release")
        if os.path.isdir(t):
            shutil.rmtree(t, ignore_errors=True)
        os.makedirs(t, exist_ok=True)
        n = 0
        for f in os.listdir(rel):
            shutil.copyfile(os.path.join(rel, f), os.path.join(t, f))
            n += 1
            total += 1
        print("  %-12s %4d 个文件" % ("release", n))

    print()
    size = 0
    count = 0
    for root, dirs, files in os.walk(DST):
        for f in files:
            size += os.path.getsize(os.path.join(root, f))
            count += 1
    print("共复制 %d 个文件" % total)
    print("目标目录：%d 个文件，%.1f MB" % (count, size / 1024 / 1024))


if __name__ == "__main__":
    main()
