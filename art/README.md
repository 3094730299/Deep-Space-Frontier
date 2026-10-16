# art 文件夹说明

这个文件夹放**美术素材**（不是游戏会加载的资源；游戏读的是
`src\main\resources\assets\deepspacedeployer\...`）。

| 子文件夹 / 文件 | 是什么 | 谁在用 |
|---|---|---|
| `cover-512.png`、`gallery-1-overview.png`、`gallery-2-variants.png` | 上传平台用的封面与展示图 | 发布清单 / README |
| `程序生成-备份\` | `tools\gen_textures.py` 生成的**旧版**物品贴图（被手绘定稿换下来的） | 只是备份，想退回程序生成版就用它们 |
| `手绘定稿\` | 老板提供的**手绘定稿**物品贴图（现在游戏里用的就是这几张） | 直接覆盖到 textures/item/ 即可换图 |
| `建模计划\` | **老板的 Blockbench 建模源文件（.bbmodel）+ 导出中间件** —— 见下节 | 重新导入模型时用（`tools\import_*.py` 的源） |
| `模组贴图\` | 老板的**贴图源文件**（含"定图/预备"各版本） | 换图 / 出定稿时用 |
| `载具计划\` | 老板设计的**载具蓝图**（`.nbt`，星联动力基础小车 / 露营车）| 载具打印机用；也作云端备份 |
| `模组图标\` | **模组图标原图 + 处理产物**（老板 2026-10-17 给的那张） | `tools\process_theme_icon.py` 的源；见下节 |

---

## 模组图标（`art\模组图标\`）

老板给的原图有个坑：**背景是"画进图里的浅蓝棋盘格"**（PNG 没有 alpha 通道），
直接当图标用会是一块浅蓝色方块，所以要先抠掉。

```powershell
# 只看测量（不写文件）：抠掉多少像素、内容包围盒多大
python tools\process_theme_icon.py "art\模组图标\原图-631x629.png" --report

