# 深空铸造体（Deep Space Deployer）— 交接自述文档

> **本文档的用途**：让**接手这个项目的 AI 或人**在 5 分钟内完全接手，不需要重新踩一遍坑。
>
> **可靠性约定**（非常重要）：
> - 标注 ✅ 的 = **本会话实际执行并验证过**（有命令输出为证）
> - 标注 ⚠️ 的 = **未验证 / 待实机确认**
> - 标注 ❌ 的 = **已知失败的做法，不要重试**
>
> **本文档里的关键技术断言已被脚本验证**：跑
> `python tools/verify_readme_claims.py` → **64 条全部符合 ✓**
> （它逐条检查本文档声称的代码结构是否真的还在。改完代码后请重跑，红了就更新本文档。）
>
> 最后更新：2026-10-18（对应模组 **0.1.23+mc1.21.1**）
> **0.1.4**：载具打印机以前只读 `Sable-Schematics`，玩家放在 **`schematics`**（机械动力图纸夹）
> 里的载具看不到 —— 现在 `USER_DIRS` 数组里的文件夹都会扫（见 §7.8）。
> **本轮（0.1.3）改动：收购投送机「为什么不工作」诊断** ——
> 收购循环的 5 道关卡（没插卡 / 卡未绑定 / 自动收购关 / 头顶被挡 / 没有可收的东西）
> 原先**全部静默失败**，界面和日志都不吭声，这就是老板说的"新存档里无论如何都不工作"。
> 现在：原因码 `MachineMenu.DATA_IDLE_REASON`（0=正常）+ **界面一行提示**（原生界面画在 y=78；
> 主题用 `buyer.html` 的 `data-if="未工作"`）+ **日志** `[深空铸造体][收购诊断]`（只在原因变化时写）。
> 详见 §7.7 与 `publish/更新日志.md` 的 0.1.3 条目。
>
> **上一轮（0.1.1 / 0.1.2）改动（界面主题 / 内置 WebView 接进游戏）**：
> ① **渲染内核接进游戏**：新增 `ui/webview` 包（`WebViewScreen` 宿主 / `GuiCanvas` 游戏内画布 /
> `WebViewManager` 总开关 / `ThemeChoiceScreen` 主题菜单）；此前 `ensureDefaultTheme()` **没人调用**、
> `theme/default/` 一直没落到磁盘上的问题**已修**（见 §5.2 第 6 项）。
> ② **一个界面一个 html**：十个默认主题文件写进 `assets/deepspacedeployer/theme/default/`，
> 界面清单见 [docs/开发文档/界面清单.md](docs/开发文档/界面清单.md)。
> ③ **主题入口在背包界面**：按 **E** 打开背包，**屏幕底部居中**有一颗**圆形「主题」按钮**
> （**左键**点开主题选择；**右键长按拖拽**慢慢挪；按**「移动界面主题按钮」键**（默认鼠标中键）
> 把它一把搬到鼠标位置；位置都记进配置）。
> ⚠️ 默认放底部居中是有原因的：左上角会被 FTB 这类模组的按钮盖住（老板反馈过"四个角都没有按钮"）。
> ⚠️ 原来放在 ESC 菜单上会"点了没反应 / 要先继续游戏才弹" —— 暂停菜单生命周期太脆，已挪走。
> ④ **不用开游戏就能验主题**：`gradlew checkThemes` / `renderThemes` / `dumpTheme`。
> ⑤ 修掉两个**布局引擎 bug**（固定高度容器按内容高度占位 → 溢出；根容器不受视口约束 → 假溢出）。
> ⑥ 新增 [docs/开发文档/自定义主题开发文档.md](docs/开发文档/自定义主题开发文档.md)。

---

## 零、先读这一节：16 条铁律（违反必出事故）

这十五条都是**实际踩出来的**（最后一条是老板 2026-10-18 亲口定的规矩）。

