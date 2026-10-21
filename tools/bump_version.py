#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按铁律 16 自增模组版本号，并同步文档里所有"当前版本"的引用。

用法：
    python tools/bump_version.py            # 补丁号 +1（0.1.11 → 0.1.12）
    python tools/bump_version.py 0.2.0      # 直接指定新版本
    python tools/bump_version.py --check    # 只看现在是多少（不改）

为什么要有这个脚本：
    "每次提交务必提升模组版本号"是老板 2026-10-21 定的铁律。
    手改容易漏（尤其散在十几个文档里的 `xxx-0.1.11+mc1.21.1.jar` 路径），
    漏了就会出现"jar 名没变但内容变了"，装机时分不清是哪个包。

它会做两件事：
    1. 改 gradle.properties 的 mod_version；
    2. 把所有文档/脚本里指向"当前版本"的路径一并改掉（历史条目不动）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 只改这些文件里的"当前版本"引用（历史叙述文件如更新日志的旧条目不动）
TARGET_FILES = [
    'README.md',
    'README-交接.md',
    'CONTRIBUTING.md',
    'docs/交接说明.md',
    'docs/构建与测试指南.md',
    'docs/设计决策.md',
    'publish/发布清单.md',
    'docs/开发文档/界面主题系统.md',
    'docs/开发文档/界面清单.md',
    'docs/开发文档/自定义主题开发文档.md',
]


def read_version():
    path = os.path.join(ROOT, 'gradle.properties')
    text = io.open(path, encoding='utf-8').read()
    m = re.search(r'^mod_version=(.+)$', text, re.MULTILINE)
    if not m:
        raise SystemExit('× gradle.properties 里找不到 mod_version')
    return m.group(1).strip()


def next_patch(version):
    parts = version.split('.')
    while len(parts) < 3:
        parts.append('0')
    try:
        parts[2] = str(int(parts[2]) + 1)
    except ValueError:
        raise SystemExit('× 版本号格式不认识：%s' % version)
    return '.'.join(parts[:3])


def write_version(old, new):
    path = os.path.join(ROOT, 'gradle.properties')
    text = io.open(path, encoding='utf-8').read()
    text = text.replace('mod_version=' + old, 'mod_version=' + new, 1)
    io.open(path, 'w', encoding='utf-8', newline='').write(text)

    touched = 0
    for rel in TARGET_FILES:
        full = os.path.join(ROOT, rel)
        if not os.path.isfile(full):
            continue
        body = io.open(full, encoding='utf-8').read()
        before = body
        # 形如 deepspacedeployer-0.1.11+mc1.21.1 / **0.1.11+mc1.21.1**
        body = body.replace('-%s+mc' % old, '-%s+mc' % new)
        body = body.replace('**%s+mc' % old, '**%s+mc' % new)
        body = body.replace('版本        %s+mc' % old, '版本        %s+mc' % new)
        body = body.replace('当前版本：**%s**' % old, '当前版本：**%s**' % new)
        body = body.replace('（当前 %s）' % old, '（当前 %s）' % new)
        body = body.replace('填版本号 `%s+mc' % old, '填版本号 `%s+mc' % new)
        body = body.replace('对应模组 %s+mc' % old, '对应模组 %s+mc' % new)
        body = body.replace('对应模组 **%s+mc' % old, '对应模组 **%s+mc' % new)
        if body != before:
            io.open(full, 'w', encoding='utf-8', newline='').write(body)
            touched += 1
            print('  ✓ %s' % rel)
    return touched


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    current = read_version()
    if '--check' in sys.argv:
        print('当前版本：%s' % current)
        return 0
    new = args[0] if args else next_patch(current)
    if new == current:
        raise SystemExit('× 新版本和当前一样（%s）' % current)
    print('版本号：%s → %s' % (current, new))
    touched = write_version(current, new)
    print('已同步 %d 个文档。' % touched)
    print('记得：改完跑 `gradlew build`（会按铁律 15 把 jar 复制到根目录）。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
