# -*- coding: utf-8 -*-
"""
发布包打包器：把发版需要的东西收进一个 zip。
    python tools/make_release.py

产物：release/deepspacedeployer-<版本>.zip，内含
  - 模组 jar（从 build/libs 取）
  - README.md / LICENSE
  - docs（玩法手册 / 设计决策 / 构建与测试指南）
  - art（封面 + 两张展示图，传到 CurseForge/Modrinth 用）
  - publish（上传清单、中英描述、更新日志）
"""
import glob
import os
import re
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RELEASE_DIR = os.path.join(PROJECT, "release")


def mod_version():
    props = os.path.join(PROJECT, "gradle.properties")
    with open(props, encoding="utf-8") as f:
        text = f.read()
    version = re.search(r"mod_version\s*=\s*(\S+)", text).group(1)
    mc = re.search(r"minecraft_version\s*=\s*(\S+)", text).group(1)
    return "%s+mc%s" % (version, mc)


def main():
    version = mod_version()
    jars = glob.glob(os.path.join(PROJECT, "build", "libs", "*.jar"))
    jars = [j for j in jars if "sources" not in os.path.basename(j)]
    if not jars:
        print("[FAIL] 没找到 jar，先跑 gradlew build")
        return 1
    jar = jars[0]

    os.makedirs(RELEASE_DIR, exist_ok=True)
    out = os.path.join(RELEASE_DIR, "deepspacedeployer-%s.zip" % version)
    if os.path.exists(out):
        os.remove(out)

    collected = []
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(jar, "mods/" + os.path.basename(jar))
        collected.append(os.path.basename(jar))

        for name in ("README.md", "LICENSE"):
            path = os.path.join(PROJECT, name)
            if os.path.exists(path):
                z.write(path, name)
                collected.append(name)

        for folder, prefix in (("docs", "docs"), ("publish", "publish"), ("art", "art")):
            base = os.path.join(PROJECT, folder)
            for path in sorted(glob.glob(os.path.join(base, "*"))):
                if os.path.isfile(path):
                    z.write(path, "%s/%s" % (prefix, os.path.basename(path)))
                    collected.append("%s/%s" % (prefix, os.path.basename(path)))

        # 附一份"先看这个"
        readme = (
            "Deep Space Deployer %s\n"
            "================================\n\n"
            "模组 jar 在 mods/ 里，丢进整合包的 mods 文件夹即可（需要 NeoForge 1.21.1）。\n"
            "推荐同时安装 Create；Create: Aeronautics / Sable 可选（影响物理结构功能）。\n\n"
            "第一次进游戏请执行 /dsd selftest 看自检报告。\n"
            "游戏内快速上手：/dsd guide\n\n"
            "上传到平台时看 publish/ 里的文件：\n"
            "  publish/发布清单.md      逐项填写指南\n"
            "  publish/描述-中文.md     CurseForge 描述\n"
            "  publish/description-en.md Modrinth 描述\n"
            "  publish/更新日志.md       更新日志\n"
            "  art/cover-512.png        项目图标\n"
            "  art/gallery-*.png        展示图\n"
        ) % version
        z.writestr("先看这个.txt", readme)
        collected.append("先看这个.txt")

    size = os.path.getsize(out) / 1024.0
    print("已生成发布包：%s（%.1f KB）" % (out, size))
    print("包含 %d 个文件：" % len(collected))
    for name in collected:
        print("   ", name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
