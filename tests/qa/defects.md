# 缺陷清单
| ID | 级别 | 描述 | 处置 |
|---|---|---|---|
| D-1 | Low（测试债务） | 本批新增 Service/Controller 层守卫（requireSandboxOwner/checkMembership/requireGraphOwner）无 mock 单测 | 建议补测（Mockito 基建），随迭代 |
| D-2 | Low（既有欠账） | SM2Util/PasswordPolicy 行覆盖 0%（crypto 包口径 FAIL 的构成项，非本批引入） | 建议补测，随迭代 |
| D-3 | Info | coverage_gate.py 四格式不含 JaCoCo | 本轮以转换器补互操作；建议 UTSS 侧登记 Java 生态格式 |
