# 55 · 编排设计器画布坐标兜底修复方案

> 状态：待评审 ｜ 日期：2026-09-29 ｜ 关联：docs/design/46（编排图设计器）、docs/design/51（工作流引擎设计实施）
> 背景：工作流画布（WorkflowCanvas）已修复"存量坐标全 (0,0) 导致节点全部叠加"问题，本方案回答
> "编排设计器（OrchestrationDesigner）是否存在同类隐患"，并给出分层修复设计。

## 一、结论

**隐患存在（潜在），当前数据未触发，无用户可见故障。**

与工作流画布的差异是触发条件不同：

| | 工作流画布（已爆） | 编排设计器（未爆） |
|---|---|---|
| 坏数据形态 | 存量迁移数据**显式** `positionX=0, positionY=0`（724 节点中 585 个） | 现网 0 组全 0 坐标；坏数据形态是**完全缺失 x/y**（14 图 + 14 版本快照） |
| 兜底行为 | `??` 不识别 0 → 全部叠加画布原点 | 缺失 x/y 恰好被 `??` 网格兜底正确铺开 |
| 坐标写入方 | 冒烟脚本/API 建图写死 0 | 设计器保存永远写真实坐标（`flowToDefinition` 强制回写） |

即：编排侧的 `def.x ?? gridPosition(index)` 对"缺失坐标"是对的，对"显式 (0,0)"和"非数字坐标"是错的——
而后端写入口对坐标完全不设防，任一 API 调用方（脚本/集成/AI 建图）今天就可以写出会叠加的定义。

## 二、核查证据（2026-09-29，gewu_dev）

代码链路：

- `gewu-web/src/lib/orchestrationDesigner.ts:164`：`definitionToFlow` 用 `def.x ?? gridPosition(index).x` 兜底，
  仅识别 `null/undefined`；显式 `0`、字符串 `"0"`、`NaN` 均原样进入 React Flow。
- `orchestrationDesigner.ts:199`：`parseDefinition` 只做外壳类型检查，节点 `x/y` 不做类型收敛。
- `orchestrationDesigner.ts:178`：`flowToDefinition` 保存时永远写 `Math.round(position)`——经设计器保存的图必有合法坐标。
- 模板库 `orchestrationTemplates.ts`：全部节点带显式网格坐标（起点 60,60），无风险。
- 后端 `OrchestrationService.java`：`createGraph`(:83)/`updateGraph`(:109)/`rollbackGraphVersion`(:230)
  三个写入口仅校验"非空 + 是 JSON 对象 + 结构规则"，**坐标不校验**；`normalizeDefinitionJson` 只改写 `mode`。
- 坐标语义：`GraphNodeDef.x/y` 注释明确"引擎不消费"，纯展示层数据，修坏不影响执行。

数据核查（全量 30 行定义逐条解析）：

- 16 张图 + 14 份版本快照：**0 组全 0 坐标、0 组重复坐标、0 组非数字坐标**。
- 14 张图 + 14 份快照的节点**完全缺失 x/y**——程序化建图的直接证据，当前被网格兜底正确铺开。
- 1 张图 `graph_definition = NULL`——设计器已有 SKELETON + 报错 toast 处理，不在本方案范围。

## 三、风险点清单

| # | 风险 | 触发条件 | 后果 |
|---|------|----------|------|
| R1 | `definitionToFlow` 不识别"占位 (0,0)" | API/JSON 视图写入 `x:0,y:0` 的节点定义 | 多节点全部叠加画布原点，`fitView` 会缩放到重叠点，视觉不可用 |
| R2 | `parseDefinition` 不收敛坐标类型 | JSON 视图粘贴 `"x":"0"`、`"x":null`、`"x":1.5e9` 等 | React Flow 收到非数字/越界 position，渲染错乱（NaN 变换） |
| R3 | 后端写入口坐标不设防 | 任意外部调用方建图 | 坏定义静默入库，问题延迟到打开设计器才暴露 |
| R4 | 版本回滚原样回灌 | 历史快照含坏坐标 | 回滚后叠加，且用户可能误以为回滚本身失败 |
| R5 | 现有 28 组缺坐标数据依赖前端兜底 | 未来出现第二个坐标消费方（导出、只读视图、移动端） | 兜底逻辑散落重复，漏一处即叠加 |

## 四、修复方案（分层）

### P0-A 画布兜底：坐标碰撞检测（治显，必做）

`definitionToFlow` 增加与 WorkflowCanvas 修复语义对齐的两遍扫描：

