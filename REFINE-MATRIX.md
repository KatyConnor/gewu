# REFINE-MATRIX — 输入规范化矩阵（语义优化正本 · V1.9.29）

> 与 INTENT-MATRIX.md（意图路由正本）配对：INTENT 判"这输入该哪套规范管"（分类），
> 本矩阵管"这输入说清楚没有"（质量）——路由对了≠输入可用，混乱输入硬挤出三答
> 后转发执行的仍是原始浆糊。本矩阵是 Step 0 三段协议的 0b/0c 段正本
> （AGENTS.md），派生关系同三层矩阵惯例：trigger_check.py 运行时解析本表（零漂移）。
> 行序 = INTENT-MATRIX 参与语义竞争的 12 套（#1 AETC/#9 ATLAS 不参与，同理不入本表）。

## 五要素协议（V1.9.29 · 意图三问扩两问）

意图三问（type/object/artifact）之上扩两问，共五要素：

1. **type 任务类型**（由 Step 0a 路由确定，本层不重复验）
2. **object 对象**——文件/服务/模块/文档，处理的具体靶子
3. **env 环境上下文**——本地/测试/线上，哪个项目根，哪些前置
4. **done 完成标准**——做到什么程度算完（验证/回归/交付标准）
5. **artifact 期望产物**——代码/报告/方案/结论

必填要素集按规范域差异化（下表列 3）：修复域最严（AEGIS 修什么+在哪复现+怎么算修好
三要件缺一不可）；理解/治理域最宽（说清对象与产物即可开工）。

## 输入规范化矩阵（机读正本·列：例句为回归用例）

| # | 规范 | 必填要素 | 对象域词 | 混乱例句（回归锚） | 期望判定 |
|---|------|----------|----------|----------------------|----------|
| 2 | LPU | object,artifact | 存量项目 | 「帮我看看这个老项目，不大敢动」 | 缺 object |
| 3 | LR | object,artifact,done | 旧系统 | 「旧系统想换掉」 | 缺 object,artifact,done |
| 4 | OPS | object,env | 线上服务 | 「线上那个东西最近怪怪的有时候不行」 | 缺 object,env |
| 5 | SDLC | object,artifact,done | 功能特性 | 「把昨天说的那个功能加上」 | 缺 object,done |
| 6 | CR | object,done | 待审代码 | 「帮我看看写得规范吗」 | 缺 object,done |
| 7 | UI | object,artifact | 页面组件 | 「这个页面弄好看点」 | 缺 object,artifact |
| 8 | CODING | object,done | 函数模块 | 「写个分页」 | 缺 object,done |
| 10 | AEGIS | object,env,done | 缺陷报错 | 「帮我改一下那个登录的，感觉不太对」 | 缺 object,env,done |
| 11 | META | object,artifact | 规范技能 | 「这东西怎么划分来着」 | 缺 object,artifact |
| 12 | UCSS | object,env | 代码依赖 | 「上线前帮我看下有没有问题」 | 缺 object,env |
| 13 | UTSS | object,done | 改动功能 | 「确认下没改坏吧」 | 缺 object,done |
| 14 | THEMIS | object,artifact | 文档方案 | 「帮我评审下那个文档」 | 缺 object,artifact |

## 质量判定协议（trigger_check.py --refine 实现）

- **校验**：按矩阵行必填要素集校验四要素（object/env/done/artifact）非空非占位；
- **缺失 → 澄清问句优先，禁止瞎补**（对齐 V1.9.19 低置信禁瞎路由——不脑补用户
  没说的要素），澄清句按缺失要素渲染：object 缺→「要说清{域词}具体指哪个」；
  env 缺→「要说清运行环境（本地/测试/线上·项目根）」；done 缺→「要说清做到
  什么程度算完成」；artifact 缺→「要说清期望拿到什么产物」；
- **齐备 → 标准重写**（统一骨架按必填集渲染）：
  「{域}请求｜对象：{object}｜环境：{env}｜完成标准：{done}｜期望产物：{artifact}」；
- **双文转发**：重写句为执行主文（结构化低方差），原文为附注段（保语感边角
  信息）——主附分工既压方差又防信息丢失；
- **落痕**：每次 --refine 调用追加一行 JSONL 至 `<项目根>/.standards/audit/intent-log.jsonl`
  （record_type=refine：时间/原文/四答/缺失清单或渲染句）——模型管理解与改写、
  脚本管校验渲染与留痕（分工铁律：脚本只做结构层校验，不做语义判断）。

## 回归用例（selftest）

5 条（4 澄清 + 1 齐备渲染对照——后者防"过度澄清"失败形态回缩）：
改登录的→AEGIS 缺三｜线上怪怪的→OPS 缺二｜昨天那个功能→SDLC 缺二｜
写得规范吗→CR 缺二｜齐备例（object/env/done/artifact 全给）→渲染标准句

> 新增规范接入时同步维护本表一行（判型记录 release 面要求）——必填要素集
> 初始可从宽（object,artifact），实测收紧走版本演化，棘轮不回缩。
