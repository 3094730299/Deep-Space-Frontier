# art 文件夹说明

这个文件夹放**美术素材**（不是游戏会加载的资源；游戏读的是
`src\main\resources\assets\deepspacedeployer\...`）。

| 子文件夹 / 文件 | 是什么 | 谁在用 |
|---|---|---|
| `cover-512.png`、`gallery-1-overview.png`、`gallery-2-variants.png` | 上传平台用的封面与展示图 | 发布清单 / README |
| `程序生成-备份\` | `tools\gen_textures.py` 生成的**旧版**物品贴图（被手绘定稿换下来的） | 只是备份，想退回程序生成版就用它们 |
| `手绘定稿\` | 老板提供的**手绘定稿**物品贴图（现在游戏里用的就是这几张） | 直接覆盖到 textures/item/ 即可换图 |

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