1. 第一遍统计 `nodes` 中合法数字坐标的占用次数；
2. 第二遍映射：坐标**缺失或被多节点共用** → `gridPosition(index)`；唯一合法坐标 → 保留原值（含真实的 (0,0)）。

```ts
// orchestrationDesigner.ts · definitionToFlow 内
const coordUsage = new Map<string, number>();
for (const def of definition.nodes) {
  if (isFiniteNumber(def.x) && isFiniteNumber(def.y)) {
    const key = `${def.x},${def.y}`;
    coordUsage.set(key, (coordUsage.get(key) ?? 0) + 1);
  }
}
const nodes = definition.nodes.map((def, index) => {
  const shared = isFiniteNumber(def.x) && isFiniteNumber(def.y)
    && (coordUsage.get(`${def.x},${def.y}`) ?? 0) > 1;
  const hasPos = isFiniteNumber(def.x) && isFiniteNumber(def.y) && !shared;
  return {
    id: def.nodeId,
    type: 'orchestration' as const,
    position: hasPos
      ? { x: def.x as number, y: def.y as number }
      : gridPosition(index),
    data: { def },
  };
});
```

配套 `isFiniteNumber(v): v is number`（`typeof v === 'number' && Number.isFinite(v)`），同时消除 R1/R2 的画布侧暴露。

**与 WorkflowCanvas 不抽公共函数的理由**：两画布坐标模型不同（`x/y` 平铺字段 vs `position{x,y}` 对象）、
渲染栈不同（手写 SVG vs React Flow），公共化收益低于耦合成本；只需保持语义一致
（**缺失或共用 → 网格；唯一 → 保留**），并在两处注释互引。

### P0-B 解析防御：坐标类型收敛（治因之一，必做）

`parseDefinition` 对每个节点做坐标收敛，非有限数字一律视为缺失（置 `undefined`），
使 `definitionToFlow` 的输入域规约到"合法数字 | 缺失"：

```ts
nodes: (obj.nodes as GraphNodeDef[]).map(n => ({
  ...n,
  x: isFiniteNumber(n.x) ? n.x : undefined,
  y: isFiniteNumber(n.y) ? n.y : undefined,
})),
```

注意保持节点其余字段（config/inputs/refId）原样透传，不得深拷贝语义变化。

### P1-C 后端收口：normalize 阶段坐标规范化（防再发，建议做）

`OrchestrationService.normalizeDefinitionJson`（JsonNode 树改写处）增加：
`nodes[].x/y` 存在但非数字时**删除该字段**（交给前端网格兜底），合法数字保留，不注入新值。
收益：坏定义不再静默入库，版本快照天然干净；成本约 10 行 + 一条 DEBUG 日志。
不做坐标纠偏/布局注入——坐标是展示层关注点，后端只做"非法即剔除"的最小干预。

### P2-D 存量数据与用户路径（不做库修复）

- 28 组缺坐标数据：前端网格兜底已正确显示，**不刷库**（引擎不消费坐标，刷库收益为零、风险为正）。
- 用户打开此类图后点"整理布局"（`layeredLayout`）或拖拽保存，真实坐标即回写。
- 版本快照随 P1-C 之后的激活行为自然干净；历史快照由 P0-A 兜底，不回灌修复。

### 验证设计

| 层 | 用例 |
|----|------|
| 单测（definitionToFlow） | 正常坐标回归不变；全 (0,0) → 网格铺开；部分缺失 → 缺失者入网格、其余保留；坐标重复 → 重叠组入网格、孤立 (0,0) 保留；`"x":"0"`/NaN → 视为缺失 |
| 单测（parseDefinition） | 非数字 x/y 收敛为 undefined；合法坐标原样；其余字段透传 |
| 手工 | JSON 视图粘贴全 0 定义 → 应用到画布 → 节点铺开；API 建全 0 坐标图 → 打开设计器 → 铺开；存量缺坐标图 → 铺开且可拖拽保存回写 |
| 门禁 | `tsc --noEmit`、`next build`、（若做 P1-C）后端模块 `mvn -pl gewu-application test` |

## 五、工作量与顺序

| 项 | 规模 | 优先级 |
|----|------|--------|
| P0-A + P0-B（orchestrationDesigner.ts） | ~30 行 + 单测 | P0，随下一个前端批次落地 |
| P1-C（OrchestrationService.java） | ~10 行 | P1，可与 P0 同批 |
| P2-D | 无代码 | — |

P0 两项均为纯前端纯函数改动，无接口/数据变更，风险低；P1-C 动后端入库路径，需按 R2 留痕与检查点执行。