| # | 铁律 | 违反后果 |
|---|---|---|
| 1 | **只改资源也必须 `gradle clean build`**，不能只 `build` | Gradle 报 `UP-TO-DATE`，资源没进 jar，游戏里看不到改动 |
| 2 | **装机必须用 `python tools/install.py`**，不要裸 `Copy-Item` | 复制不完整 → jar 内 zip 条目损坏 → **世界生成注册表加载失败 → 卡在加载界面**（真发生过）|
| 3 | **`geo.json` 的 `texture_width/height` 必须和 PNG 尺寸一致** | 不一致 → **黑紫格**（贴图读取失败）|
| 4 | **改贴图尺寸要用 `Image.NEAREST`（最近邻）**，不能用 LANCZOS | LANCZOS 会**把像素画糊掉**（真发生过）|
| 5 | **`format_version` 必须是 `1.12.0`** | 更高版本 → GeckoLib 静默拒绝几何 → **模型显示但完全无动画** |
| 6 | **一个方块实体类型只能注册一个渲染器** | 注册第二个会**覆盖第一个** → 两个方块都显示成同一个模型（真发生过）|
| 7 | **动画阶段是纯客户端状态，存 NBT 必须配套"读回 + 写回"** | 只存不读或只读不写 → 阶段被同步包反复重置 → **动画反复重播** |
| 8 | **不要用"格子空了"检测卖出** | 玩家自己拿走物品也会触发 → 误播动画 |
| 9 | **`GeoBlockRenderer` 自动做 `translate(0.5, 0, 0.5)`**，自己不要再平移 | 位置偏移翻倍 |
| 10 | **改动默认只留在本地，不要自己往仓库提交**；**等老板明确说「提交到仓库」时才 `git add -A && git commit && git push`** | 老板 2026-10-17 改的规矩。以前是"每轮改完自动提交+推送"，他不希望仓库里堆着还没验收的半成品（也不想和合伙人的分支互相添乱）。正确做法：改动先攒在本地、**照常装机 + 跑自检**，等他发话再一次性提交，提交信息里写清"这一批都改了什么"。另：只认 git 仓库，不再同步桌面副本（旧规矩见 §2 的说明）|
| 11 | **动画 JSON 里 `"0.0"` 关键帧必须是该通道的第一项**（GeckoLib **不排序**，按书写顺序读） | 否则抛 `Invalid keyframe data - multiple starting keyframes?0.0` → **整份 animation.json 作废**，同文件里**所有**动画一起失效（模型还在，但一动不动）。**改完动画必须跑 `python tools/check_gecko_animations.py`** |
| 12 | **重启动画只用 `AnimationController.forceAnimationReset()`**；**绝不要**注册 `.triggerableAnim()` / 调 `tryTriggerAnimation()` | 触发式动画一播完，GeckoLib 会置 `needsAnimationReload=true` **再回头问状态处理器**；相位还没结束时状态处理器又返回同一动画 → 动画被**从头重建又播一遍**。现象：**"播完后又重播一次 / 播到一半重置"**（真发生过，就是这个） |
| 13 | **geo 声明是"UV 坐标系"，PNG 尺寸必须是它的【整数倍】—— 声明绝不能改，只能改 PNG** | ① **改声明**（64→128）→ UV 空间变小、采样点整体错位 → **整片花屏**（真发生过，2026-10-15 通用终端）；② 为了"让声明对上"**把原图缩小** → 分辨率白丢（299 色→138 色，贴图变糊）。**正确做法：声明一个字不动，PNG 用高分辨率的那份（1×、2× 都合法）**。字节码依据：`GeoQuad.build` 里 `normalizedU = uv / texture_width`。改完跑 `python tools/check_gecko_textures.py` |
| 14 | **资源按物品隔离：生成脚本默认只补缺、绝不覆盖已有文件；改一个物品只点名它自己** | `gen_textures.py` 全量是 **144 张方块贴图 + 18 模型 + 18 方块状态**（老板原话："改一个物品总会波及其他物品"）。规则：默认**不覆盖已存在**（要重生成必须显式 `--force`）；**只处理点名的物品**（`gen_textures.py buyer_threshold_mid`）；**`art/手绘定稿/` 里的文件名连 `--force` 都跳过**（清单自动扫目录，丢个图进去就受保护）。`--list` 可先看会涉及哪些物品。跑完用 `git status` 确认波及范围 |
| **15** | **★ 每次更新后，必须构建一份 jar 放到【项目根目录】**（老板 2026-10-18 定） | 不这么做，装机/发人时还要去 `build/libs` 里翻，很容易**拿错版本**（旧 jar 装了半小时才发现的事真发生过）。**已自动化**：`gradlew build` 结束时 `copyJarToRoot` 自动把 `deepspacedeployer-<版本>+mc1.21.1.jar` 复制到项目根，并清掉根目录里旧版本的 jar；也可以单独跑 `gradlew copyJarToRoot`。根目录的 jar **不入库**（`.gitignore` 里的 `/*.jar`）|
| **16** | **★ 每次提交务必提升模组版本号**（老板 2026-10-21 定） | 版本号不变 = jar 名字不变，装机/发人时分不清哪个是新的（"明明重装了却没变化"真发生过）。**用工具，别手改**：`python tools/bump_version.py`（补丁号 +1，并同步所有文档里的当前版本引用）；`python tools/verify_readme_claims.py` 有一条断言盯着"最近的提交有没有升版本号"（合并提交除外）|

---

## 一、这是什么项目

| 项 | 值 |
|---|---|
| 名称 | 深空铸造体 / Deep Space Deployer |
| 类型 | Minecraft **1.21.1 NeoForge** 模组 |
| 定位 | 机械动力（Create）+ 航空学（Aeronautics/Sable）的**经济 + 造船**扩展 |
| 前置 | NeoForge 21.1.248；可选：**GeckoLib 4.9.3**（没装也能跑，只是自定义模型不显示）|
| 语言 | Java 21 |
| 规模 | **112 个 Java 文件**（含工具类）；467+ 个资源文件（`gradlew checkThemes` 会验主题）|
| 当前版本 | **0.1.23+mc1.21.1**（新增内置 WebView 界面主题）|

### 核心玩法

六家公司（星联动力联盟 / 低语环带科技 / 断锷军械财团 / 掠影推进公司 / 磐基重工工业 / 阈限实验集团），
玩家用银行卡在四类机器之间流转材料、余额、蓝图，最终**一键把蓝图组装成物理载具**。

| 机器 | 中文名 | 作用 | 渲染方式 |
|---|---|---|---|
| `factory_terminal` | 工厂终端 | 商店：买东西、资金转化 | 原版方块模型 |
| `buyer` | **收购投送机** | 把材料卖成公司余额（**头顶有方块就不工作**，见 §7.5）| GeckoLib（有动画）|
| `factory_core` | **载具打印机** | 把蓝图组装成物理结构 | GeckoLib（静止模型，**3×2×2 多方块**）|
| `market` | 寄售市场 | 玩家之间挂单买卖 | 原版方块模型 |
| `universal_terminal` | 通用终端 | 纯装饰 | GeckoLib（有动画）|
| `printer_part` | 载具打印机结构件 | 多方块的 11 个填充格 | 不渲染（可见性交给控制器）|

---

## 二、环境与路径（这台机器上的确切值）

```
项目根目录   C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer
git 仓库     就是项目根目录本身（.git 在里面，分支 main）
             远程 origin = https://github.com/3094730299/Deep-Space-Frontier.git（**私有仓库**）
             ⚠️ 2026-10-15 起：**改动只提交这个仓库**，不再往桌面副本同步
             ⚠️ 这台机器 github.com 被 Steam++/Watt Toolkit 代理到 127.0.0.1，
                浏览器正常，git 也能用（已实测推送成功）；不要以为"DNS 坏了"去乱改 hosts
桌面副本     C:\Users\30947\Desktop\深空铸造   ← **已停用**（旧规矩遗留；别再去同步它）
整合包版本   C:\Users\30947\Desktop\.minecraft\versions\孢子危机
装机目录     …\versions\孢子危机\mods\deepspacedeployer-0.1.23+mc1.21.1.jar

Java        C:\Program Files\Java\jdk-21.0.10          （必须设 JAVA_HOME）
Gradle      C:\Users\30947\Documents\deepseek-harness\default-workspace\.tools\gradle-8.12\bin\gradle.bat
Python      C:\Users\30947\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe
            （**每次调用前设 $env:PYTHONIOENCODING='utf-8'**，否则中文输出会报错）
建模源文件   C:\Users\30947\Desktop\建模计划\
            （模板\ = bbmodel；半成品\静止建模\ 和 半成品\动态建模\ = 导出的 geo/animation；建模贴图\ = PNG）
```

