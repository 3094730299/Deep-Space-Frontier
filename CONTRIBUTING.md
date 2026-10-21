# 参与开发

欢迎一起做！这份文档写给**第一次接触这个仓库**的人。

---

## 一、先跑起来（10 分钟）

```bash
# 1. 克隆
git clone <仓库地址>
cd <仓库目录>

# 2. 编译（首次约 7 分钟，要跑完整 NeoForm 管线）
gradlew.bat build        # Windows
./gradlew build          # Linux / macOS

# 3. 产物
build/libs/deepspacedeployer-0.1.20+mc1.21.1.jar
deepspacedeployer-0.1.20+mc1.21.1.jar          # ★ build 结束会自动复制到项目根目录
```

>
> **★ 铁律 16（老板 2026-10-21 定）**：**每次提交务必提升模组版本号**。
> 用 `python tools/bump_version.py` 自增（会同步文档里的版本引用），别手改、容易漏。
> **★ 铁律（老板 2026-10-18 定）**：**每次更新后，构建一份 jar 放到项目根目录**。
> `gradlew build` 结束时 `copyJarToRoot` 任务会自动做这件事（并清掉根目录里旧版本的 jar），
> 不需要手动 `Copy-Item`。根目录的 jar 不入库（`.gitignore` 的 `/*.jar`），只是方便随手取用。

**需要 JDK 21。** Gradle 用仓库自带的 wrapper，不用另外装。

把 jar 丢进整合包的 `mods/` 就能进游戏测。**推荐整合包**：
机械动力 6.0.10 + 航空学 1.3.2 + Sable 2.0.6 + GeckoLib 4.9.3
（后三个都是**可选**的，缺了也能进游戏，只是对应功能降级）。

---

## 二、提交前必须做的事

```bash
python tools/validate.py        # 工程侧自检（模型/贴图/语言/掉落表/结构/世界生成）
python tools/check_mod_ids.py   # 核对价目表里的物品 id 在整合包里真的存在
```

这两条**任一失败就别提 PR** —— 它们覆盖了过去踩过的大部分坑（黑紫方块、贴图错位、
缺失的 parent 模型、引用了不存在的物品 id 等）。

进游戏后也跑一下 `/dsd selftest`，它会一次性检查注册、价目表、蓝图、配置、声望、联动桥。

---

## 三、改代码前请先看设计决策

**[docs/设计决策.md](docs/设计决策.md) 是这个项目的"唯一真相来源"。**
里面每个数字（价格、倍率、手续费、解锁门槛）都是**确认过的设定**，不是随手写的。

**要改设定 → 先改那份文档，再改代码。**

尤其注意这两节（都是花了很大代价才踩通的）：

- **§2.48 / §2.49** —— 自定义建模 + 动画的完整配方，含 **9 个坑**：
  - `geo.json` 的 `format_version` 必须是 `1.12.0`（否则几何被静默拒绝加载，模型在但没动画）
  - **不要改** `texture_width/height`（那是 UV 坐标系，改了贴图全乱）
  - 渲染器里**不要**自己做平移/旋转（GeckoLib 已经做了，重复会偏移翻倍）
  - 方块必须 `RenderShape.INVISIBLE`，且**必须有 `getTicker`**

---

## 四、代码约定

| 项目 | 约定 |
|---|---|
| 语言 | 注释与文档用**中文**（这个项目的中文注释是有意保留的） |
| 包结构 | 按功能分包（`block/` `blockentity/` `economy/` `factory/` …），见 README 的目录结构 |
| 联动 | **一律走 `integration/` 里的反射桥** —— 直接 import 别的模组的类会让"没装那个模组就崩" |
| 可选依赖 | 用独立的 `Setup` 类判断（只碰 `ModList`，不引用对方类型），见 `UniversalTerminalGeoSetup` |
| 数据 | 商品、收购价、结构、世界生成**都走数据包**（`src/main/resources/data/`），不要硬编码 |
| 配置 | 数值放 `Config.java`，注释里写清"为什么是这个值" |

---

## 五、加一个新方块（常见的贡献）

以"再加一个装饰方块"为例：

```
1. 建模：Blockbench 里做模型 + 动画，用 GeckoLib 插件导出三个文件
     geo.json / animation.json / png
     ⚠️ 导出后把 geo.json 的 format_version 确认成 1.12.0

2. 资源放进：
     assets/deepspacedeployer/geo/block/<名>.geo.json
     assets/deepspacedeployer/animations/block/<名>.animation.json
     assets/deepspacedeployer/textures/block/<名>.png

3. Java 三个类（照抄现有实现）：
     方块实体  blockentity/<名>BlockEntity.java   implements GeoBlockEntity
     模型      client/gecko/<名>GeoModel.java     extends DefaultedBlockGeoModel
     渲染器    client/gecko/<名>GeoRenderer.java  extends GeoBlockRenderer（方法体空着）

4. 注册：ModBlocks / ModBlockEntities / ClientSetup（带"有没有 GeckoLib"判断）

5. 碰撞箱：python tools/gen_collision_shape.py 从几何算盒子，粘进方块类

6. 跑 validate.py + 进游戏看
```

**动画相关的坑**（血泪教训，别重犯）：
- 判断"动画播完了"→ **自己数 tick**，不要用 `hasAnimationFinished()`（它会误报 true）
- 判断"是否正在播"→ 不要用 `isPlayingTriggeredAnimation()`（只在 `triggerAnim()` 时为 true）
- `getTick()` 不要返回世界总时间（动画会被一步跳到结尾），用本地计数器
- 动画阶段**别放在方块实体的 ticker 里推进**（不保证每 tick 调用），放**渲染方法**里

---

## 六、报告问题

提 issue 时请附上：

```
1. 现象（越具体越好，比如"固定爪转进了底座里"比"模型不对"有用得多）
2. logs/latest.log 里含 deepspacedeployer 的那几段
3. 装了的模组版本（Create / 航空学 / Sable / GeckoLib）
```

> **实战经验**：这个项目大多数 bug 的答案**都在日志里**。
> 比如"模型显示但没动画"报的是
> `[GeckoLib WARN] Unsupported geometry json version ... Supported versions: 1.12.0` ——
> 一眼就能定位。所以**先看日志，再猜**。

---

## 七、分支与提交

```
主分支：main（保持可编译、可游戏内验证）
功能分支：feature/<简短描述>
修复分支：fix/<简短描述>
```

提交信息用中文或英文都行，说清**改了什么 + 为什么**。例：

```
修复收购投送机投送动画不播：动画阶段推进从方块实体 tick 挪到渲染器

方块实体的 getTicker 不保证每 tick 被调用，实测控制器被调了 1700+ 次
但阶段一次都没推进，动画卡在 STOPPED。渲染方法每帧都会执行，更可靠。
```

---

## 八、素材来源与授权

- **代码**：见 [LICENSE](LICENSE)
- **贴图 / 建模**：本仓库 `art/` 与 `src/main/resources/assets/` 下的素材
- **第三方**：`libs/geckolib-neoforge-1.21.1-4.9.3.jar` 是 GeckoLib 的官方发布件，遵循其自身授权
  仅用于编译（`compileOnly`），不打包进最终产物

**提交素材时请确认你有权使用它**（自己画的、或授权允许再分发的）。
