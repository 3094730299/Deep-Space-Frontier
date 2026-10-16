# 开发文档（技术向）

> 面向**改这个模组的人（包括 AI）**。给玩家看的文档在 `docs/` 的上一级：
> [玩法手册.md](../玩法手册.md)、[模组介绍.md](../模组介绍.md)。

---

## 这个目录放什么

**只有"改代码时才需要看"的东西放这里。** 玩家向 / 发布向的文档留在 `docs/` 根目录。

| 文档 | 内容 | 什么时候看 |
|---|---|---|
| [界面主题系统.md](界面主题系统.md) | HTML+CSS 自定义界面的**全部规则**：目录约定、支持的写法、数据绑定、离线预览、代码地图 | 改界面、加主题、动渲染器 |
| [../设计决策.md](../设计决策.md) | **项目的"唯一真相来源"**：所有已确认的设定 + 每一次踩坑的完整记录（当前 300+ 条） | **改任何设定之前** |
| [../构建与测试指南.md](../构建与测试指南.md) | 怎么编译、怎么进游戏、各阶段的测试清单（写给没有代码基础的人） | 第一次跑起来、要发版 |
| [../交接说明.md](../交接说明.md) | 交接摘要 | 刚接手这个项目 |

> **为什么 `设计决策.md` 没有搬进来**：它被 README / CONTRIBUTING / 交接文档等
> 7 处引用，搬动会改一堆链接，收益不大。等哪天要整理时一起搬。

---

## 两条必读的"铁律"

真正会让你出事故的规则，都汇总在
[../../README-交接.md](../../README-交接.md) 的**第零节（13 条铁律）**。
其中和开发最相关的两条：

1. **改完必须 `gradle clean build`**（只改资源也必须 clean）→ 否则 Gradle 报 UP-TO-DATE，资源没进 jar；
2. **装机必须用 `python tools/install.py`** → 裸 `Copy-Item` 会导致 jar 条目损坏、卡加载界面。

---

## 自检脚本（改完就跑）

```powershell
$env:PYTHONIOENCODING='utf-8'
python tools\validate.py               # 资源一致性：模型/贴图/语言/掉落表/标签/世界生成
python tools\check_mod_ids.py          # 物品 id 在整合包里是否真实存在
python tools\check_gecko_textures.py   # 贴图尺寸 vs geo 声明（黑紫问题）
python tools\check_gecko_animations.py # 动画关键帧规则（"模型在但一动不动"）
python tools\verify_readme_claims.py   # 交接文档里的技术断言是否还成立（49 条）
python tools\install.py                # 安全装机（SHA256 + 逐条目校验）
```

**改完必须提交到 git 仓库**（老板 2026-10-15 定的规矩）：

```powershell
git add -A
git commit -m "一句话说清这次改了什么"
```

> `tools/make_github_repo.py`（把项目导出到桌面副本）**已停用**，平时不要跑 ——
> 仓库就是项目根目录本身，不再维护第二份副本。

**红了就先修，不要往下做。**