> ⚠️ **`maven.neoforged.net` 在这台机器上 DNS 被污染**。`build.gradle` 里已把仓库改写成
> `neoforged.forgecdn.net`。**不要改回去**。

> ⚠️ **Gradle 必须能写 `C:\Users\30947\.gradle`**（要释放并加载原生库 `native-platform.dll`、
> 写依赖缓存）。若被限制成"只能写项目目录"，报错是
> `Could not initialize native services / Failed to load native library 'native-platform.dll'`。
> **这不是代码问题**，放开该路径的写权限即可 —— 别去改 `build.gradle`。

---

## 三、标准工作流（照抄即可）

### 3.1 改代码 / 资源后

```powershell
$py='C:\Users\30947\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$proj='C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer'
Set-Location $proj
$env:PYTHONIOENCODING='utf-8'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'

# ① 必须 clean build（改资源也一样）
& 'C:\Users\30947\Documents\deepseek-harness\default-workspace\.tools\gradle-8.12\bin\gradle.bat' clean build --console=plain

# ② 工程自检（模型/贴图/语言/掉落表/标签/机器注册/世界生成）
& $py tools\validate.py

# ③ id 校验（商品与蓝图材料的物品是否真实存在于整合包）
& $py tools\check_mod_ids.py

# ④ 安全装机（复制 + SHA256 比对 + 逐条目解压校验，失败自动重试）
& $py tools\install.py

# ⑤ 提交到 git 仓库（老板要求每次必做）
git add -A
git commit -m "一句话说清这次改了什么"
```

### 3.2 导入老板新做的模型

```powershell
# 静态模型（无动画）
& $py tools\import_printer_model.py       # 载具打印机：geo + 六公司贴图 + 图标 + 对比图
# 或通用做法：
& $py tools\import_buyer_model.py         # 收购投送机
& $py tools\import_new_terminal_model.py  # 通用终端

# 导入后必须跑：
& $py tools\check_gecko_textures.py        # 检查贴图尺寸 vs geo 声明
& $py tools\check_gecko_textures.py --fix  # 不一致就自动修（NEAREST 缩放）
& $py tools\check_gecko_animations.py      # 检查动画关键帧（"0.0" 必须第一项）
& $py tools\check_gecko_animations.py --fix # 不合法就重排（只调顺序、不改数值）
```

### 3.3 从建模算出碰撞箱

```powershell
# 逐层扫 geo.json → 生成 VoxelShape Java 片段（输出到 tools\_machine_shapes.txt）
& $py tools\gen_machine_shapes.py
```

---

## 四、目录结构

```
src\main\java\com\deepspacedeployer\
  Company.java  Config.java  Tier.java  DeepSpaceDeployer.java
  block\          MachineBlock.java（四种机器共用） PrinterPartBlock.java（多方块结构件） UniversalTerminalBlock.java
  blockentity\    MachineBlockEntity.java（★核心，约 2500 行） MachineKind.java UniversalTerminalBlockEntity.java
  client\         12 个界面类 + ClientSetup.java（渲染器注册入口）
  client\gecko\   MachineGeoModel.java MachineGeoRenderer.java（★合并渲染器）
                  UniversalTerminalGeoModel/GeoRenderer/GeoSetup.java
  multiblock\     PrinterStructure.java（★12 格几何映射）
  factory\        蓝图解析与组装（BlueprintLibrary / PhysicsBlueprintLibrary / ...）
  integration\    联动桥（反射，不编译期依赖）：CreativeCrateBridge（创造板条箱）
                  SableBridge  DissolveBridge  StaticToggleBridge  CreateClipboardBridge  IntegrationProbe
  economy\        银行卡 / 余额 / 声望 / 凭证
  market\         寄售市场
  shop\           价目表
  menu\ net\ registry\ item\ editor\ diagnostics\

src\main\resources\assets\deepspacedeployer\
  geo\block\        buyer.geo.json  factory_core.geo.json  universal_terminal.geo.json
  animations\block\ buyer.animation.json  universal_terminal.animation.json
  textures\block\   buyer_<公司>.png  factory_core_<公司>.png  universal_terminal.png …
  blockstates\ models\ lang\
src\main\resources\data\deepspacedeployer\
  worldgen\（18 结构 + 18 拼图池 + 3 规则 + 3 标签）  loot_table\  tags\  shop\  structures\

tools\   40 个 Python 脚本（见第六节）
docs\    设计决策.md（★77KB，最有价值） 模组介绍 玩法手册 建模清单 开发进度_B站 …
```

---

## 五、当前真实状态（诚实版）

### 5.1 已完成并验证 ✅

| 项 | 验证方式 |
|---|---|
| 编译通过 | `gradle clean build` BUILD SUCCESSFUL |
| 资源一致性 | `tools\validate.py` 全过（6 个方块状态文件、中英各 300 条语言键、19 份掉落表、6 个工具标签）|
| 物品 id 真实性 | `tools\check_mod_ids.py` 全过（读整合包 194 个 jar）|
| jar 完整性 | `tools\install.py` 逐条目解压校验（624 条目，含 48 个 worldgen 条目）|
| 六公司 × 四机器外观 | 19 个方块物品变体全部注册 |
| 收购投送机「投送」动画 | **实机确认可用**（老板确认过"完美，成功了"）|
| 收购节奏 = 动画握手 | 服务端收一轮 → 等客户端回执 → 再收下一轮 |
| **「放置」动画被 GeckoLib 拒收的真凶** | 复现出 GeckoLib 原话 `JsonParseException: Invalid keyframe data - multiple starting keyframes?0.0`；修好后 `tools/check_gecko_animations.py` 全绿、jar 内 12 条通道逐条复核 OK |
| **「播两次」的真凶** | 字节码确认 `handleAnimationState` 在触发式动画播完时置 `needsAnimationReload=true` 并**再问一次状态处理器** → 同一动画被重建重播；改用 `forceAnimationReset()` 已修 |
| **载具打印机「12 格缺一不放」** | 放置前先查 `PrinterPartBlock.canPlaceAll(...)`，不通过返回 `null`（原版直接 FAIL、无提示）；已加 2 条断言，共 42 条全绿 |
| **投送机「顶上有方块不工作」** | 自动收购 `processBuyerCycle()` 与手动 `sellAllSlots()` 两处拦截 + 界面红字提示 + 中英文案；判定 = 从上方**一路扫到世界顶部**，「占满整格」才算挡（火把/告示牌/雪片不挡），带 0.5 秒缓存；已加 4 条断言，共 58 条全绿 |
| **通用终端贴图恢复 128×128** | 颜色数 138 → **299**；`check_gecko_textures.py` 报 128/128 一致；**jar 内**逐条复核贴图尺寸与 geo 声明一致；断言里的期望值也从 64 改成 128 |
| **资源按物品隔离**（老板要求） | `gen_textures.py` 默认**不覆盖已存在文件**、支持**只点名一个物品**、`art/手绘定稿/` 自动成为**永不覆盖清单**；实测默认跑一遍**写入 0 个文件**、`git status` 无变化；已加 3 条断言（共 58 条）|

