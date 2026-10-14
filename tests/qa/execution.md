# 执行记录
| 用例 | 结果 | 证据 |
|---|---|---|
| TC-00a~c, TC-01~05, TC-08~12 | PASS | mvn EXIT=0；surefire 汇总 0F0E；gate 输出（本批面 84.4%）；/tmp/common.lcov |
| TC-06 | NOT-RUN | Service/Controller 层缺 mock 单测基建 → D-1 |
| TC-07（运行时半程） | BLOCKED | 起服需 DB/env，端到端 BLOCKED（S2 裁剪声明） |
| TC-09（运行时半程） | BLOCKED | 同上 |
环境：用户空间工具链 ~/toolchain（JDK21/Maven3.9.9/Node22）；Jacoco→lcov 转换器 /tmp/jacoco2lcov.py（UTSS 四格式不含 JaCoCo 的互操作补丁）
