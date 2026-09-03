# 前端收敛与 ChatPage 增值接线完成报告（S6 续）

> **日期**：2026-09-03 | **依据**：用户确认的两项后续（API 收敛 A 组 + ChatPage 三按钮），前置 chat.ts 缺陷修复
> **验收结论**：✅ 四个提交交付，后端 439 测试全绿零回归，工作区干净

## 交付清单（4 个提交）

| 提交 | 内容 |
|------|------|
| `81a0aa2` | **chat.ts file 事件分发体丢失修复**（T4.5 编辑事故：case 'file' 被脚本替换截断剩孤立括号，onFile 回调失效、语法错误；恢复分发体并花括号平衡自检）——独立 fix 提交 |
| `0b07a02` | **A 组 11 模块 API 收敛**：agent/workflow/skill/menu/role/org/sandbox/user/stats/mcp/audit 全部迁移至 session.ts 试点范式（unwrap 信封解包 + request 泛型），删约 300 行重复样板；导出面不变、UI 零改动；特殊语义保留（mcp records 解包与 DTO 映射、audit 链校验 Boolean、workflow current 分页字段） |
| `34a818d` | **ChatPage 三按钮接线**：① 重发——AI 气泡 hover 按钮（仅 fromBackend 历史消息），本地裁剪 + regenerateMessageStream SSE 重跑（复用 ProcessTracker）+ 完成后 listMessages 回填真实 messageId；② 置顶——两处列表项 hover Pin/PinOff（已置顶常显图钉），stopPropagation 防误触，后端排序透传；③ 分享——头部 Share2 死按钮接 toggleShare（slug 生成 + 链接复制 + 已分享态高亮） |
| 本提交 | 完成报告 |

**支撑改动**：chat.ts 提取 `consumeChatSse` 共用实现 + `ChatStreamCallbacks` 导出接口 + 新增 `regenerateMessageStream`；types Message 增加 `fromBackend` 标记；清理 deleteSession 死 import。

## 关键实现决策（已按推荐项执行）

1. **收敛范围**：A 组 11 个纯 CRUD 模块先行（约 65 函数、机械替换零特殊逻辑）；B 组（multipart/裸文本 4 模块，需处理 axios Content-Type 覆盖）、orchestration SSE（保留 fetch 直连）、model-config 第三轨未包含；
2. **重发 messageId 方案**：前端完成后 listMessages 刷新回填（纯前端改动，不动后端 SSE 契约）；重发按钮仅对 `fromBackend` 历史消息显示；
3. **chat.ts 修复独立提交**：该缺陷导致流式文件卡片功能当前不可用，修复不应被接线改动淹没。

## 验证与边界（如实记录）

- 后端全量 439 测试全绿（本计划未动后端，跑通确认零回归）；两套 compose YAML 此前已解析验证；
- 前端本机无 node：以逐文件类型/导入核对 + 每次编辑后花括号平衡自检兜底，**构建级类型检查依赖 CI/下次 npm 环境**（与 T4.3 试点相同约束）；
- 语义说明：收敛后 15 个"仅 res.ok"函数错误信息变丰富（原后端 message 丢失），属行为增强；
- 重发按钮在当前会话新产生的消息上不显示（无后端 id）——刷新或重进会话后即可用；如需完全实时闭环，后续可做"后端 done 事件携带 messageId"小改造（后端契约变更，单列决策）。

## 剩余后续项（44 号文档跟踪体系外补充）

- B 组 4 模块收敛（multipart Content-Type 覆盖 + res.text() 语义确认，约 1 人日）；
- orchestration SSE / model-config 收敛（约 0.5 人日）；
- "done 事件携带 messageId" 后端小改造（可选，使重发按钮全量可用，约 0.5 人日）。