### 5.2 进行中 / 待老板实机确认 ⚠️

| # | 问题 | 当前做法 | 怎么确认 |
|---|---|---|---|
| 1 | **收购投送机「放置」动画不播** —— **真凶已找到并修好（2026-10-15 18:07），等实机确认** | **根因不在 Java，在动画文件本身**：`buyer.animation.json` 里 `固定杆`/`固定杆2`/`固定杆3`/`固定杆4` 这 4 条 `position` 通道的 `"0.0"` 被写在**最后一项**（顺序 `0.3333, 0.5833, 0.0`）→ GeckoLib 直接拒收**整份文件** → `放置` 和 `投送` **一起失效**。已用 `check_gecko_animations.py --fix` 按键名升序重排，**数值一个都没动**（用 JSON 语义比对证明前后完全一致）。Java 侧那些兜底（首次渲染兜底 / 握手 / 诊断）都保留 | 老板放一台**新的**收购投送机：应看到**大圆筒、小圆筒从上方降下来**，四根固定杆收拢；收购时「投送」上下点一下。**若还是一动不动**，看日志有没有 `Unable to parse animation` |
| 2 | **动画"播两次 / 播到一半重置重播"** —— **真凶已找到并修好（2026-10-15 18:21），等实机确认** | 根因是**用了 GeckoLib 的"触发式动画"**（`tryTriggerAnimation`）：动画一播完，GeckoLib 就置 `needsAnimationReload=true` 并**再回头问一次状态处理器**；此时相位还没结束（「放置」相位 800ms > 动画 750ms），状态处理器返回同一动画 → `setAnimation` 不早退 → **从头重建、又播一遍**。已改成 `AnimationController.forceAnimationReset()`（只置位、由状态处理器自己重建一次），并**删掉 `.triggerableAnim()` 注册**。详见铁律 12、§7.1 | 连放几台机器 + 连续收购几轮：每台**只应看到一次**「放置」，每轮**只应看到一次**「投送」，中间不再有"跳回开头" |
| 3 | **通用终端贴图** —— **已查明并修好（2026-10-15 21:55）** | 老板原图是 **128×128（299 色）**，但为了让 geo 里 64 的声明"对上"，之前把**整张图 NEAREST 缩成了 64×64（只剩 138 色）** → 现象就是老板说的**"糊 / 糙 / 像素块很大"**。已改成 **PNG 恢复 128×128（逐像素等于原图、零重采样）+ geo 声明同步 128×128**；jar 内已复核两者一致 | 重启游戏看通用终端：细节应明显变清楚，**贴图位置不应有任何变化**（采样区域归一化后没变）|
| 4 | **创造板条箱加成吃不到** | 判定链已确认正常（配置开、桥接可用）；已加 `[创造板条箱]` 逐步诊断 | **关键**：创造板条箱必须**设了过滤物品**才算数；放机器旁 5 格内再开界面，看诊断日志 |
| 5 | **载具打印机模型对位** | 老板最新要求"以碰撞箱为基准中心对齐" → 已把模型中心对齐到碰撞箱中心（X1.5 / Z1.0）| 看模型是否和碰撞箱重合 |
| 6 | **界面主题系统（HTML+CSS 自定义界面 / 内置 WebView）** —— ⏸ 老板 2026-10-15 暂停后 **2026-10-16 已接进游戏** ✅ | **渲染内核 + 游戏内宿主全部就位**：`ui/webview` 包（宿主/画布/总开关/主题选择界面）+ 十个默认主题 html + 背包界面里的圆形 `ThemeButton`（左键开、右键长按拖、键位「移动界面主题按钮」默认中键、位置存 `ui.theme_button_x/y`；**生存和创造背包都挂**）。**0.1.6 修掉一个致命渲染 bug**：绘制时没把"这一帧的画布"交给渲染器 → 所有主题界面只有一层灰、什么都不显示（离线预览看不出来，已加断言盯住）。**0.1.11 修掉错位**：`ThemeSlotLayout` 按 `data-slot` 标记摆原版物品格（以前 `markers()` 没人调用，槽位一直停在硬编码坐标）。**主题选择界面是资源包式的原生实现**：左右两栏（可用/已启用）、可同时启用多套、每套显示图标/名称/版本/作者/介绍（`index.json` 的 `icon` 字段）、底部「高级界面设置」可按界面指定主题。**不用开游戏就能验**：`gradlew checkThemes`（逐个界面加载+布局）、`gradlew renderThemes`（出图）。架构选型与全部写法见 [docs/开发文档/自定义主题开发文档.md](docs/开发文档/自定义主题开发文档.md) | **待确认**：按 E 点主题按钮 → 两栏列表、启用禁用、高级设置；进机器界面看**物品格是否和主题框对齐**（0.1.11 的重点） |
| **7** | **UI 编辑器（待办，分步做）** 🚧 | **第 1~2 步已完成（0.1.11）**：`ThemeEditorScreen` —— 三栏（目录树 / 预览 / 文件信息）、`..` 返回上级、右键复制/剪切/粘贴/重命名/删除、`.json` 文本预览、图片预览、`.html` 直接渲染；**文本可编辑并保存**（`ThemeTextBuffer`：光标/方向键/回车/退格，Ctrl+S 保存，保存后当前主题的界面自动重载）。**后续步骤**：① 组件库（文本框 / 按钮 / 物品框 / 图片）+ 拖入画布；② 选中出属性面板（大小/位置/内容/样式/图层）+ 拉伸改大小；③ 图层（背景 -1，其余可自定义）；④ 修改资源（系统文件选择器 + 重命名 + 复制到主题根）；⑤ **逻辑编排器（参考 Scratch）** —— 老板 2026-10-19 明确列入待办 | 按 E → 主题按钮 → 选中一个包 → 「编辑…」 |

