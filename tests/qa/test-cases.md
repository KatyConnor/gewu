# 测试用例（GWT，来源=Step1 清单逐项映射）

## T0 冒烟（准入）
- TC-00a [来源:变更清单] 全仓 `mvn compile` → EXIT=0 ✅（发现并阻断 1 处 M-7 构造器签名错误）
- TC-00b [来源:FIX-08] Electron 主进程 5 JS `node --check` → 全过 ✅
- TC-00c [来源:FIX-01/03] compose/helm YAML 语法解析 → 11/11 过 ✅

## S1 功能（本批变更面）
- TC-01 [来源:M-6] PasswordHasher：新格式往返/错误密码拒绝/随机盐/畸形输入/旧格式兼容/needsUpgrade → PasswordHasherTest 9/9 ✅
- TC-02 [来源:M-6] PBKDF2WithHmacSM3 运行时（BC 注册）→ 冒烟 4 项 PASS ✅
- TC-03 [来源:C-2] ApiKeyCryptoService：prod 弱默认 throw/空值 fail-closed → ApiKeyCryptoServiceTest 5/5 ✅
- TC-04 [来源:H-4] sanitizeWorkspaceRel：../ 注入/shell 注入/反引号/中文/绝对路径 → 既有 16 用例 ✅
- TC-05 [来源:M-7] DataPermission fail-closed → infrastructure 3 用例 ✅
- TC-06 [来源:H-2] requireSandboxOwner 6 端点 / MessageService.checkMembership / requireGraphOwner → **无单测（Controller/Service 层需 mock 基建）** → 缺陷 D-1 登记
- TC-07 [来源:C-1] JWT 占位符 fail-closed（缺失启动失败）→ Spring 标准语义 + 死信变量清零 grep ✅（运行时验证受限 C3）

## S2 安全复扫（UCSS 联动）
- TC-08 [来源:C-1/2/3] 弱口令/公开密钥 grep → 零残留（dev 弱默认设计保留除外）✅
- TC-09 [来源:M-13] tomcat 10.1.46/security 6.2.7/netty 4.1.118 属性 → pom 落位 ✅（运行期验证需部署）

## S3 回归（影响面）
- TC-10 受影响 4 模块全量单测 → 326/326 绿（common 96/application 208/infrastructure 3/desktop-runtime 19）✅
- TC-11 覆盖率门禁 C 档：本批变更面（PasswordHasher/ApiKeyCryptoService/SM3/SM4）最低 84.4% ≥ 80 → PASS ✅；
  crypto 全包口径 FAIL（SM2Util/PasswordPolicy 存量 0%）→ 缺陷 D-2（既有欠账，非本批引入）
- TC-12 AI 代码检查 P1-P10：P1 编译✓ P2 占位扫描✓ P4 catch 审查✓（命中均既有段）P7 DTO 向后兼容✓ P8 断言人审✓ P10 跨文件一致性✓
