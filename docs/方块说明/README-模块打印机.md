# 模块打印机

> 方块 ID：`module_printer`  
> 归属：6 家公司各一种  
> 等级：低 / 中 / 高  
> 结构：单格方块  
> 作用：打印服务端预设的模块化物理结构

## 一句话

只读服务端 `modules/` 预设，不读玩家 Sable；不耗材料，只扣本公司货币。  
和载具打印机是**两台独立机器**，不是同一个方块切模式。

## 放置

- 单格方块，6 家公司 × 低/中/高；
- 放下后归属和等级固定；
- 不需要多方块结构。

## 打开

- 空手右键打开界面；
- 插卡后才能打印（要扣公司账户）；
- 界面是原生布局，不走主题 HTML。

## 模块来源

只读：

```text
config/deepspacedeployer/modules/
  <名字>.nbt
  <名字>.json
```

- `.nbt` 必须是 Sable 蓝图；
- 同名 `.json` 可选，写显示名、价格、最低等级、可用公司；
- **不读** `Sable-Schematics/`、`schematics/` 等玩家蓝图文件夹；
- 载具打印机也不会把 `modules/` 里的东西列进自己的蓝图列表。

## 打印流程

1. 打开界面，列表里只会出现当前公司/等级可用的模块；
2. 点「选投放点」在周围地图选位置（不选则默认前方 3 格）；
3. 插卡；
4. 点模块行右侧「打印」；
5. 直接生成物理结构。

## 费用

- **不消耗任何材料**；
- 只扣**这台模块打印机所属公司**的账户余额；
- 价格来自模块的 `.json`；没有 `.json` 时默认 5000；
- 实体币、通用账户不参与模块打印；
- 生成失败会把钱退回；
- 没有库存限制。

## 等级/公司过滤

- 模块 `.json` 的 `tier` 是**最低等级**；低于它的模块不显示；
- `company` 留空 = 所有公司可用；填了公司 id 就只有那家公司能打印；
- 公司 id：`threshold / whisperbelt / brokenedge / skimmer / bedrock / astralink`。

## 预设示例

`config/deepspacedeployer/modules/living_pod.nbt`  
`config/deepspacedeployer/modules/living_pod.json`：

```json
{
  "display": "居住舱模块",
  "price": 5000,
  "tier": "mid",
  "company": "threshold"
}
```

改完执行：

```text
/dsd reloadblueprints
```

会同时重读载具、`vehicles/`、`modules/`。

## 注意

- **仓库没有附带模块 `.nbt`**，所以第一次打开列表是空的；
- 详细格式见 `docs/模块打印机预设.md`；
- 外观目前是载具打印机模型的冷蓝偏色版，后续可换独立模型；
- 机器设计为不依赖固定世界坐标，但“在物理结构上运行”还没有实机验证。

[返回索引](README.md)