### 5.3 已知的坑（不要重试） ❌

| 做法 | 为什么不行 |
|---|---|
| 用 `AnimationController.hasAnimationFinished()` 判阶段 | 它在动画**刚开始**时就返回 `true`（队列未填充）|
| 用 `isPlayingTriggeredAnimation()` 判阶段 | 只对 `triggerAnim()` 触发的动画为真；状态处理器驱动的永远是 `false` |
| 用游戏 tick 计数推进阶段 | 方块实体的 `getTicker` 不保证每 tick 调用；渲染帧率远高于 20，节流会丢帧 |
| `getTick()` 返回 `level.getGameTime()` | 老存档几万 tick → GeckoLib 首帧算出巨大 delta → **动画一步跳到结尾** |
| 用 `triggerAnim(controller, anim)`（两参数版）重启动画 | 看字节码：它走**服务端分支**，客户端调用等于没做（单参数版又写死 `getManagerForId(0L)`，和渲染器的 `instanceId` 不是同一个管理器）→ **正确做法见 `forceRestart()`** |
| 用「格子空了」检测卖出 | 玩家自己拿走也会触发 |
| 分别注册 `BuyerGeoRenderer` + `CoreGeoRenderer` | 两者共用方块实体类型 `MACHINE`，后者覆盖前者 |
| 用 LANCZOS 缩放像素画贴图 | 会糊 → 用 `Image.NEAREST` |
| 裸 `Copy-Item` 装机 | 复制不完整 → zip 损坏 → 卡加载界面 |
| **以为动画文件的键顺序无所谓** | GeckoLib **不排序**、按 JSON 书写顺序读；`"0.0"` 不在第一项就抛异常，而且**整份文件作废**（真发生过：「放置」白查了一整轮，一直在改 Java）|
| **只在日志里搜 `GeckoLib.*WARN`** | 关键帧错误的级别是 **ERROR**，且夹在别的模组几千行报错里 → 要专门搜 `Unable to parse animation`（本项目 §5.2 第 1 项就是这么漏掉的）|
| 以为"某个动画坏了"只影响它自己 | GeckoLib 是**整份文件**一起 load/一起废 —— 一个通道写错，同文件所有动画全没了 |
| **用 `.triggerableAnim()` + `tryTriggerAnimation()` 重启动画** | 触发式动画播完会置 `needsAnimationReload=true` 并**再问一次状态处理器** → 同一动画被重建、**又播一遍**（"播两次"的元凶）。改用 `forceAnimationReset()` |
| 以为"状态处理器每帧返回同一动画"会重启动画 | 不会 —— `setAnimation` 里 `!needsAnimationReload && equals(currentRawAnimation)` 就**早退**（这正是相位期间动画稳定的原因）。要重启必须置 `needsAnimationReload` |

---

## 六、工具速查（tools\）

### 必用的五个

| 脚本 | 作用 |
|---|---|
| `install.py` | **安全装机**：复制 + SHA256 + 逐条目解压校验，失败重试 3 次 |
| `validate.py` | 工程自检：模型/贴图引用、语言键、掉落表、工具标签、机器注册、世界生成、结构 NBT |
| `check_mod_ids.py` | 校验引用的物品/方块 id 在整合包 194 个 jar 里真实存在 |
| `verify_readme_claims.py` | **验证 `README-交接.md` 的技术断言是否仍与实际代码一致**（改代码后必跑；当前 64 条）|
| `make_github_repo.py` | 【**已停用**】以前用来同步桌面副本 `C:\Users\30947\Desktop\深空铸造`。老板 2026-10-15 改成"只提交 git 仓库"，**平时不要再跑**（脚本本体保留，需要导出干净副本时还能用）|

### 建模相关

| 脚本 | 作用 |
|---|---|
| `check_gecko_textures.py [--fix]` | **贴图尺寸 vs geo 声明**检查/修复（黑紫问题专用，用 NEAREST）|
| `check_gecko_animations.py [--fix]` | **动画关键帧规则**检查/修复（「模型在、但一动不动」专用，见铁律 11）|
| `gen_machine_shapes.py` | 从 geo 逐层扫出碰撞箱，输出 Java 片段 |
| `import_printer_model.py` / `import_buyer_model.py` / `import_new_terminal_model.py` | 导入老板的模型（geo + 六公司贴图 + 图标 + 对比图）|
| `render_geo_anim.py <geo> <anim> <动画名> <tick> <输出png> [贴图]` | **离线渲染某 tick 的姿态** —— 判断"动画数据对不对"的利器 |
| `render_geo.py` / `render_geo_preview.py` / `render_block_icon.py` | 离线出图 |
| `bbmodel_to_geckolib.py` 等 8 个 bbmodel 转换脚本 | bbmodel → 各种格式 |
| `scan_models.py` / `model_inventory.py` | 建模计划清点 |

### 其它

`make_release.py`（打发布包）、`gen_textures.py`（六公司贴图变体）、`gen_factory_structures.py`（世界生成）、
`gen_shop_json.py`、`gen_loot_tables.py`、`gen_example_blueprints.py`、`preview.py`、`verify_conversion.py`

---

## 七、关键实现说明

### 7.1 收购投送机的动画（最复杂的一块）

**阶段机**（`AnimPhase` 枚举，纯客户端状态）：

