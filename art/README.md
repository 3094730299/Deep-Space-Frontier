# art 文件夹说明

这个文件夹放**美术素材**（不是游戏会加载的资源；游戏读的是
`src\main\resources\assets\deepspacedeployer\...`）。

| 子文件夹 / 文件 | 是什么 | 谁在用 |
|---|---|---|
| `cover-512.png`、`gallery-1-overview.png`、`gallery-2-variants.png` | 上传平台用的封面与展示图 | 发布清单 / README |
| `程序生成-备份\` | `tools\gen_textures.py` 生成的**旧版**物品贴图（被手绘定稿换下来的） | 只是备份，想退回程序生成版就用它们 |
| `手绘定稿\` | 老板提供的**手绘定稿**物品贴图（现在游戏里用的就是这几张） | 直接覆盖到 textures/item/ 即可换图 |

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

> ⚠️ **不要**用 `python tools\gen_textures.py` 去"重新生成"这三张 —— 脚本里已经把
> `bank_card.png`、`blueprint_voucher.png`、`tier_upgrade_kit.png` 加进白名单，
> 它会跳过这几张（只重写模型 JSON），免得把手绘图盖回程序生成版。