# 生成三个产物
python tools\process_theme_icon.py "art\模组图标\原图-631x629.png" --out "art\模组图标"
```

| 产物 | 用途 | 放到哪 |
|---|---|---|
| `theme_icon_transparent.png`（601×460） | 全尺寸透明版（存档 / 发布页大图） | 不用拷（`docs\logo-大图.png` 是它） |
| `deepspacedeployer_logo.png`（128×128） | **模组图标**（模组列表里那个） | `src\main\resources\` 根目录（`neoforge.mods.toml` 的 `logoFile` 指的就是它） |
| `theme_button.png`（48×48） | **背包界面那颗圆形主题按钮的图标** | `src\main\resources\assets\deepspacedeployer\textures\gui\` |

> ⚠️ 按钮贴图必须在 `textures/gui/` 下（界面贴图的规范位置；`block/` 是方块、`item/` 是物品）。
> 想换按钮图标就换那张 48×48 的 png，Java 代码不用动。
> 抠背景的判据只有一条"蓝通道比红通道高 60 以上"（背景 `B-R` 是 74~127，物体灰/白/橙约等于 0），
> 阈值都在脚本顶部，换图时按 `--report` 调。


## ★ 源文件备份（2026-10-16 加入）

老板桌面上这几个文件夹**原样复制**了一份到这里，作为**云端备份**：

| 仓库里 | 来自（老板桌面） | 内容 |
|---|---|---|
| `art\建模计划\` | `桌面\建模计划\` | **18 个 `.bbmodel`**（模板 / 半成品 / 完成品 / 备份）+ 导出的 geo、animation + 建模贴图，共 29 个文件 |
| `art\模组贴图\` | `桌面\正在开发的模组\模组贴图\` | 17 张贴图源文件（含 `定图\`、`定图\预备\`）|
| `art\载具计划\` | `桌面\载具计划\` | **2 个载具蓝图 `.nbt`**（星联动力基础小车、星联动力露营车），2026-10-16 加入 |

> **载具蓝图怎么用**：它们是 gzip 压缩的 NBT 结构文件（和 `examples\blueprints\` 里那两个同类）。
> 想在游戏里用，把 `.nbt` 复制到整合包实例的 `Sable-Schematics\` 文件夹
> （例如 `…\.minecraft\versions\孢子危机\Sable-Schematics\`），再用载具打印机读取。

**为什么放进来**：这些是**不可再生的原稿** —— 导出的 `geo/`、`textures/` 早就在仓库里，
但能继续编辑的 `.bbmodel` 之前**只存在于桌面上那一份**，那块硬盘坏了模型就真没了。

> ⚠️ 两点注意：
> 1. **桌面上的原件没有被移动或删除**，只是复制了一份进仓库；
> 2. 以后在桌面上改了模型，**要再复制一次到 `art\` 并提交**，否则仓库里留的是旧版：
>    `git add -A ; git commit -m "art: 同步建模源文件" ; git push`

**没放进来**的：`桌面\正在开发的模组\` 里的 3 个 jar
（`create_flight_control`、`impact_drill` ×2）—— 那是**别的模组**的编译产物，不是我们做的，
加起来 4.6 MB。需要的话说一声。

---

## ★ 隔离规则（老板 2026-10-15 要求："每个物品单独做一个隔离"）

**一句话：生成脚本默认只"补缺"，绝不动你已经有的文件。**

| 规则 | 效果 |
|---|---|
| ① `gen_textures.py` **默认不覆盖已存在的文件** | 你手改过的任何一张图/模型，跑脚本都不会被盖掉 |
| ② 想重新生成必须**显式** `--force` | 覆盖是有意识的行为，不会"顺手发生" |
| ③ 可以**只点名一个物品** | `python tools\gen_textures.py buyer_threshold_mid` 只碰这一个，其余一个字节都不读写 |
| ④ **`手绘定稿\` 里的文件名 = 永不覆盖清单** | 连 `--force` 都跳过（这份清单是自动扫描目录得到的，**往这里丢个图就自动受保护**） |
| ⑤ 跑完**按物品列出**写了什么、跳过什么 | 一眼确认"这次波及了哪些物品" |

常用命令：

```powershell
python tools\gen_textures.py                      # 补缺失的（安全：不覆盖任何已有文件）
python tools\gen_textures.py --list               # 只看会涉及哪些物品，不写文件
python tools\gen_textures.py buyer_threshold_mid  # 只处理这一个物品
python tools\gen_textures.py buyer_threshold_mid --force   # 明确要求重新生成它
```

> 想确认"我刚才那次改动到底碰了哪些文件"，最直接的办法是在项目根目录跑 `git status`
> —— 它会列得清清楚楚；配合"只点名一个物品"，就不会再出现"改 A 动了 B"。

## 手绘定稿的对应关系

| 这个文件 | 游戏里的资产 | 状态 |
|---|---|---|
| `bank_card.png` | `textures/item/bank_card.png`（深空银行卡） | ✅ 已装进工程 |
| `blueprint_voucher.png` | `textures/item/blueprint_voucher.png`（蓝图凭证） | ✅ 已装进工程 |
| `tier_upgrade_kit.png` | `textures/item/tier_upgrade_kit.png`（等级升级套件） | ✅ 已装进工程 |
| `预备-银行卡.png` | —— | ⏸ **待定**：另一版银行卡卡面（更素），暂时没启用 |
| `预备-损坏的蓝图凭证芯片.png` | —— | ⏸ **待定**：看起来是"损坏的凭证/芯片"，没有对应物品，等老板定用途 |

## 换图的正确姿势

1. 新图做成 **16×16 PNG（带透明通道）**；
2. 覆盖 `src\main\resources\assets\deepspacedeployer\textures\item\` 下的同名文件；
3. `gradlew build` 然后换 jar（或者开发环境 `runClient`）。

> ⚠️ **不要**为了"重新生成"去跑 `python tools\gen_textures.py --force` ——
> 现在脚本默认**不覆盖任何已存在的文件**，而且 `手绘定稿\` 里出现过的文件名
> （`bank_card.png`、`blueprint_voucher.png`、`tier_upgrade_kit.png`）
> **连 `--force` 都会跳过**。所以这几张手绘图是安全的，不会被盖回程序生成版。