```
PENDING ──100ms──▶ PLAYING_PLACE ──播完(800ms)──▶ WAITING_FIRST_DELIVER
                                                          │
                                      服务端成交 ────────▶ DELIVERING
                                                          │
                                              播完(550ms) ─▶ WAITING_DELIVER
                                                          │
                                      服务端成交 ────────▶ DELIVERING …
```

**握手**（"收一次 = 播一次"的关键）：

```
服务端 sellAllSlots() → notifySold() → BuyerSoldPayload ──▶ 客户端
客户端 onBuyerSold()  → 阶段进 DELIVERING → forceRestart() 重启动画
550ms 后              → 阶段回 WAITING_DELIVER → BuyerAnimDonePayload ──▶ 服务端
服务端 onAnimDone()   → animAckReady = true → 下一轮才被允许
                      （兜底：ANIM_FALLBACK_MS = 1500ms，防回执丢包卡死）
```

**放置动画的"是否播过"**：由**服务端**持有 `PlaceAnimDone`（存 NBT）；
客户端播完发回执 → 服务端置位 → 之后同步包带来的都是 `true` → 客户端不再播。

**`forceRestart()`（唯一正确的重启方式）**：

```java
// 只置 needsAnimationReload，让状态处理器自己把动画重建一次。
// 全程不产生"触发式动画" —— 所以不会出现"播完又被请求一次"。
private void forceRestart(String controllerName, String animName) {
    var manager = animCache.getManagerForId(worldPosition.hashCode());  // ← 必须用坐标 hash
    var controller = manager.getAnimationControllers().get(controllerName);
    if (controller != null) controller.forceAnimationReset();
}
```

**为什么不能用 `tryTriggerAnimation()`**（`javap -p -c` 看 `handleAnimationState` 字节码，偏移 0~85）：

```java
if (triggeredAnimation != null) {
    setAnimation(triggeredAnimation);
    if (!hasAnimationFinished()) { ...; return PlayState.CONTINUE; }
    triggeredAnimation = null;
    needsAnimationReload = true;              // ★ 置位
}
return getStateHandler().handle(state);       // ★ 然后【照样】回头问状态处理器
```

触发式动画"播完"这一刻，相位往往还没结束（「放置」相位 800ms、动画只有 750ms），
状态处理器于是又返回同一动画 → `setAnimation` 见 `needsAnimationReload == true` **不早退**（偏移 21~36）
→ **从头重建、又播一遍**。**这就是老板看到的"重叠播放两次 / 播到一半重置"。**
而 `forceAnimationReset()` 的方法体只有一句 `needsAnimationReload = true`，
`setAnimation` 重建后会在偏移 93~95 把它清回 `false` —— 所以**只重建一次**。

**阶段推进的位置**：挂在 **GeckoLib 控制器回调**里（`registerControllers` 的两个状态处理器
开头都调 `clientAnimTick()`）—— 因为控制器回调是**每帧必到**的心跳，比方块 ticker 可靠。
`clientAnimTick()` 内有 1ms 去重，同帧多调无害。

### 7.2 载具打印机是 3×2×2 多方块

`multiblock\PrinterStructure.java`：

```java
OFF_X = {0,1,2, 0,1,2}          // 未旋转时的"右"方向
OFF_Z = {-1,-1,-1, 0,0,0}       // 未旋转时的"前"方向（老板要求碰撞箱后移一格）
// 共 6 列 × 2 层 = 12 格
```

- **控制器**：玩家放下的那一格（`MachineBlock`, kind=CORE），存数据、渲染整台机器
- **11 个结构件**：`PrinterPartBlock`，**没有方块实体** —— 状态里只记
  `column`(0-5) + `layer`(0-1) + `facing`，靠 `PrinterStructure.offset()` 反推控制器位置
- **点任意一格** → 反推控制器 → `MachineBlockEntity.openMenuFor(player)` 开同一界面
- **挖任意一格** → 整台一起消失（`TEARDOWN` 集合防递归），**只掉控制器**（结构件掉落表为空）
- **碰撞**：结构件每格给一个整方块；控制器也只占本格
- **★ 放置＝全有或全无**（老板定稿 2026-10-15）：12 格里**只要有一格放不下**
  （被别的方块 / 别的多方块结构占着），就**整台不放下** —— 连控制器也不放，
  而且**不给玩家任何提示**（老板明确要求"不给提示"）。
  实现：`MachineBlock.getStateForPlacement()` 里查 `PrinterPartBlock.canPlaceAll(...)`，
  不通过就 `return null`；原版 `BlockItem.place` 见到 null 直接 FAIL，什么都不放、不消耗物品。
  > 为什么必须这样：以前是"被挡的格子跳过"，机器就会**缺格**，
  > 于是**碰撞箱缺一块**（老板反馈："碰撞箱会被其他多方块结构挤掉"）。
- **★ 不允许别的结构重叠**（老板定稿）：12 格一律是**实心、不可被覆盖**的方块
  （结构件与控制器各自 `Shapes.block()`、都不是 `replaceable`）
  → 别的多方块结构放不进这 12 格。

### 7.3 GeckoLib 渲染（合并渲染器，务必理解）

```java
// MachineGeoModel.geoName(kind)：按种类挑几何
BUYER -> "buyer"          CORE -> "factory_core"       其它 -> null（用原版模型）

// MachineGeoRenderer.render()：没有自定义模型的必须【直接返回】
if (!MachineGeoModel.usesGeo(animatable.kind())) return;   // ← 不返回会去找 null.geo.json 然后崩
```

**为什么必须合并**：`MACHINE` 是一个方块实体类型，原版**只允许一个渲染器**。

### 7.4 模型在格内的摆放规则（改模型必看）

```
GeckoLib 的 GeoBlockRenderer.preRender 做 translate(0.5, 0, 0.5)   ← 从字节码确认
所以：  方块空间 x = 像素x/16 + 0.5
        y = 像素y/16          （0 = 方块底面）
        z = 像素z/16 + 0.5

例：模型 X -24..24 → 渲染 X -1.0..2.0 格（跨 3 格）
    想让它正好占 X 0..3 格 ⇒ 几何坐标要平移到 X -8..40
```

