# AEGIS 批量受理单（受理层拆单 V1.1）
- 来源：audit/reports/UCSS-SCAN-20261012-framework.md（门禁 FAIL：Critical 3 / High 8 / Medium 14 / Low 3）
- 授权：用户指令「按照计划优先级启动修复」（GATE 已批准，trace s-20261012-081241）
- 检查点：pre-aegis-fix-p0（protect）

## 拆单映射（处理层逐单 P0-P6）
| 单号 | 缺陷 | 级别 | 范围 |
|---|---|---|---|
| FIX-01 | C-1 JWT 密钥公开入库+死信变量 | S3 | gateway/interface/admin yml、compose.prod、helm config |
| FIX-02 | C-2+H-8 SM4/沙箱 key 默认值 + prod 弱值不阻断 | S3 | .env.example、compose.prod、interface/admin yml、ApiKeyCryptoService |
| FIX-03 | C-3 生产 compose 弱口令族+端口暴露 | S2 | docker-compose.prod.yml |
| FIX-04 | H-1 编排图横向越权 | S3 | OrchestrationService |
| FIX-05 | H-2 沙箱代理无属主校验 | S2 | SandboxProxyController（归属数据可得性待评估） |
| FIX-06 | H-3 会话消息无成员校验 | S3 | MessageService |
| FIX-07 | H-4 DevWorkspace 路径绕过净化 | S3 | DevWorkspaceService |
| FIX-08 | H-5/H-6 Electron 特权桥暴露+凭据跨源 | S2 | electron/main.js |
| FIX-09 | P2 快赢批（M-1/2/3/4/5/8/9/10/11/12） | S1×N | 配置+小代码，逐项见 VR |
| DEFER-01 | M-6 密码哈希迁移（需存量迁移策略）M-7 DataPermission fail-closed（需兼容审计）M-13/14 SCA 大版本升级（立项） | - | 按计划立项，不在本批 |
