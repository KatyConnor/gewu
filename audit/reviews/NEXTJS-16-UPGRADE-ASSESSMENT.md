# H-7 评估：Next.js 14.2.35 升级路径（UCSS-SCAN-20261012 · FIX-20261012 第二批）

## 结论
**不紧急，纳入常规依赖升级节奏**（建议随下一迭代窗口执行 14→15→16 两步迁移）。
理由：OSV 23 条公告中最重的未认证 RCE（GHSA-2xp9-vwfh-vxw4，AVIF 图像优化）在本仓库**无触发路径**——
`gewu-web/src` 全量 grep 零处使用 `next/image`，未配置 `images.formats`，应用不消费 `/_next/image`
优化管线；CVE-2026-44573（Middleware 绕过）同样不适用（无 `middleware.ts`）。

## 攻击面核查（2026-10-12）
| 公告 | 触发条件 | 本仓库 | 判定 |
|---|---|---|---|
| GHSA-2xp9-vwfh-vxw4 未认证 RCE（AVIF） | next/image 图像优化处理 AVIF 输入 | 无 next/image 用法 | 不适用（C3 保留观察） |
| CVE-2026-44573 Middleware 绕过 | Pages Router + i18n + middleware | 无 middleware.ts | 不适用 |
| CVE-2026-64646~49（Edge/Server Actions/缓存混淆） | Server Actions / Edge runtime | 未使用 Server Actions | 不适用 |
| 其余 19 条 | 多为 dev/canary 或特定 API | 逐条随升级复扫 | — |

## 升级路径（建议）
1. `npx @next/codemod@latest upgrade latest`（当前 14.2 → 15.x）：async request APIs
   （`cookies()/headers()/params/searchParams` 变 Promise）、GET Route Handler 默认不缓存、
   `next/font` 与 App Router 缓存语义变化——codemod 覆盖大部分，TS 编译错误逐个收敛。
2. 15.x 内升到最新 patch → 再评估 16.x（Turbopack 默认化，风险递减）。
3. 联动：`eslint-config-next` 同步升级；React 18.3.1 保持（Next 15 兼容），16.x 再评估 React 19。
4. 验证：`pnpm build` + 登录/工作台/会话/FileEditor 冒烟 + rewrites 代理回归（`/api/*` → 8081）。

## 升级前临时缓解
不必要（无触发路径）。若未来引入 `next/image`，须同步升级 Next 至已修复版本后再启用 AVIF。