**老板定义的"正面"**：朝向玩家的那一面。朝向 NORTH 放置时正面 = 模型的 **max-Z** 侧。

### 7.5 收购投送机：顶上有方块就不工作（老板设定的机制）

**规则**（老板 2026-10-15 定，同日三次修改后定稿）：

1. **正上方只要被"占满整格"的方块挡住**（**不管多高**，从机器上一格一直扫到世界顶部）
   → 这台机器就**不工作**（不自动收购、也不响应界面上的「立即收购」）；
2. 界面里提示 **「上方被阻挡无法工作」**；
3. 火把、告示牌、雪片之类的**不算挡**。

```java
// MachineBlockEntity：判定（带 0.5 秒缓存 —— 扫到世界顶部太贵，不能每 tick 都扫）
public boolean blockedAbove() {
    if (level == null || kind() != MachineKind.BUYER) return false;   // 只有投送机受这条限制
    // 缓存 10 tick（0.5 秒）：玩家盖顶/拆顶后最多半秒反应过来
    ...
    return scanBlockedAbove();
}

private boolean scanBlockedAbove() {
    for (int y = worldPosition.getY() + 1; y < level.getMaxBuildHeight(); y++) {
        if (isFullBlockAt(那格)) return true;      // ★ 一路扫到世界顶部
    }
    return false;
}

private boolean isFullBlockAt(BlockPos pos) {       // 「占满整格」才算挡
    VoxelShape shape = level.getBlockState(pos)
            .getCollisionShape(level, pos, CollisionContext.empty());
    if (shape.isEmpty()) return false;              // 空气、水
    return 六个面都贴到格子边界;
}

// 两处拦截（缺一处就会出现"说明里说不工作、实际还在收"）
processBuyerCycle()  → if (blockedAbove()) return false;   // 自动收购
sellAllSlots()       → if (blockedAbove()) return false;   // 手动「立即收购」
```

**UI 怎么传过去的**（沿用现成的数据槽机制，没加新网络包）：

| 位置 | 改动 |
|---|---|
| `MachineMenu` | `DATA_COUNT` 14 → **15**，新增 `DATA_BLOCKED_ABOVE = 14`，加访问器 `blockedAbove()` |
| `MachineBlockEntity.createDataAccess()` | `case MachineMenu.DATA_BLOCKED_ABOVE -> blockedAbove() ? 1 : 0;` |
| `MachineScreen.renderLabels()` | 投送机且被挡时，在 **(8, 78)** 用 `WARN` 红色画一行提示 |
| `lang/zh_cn.json` + `en_us.json` | `gui.deepspacedeployer.blocked_above`（中英各加一条，301 条对齐）|

> **为什么放在 (8, 78)**：界面左侧状态行在 y=65（字号 9）、投送机的按钮从 y=96 开始，
> 78 正好是空档，不会压到任何控件。
>
> **判定标准**：从机器正上方**一格一格扫到世界顶部**（**不管多高**），
> 只要路上遇到"**占满整格**"的方块就算被挡（碰撞箱六个面都贴到格子边界）。
> * **不算被挡**：火把、告示牌、雪片、半砖、楼梯、栅栏、活板门、草 —— 老板明确要求这些不挡；
> * **算被挡**：石头、木板、玻璃、树叶……这类占满整格的方块；
> * 空气、水（碰撞箱是空的）：不算。
>
> 为什么是"占满整格"而不是"有没有碰撞"：**火把也有碰撞箱**（只是很小），
> 所以"非空即挡"会把火把算进去，正是老板要去掉的那一类。
>
> ⚠️ **别把 `getMaxBuildHeight()` 的循环去掉**：老板要的是"不管距离多远"，
> 只查紧挨着的那一格是不够的。循环里有 0.5 秒结果缓存兜性能。

---

### 7.6 星联动力联盟商店：靠近展开 / 离开闭合（老板 2026-10-16 的新模型）

**只星联动力联盟的商店**用老板新做的模型（**三个等级共用一套**），其余公司的商店
继续用原版方块模型。

| 资源 | 位置 |
|---|---|
| 模型 | `geo/block/terminal_astralink.geo.json`（声明 256×256，UV 上限 114）|
| 动画 | `animations/block/terminal_astralink.animation.json`（**展开 / 闭合**，各 0.5 秒，`hold_on_last_frame`）|
| 贴图 | `textures/block/terminal_astralink.png`（512×512 = 声明的 **2×**）|
| 重导脚本 | `python tools\import_astralink_shop_model.py`（贴图直接取自 bbmodel 里内嵌的那张）|

**行为**（老板定稿）：玩家进到**水平 2 格**内 → 播「展开」停在最后一帧；
离开 2 格 → 播「闭合」停在最后一帧；**一个动画播完才允许播另一个**。

```
服务端（每 5 tick）  MachineBlockEntity.updateAnyoneNear()
   └ 有变化 → anyoneNear=true/false → setChanged + syncToClient（NBT 字段 AnyoneNear）
客户端（每帧）       TerminalShopAnim.tick()   ← 挂在 shop 控制器的状态处理器上
   ├ 正在播 → 直接 return（绝不打断；期间目标变了就"排队"，播完立刻接上）
   └ 状态与目标不一致 → restartAnimation("shop", 展开/闭合)
状态处理器           TerminalShopAnim.playState()
   ├ 还没播过任何动画且处于闭合 → STOP（模型静止姿势本来就是闭合，别平白播一遍）
   └ 其余 → setAndContinue(对应动画)，配合 hold_on_last_frame = 停在最后一帧
```

**五个容易踩的点**（都处理了）：

1. **必须推进 GeckoLib 的时间轴**（`advanceAnimClockOnly()`）——
   `getTick()` 返回的 `animAgeTicks` 原先**只有收购机的 `clientAnimTick()` 在更新**，
   商店不推就恒为 0 ⇒ **动画永远停在第 0 帧**；而「展开」的第 0 帧正好是**闭合姿势**，
   于是画面上看起来"根本没播"，**但状态机日志一切正常**（那套用自己的墙上时钟计时）——
   2026-10-16 实测踩到，最阴的一个；
2. **模型静止姿势 = 闭合姿势** → 所以"还没播过 + 当前闭合"时状态处理器返回 `STOP`，
   否则每次进游戏商店都会自己"啪"地关一次；
3. **重启只能用 `forceAnimationReset`**（`restartAnimation` → `forceRestart`）——
   用触发式动画会播两遍（见**铁律 12**）；
4. **渲染形状与模型必须同一套判断**：`MachineBlock.hasCustomModel(状态)` 与
   `MachineGeoModel.geoName(方块实体)` 都按「种类 + 公司」判断，不一致会出现
   "本体和模型同时画"或"谁都不画"；
5. **只给商店注册 `shop` 控制器**（`if (kind() == MachineKind.TERMINAL)`）——
   别的机器一个字节都不参与（隔离，**铁律 14**）。

> 日志关键词：`[深空铸造体][商店动画] 开始播「展开」/「闭合」`、`播完「…」→ 当前=…`

---

### 7.7 收购投送机「为什么不工作」诊断（0.1.3，老板 2026-10-17 反馈"新存档无论如何都不工作"）

**根因**：收购循环有 5 道关卡，**每一道失败原先都是静默 `return false`** —— 界面和日志都不吭声，
所以"它不动"完全无法定位。

| 码 | 原因 | 怎么解决 |
|---|---|---|
| 0 | 正常（有东西可收） | —— |
| 1 | 没插银行卡 | 把银行卡放进卡槽 |
| 2 | **银行卡还没绑定账户**（新存档最常见） | **右键银行卡**绑定 |
| 3 | 自动收购已关闭 | 界面点「自动收购」开关 |
| 4 | 头顶被整块方块挡住 | 拆掉机器正上方的方块（火把/告示牌/半砖不算） |
| 5 | 输入格里没有这家公司收的东西 | 换成该公司收购清单里的物品 |

**现在怎么用**：

1. **看界面**：原生界面在 y=78 画一行红字「未工作：银行卡还没绑定账户（右键卡绑定）」；
   主题界面由 `buyer.html` 的 `data-if="未工作"` 显示同样一行；
2. **看日志**：搜 `[深空铸造体][收购诊断]`，只在**原因变化时**写，不刷屏；

**实现位置**（改这块时别搞乱对应关系）：

- `MachineBlockEntity#buyerIdleReason()` —— 原因码，**判断顺序必须和 `processBuyerCycle()` 里一致**，
  否则界面会报错原因；
- `MachineMenu.DATA_IDLE_REASON = 15`（`DATA_COUNT` 现在是 **16**）；
- `MachineScreen` —— 原生界面那行红字 + 主题键 `未工作` / `未工作原因`；
- 语言键 `gui.deepspacedeployer.idle_reason[.1~.5]`（中英双语）。

---

## 八、给接手者的建议动作顺序

```
① 跑一遍：gradle clean build → validate.py → check_mod_ids.py → check_gecko_animations.py
   → verify_readme_claims.py → install.py
   确认基线是绿的（不绿就先修，不要往下做）

② 读 docs\设计决策.md 的 §2.48 ~ §2.51
   这四节记录了收购投送机从 0 到"收一次播一次"的全过程，含 9 个坑

③ 按第五节的"待确认"表，逐项向老板要反馈（一次一项，不要同时问）
   最有价值的一次性信息：logs\latest.log 里搜 [动画诊断] / [投送时序] / [创造板条箱]
   **改过模型/动画后必搜**：`Unable to parse animation`（搜到就是铁律 11，跑 check_gecko_animations.py）

④ 每次改动后：clean build → validate → check_gecko_animations → verify_readme_claims → install
   → **git 提交** → 更新设计决策.md
```

### 日志关键词速查

| 关键词 | 含义 |
|---|---|
| `[动画诊断]` | 阶段机推进：`PENDING → PLAYING_PLACE`、`PLAYING_PLACE: elapsed=… 差=…`（**注意：不是 `[放置诊断]`**，旧文档写错了）|
| `Unable to parse animation: <名>` | ★ **动画文件被 GeckoLib 拒收**（铁律 11）→ 跑 `tools\check_gecko_animations.py` |
| `[投送时序]` | forceRestart 时"本阶段已过 N ms" / 播完时"N ms（应≈550）" |
| `[创造板条箱]` | 桥接可用 / 扫描半径 / 找到几个 / 供应物品 / 无限材料判定 |
| `[动画] 首次渲染兜底` | 放置动画的兜底路径被触发；带 `实例=xxxxxxxx` —— **同一个位置若出现不同实例号，就是方块实体被重建了**（此时"只播一次"的客户端标志会丢，动画会重播）|
| `Registry loading errors` | **世界生成数据读不出来** → jar 损坏，重装 |
| `ZipException` | jar 条目损坏 → 用 `install.py` 重装 |

---

## 九、与老板沟通的注意事项

1. **老板不是程序员**（原话："我没有代码基础"）—— 所有解释用**具体中文 + 数值**，避免术语堆砌。
2. **老板是唯一的视觉判断者** —— 本会话无法实机验证，**任何"改好了"都必须等老板确认**。
3. **老板不喜欢反复失败** —— 每次改动前先说清"改什么、为什么、预期看到什么"，一次说清。
4. **不要擅自动老板的建模数据**（动画数值、造型）——
   只在"导入"层面处理（格式修正、尺寸对齐、坐标平移），改数值前必须问。
5. **改动要提交到 git 仓库**（老板 2026-10-15 的规矩）——
   仓库就是项目根目录本身，`git add -A && git commit`。
   **桌面副本 `C:\Users\30947\Desktop\深空铸造` 已停用，别再去同步它。**
6. **老板会用多个对话并行** —— 所以**改动要立即落盘到项目 + 提交 git**，不要留在临时变量里。

---

## 十、版本与许可

```
版本        0.1.23+mc1.21.1
License     见 LICENSE（All Rights Reserved）
仓库        本地：项目根目录里的 git 仓库（分支 main）
             C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer
            远程：https://github.com/3094730299/Deep-Space-Frontier.git（私有）
            推送：git push origin main   （首次推送用过 --force-with-lease 清掉 GitHub 的占位 README）
            【桌面副本 深空铸造 已停用，不再同步】
贡献        CONTRIBUTING.md
```
