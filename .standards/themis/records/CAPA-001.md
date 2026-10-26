# CAPA-001 条件清偿单（rr: RR-001 · rpt: RPT-001）
id: CAPA-001
state: closed     # 2026-10-16 当日批闭环（用户终裁执行 13:08→批完成）
closure_evidence: V1.9.28 执行批——
  - action1: meta_check+aegis_check 双文件 yaml 脱钩（**执行批范围修正：G0 审计新抓 aegis_check 第二处违纪·REV-001 补记**）；
    _mini_yaml.py V3 纯标准库解析器（.standards 根·双脚本共享）；4 模板全量对账逐键一致（form-classification/RC/FP/VR）
  - action2-4: 调研报告三处条款吸收（§1.2 表述收紧/§四3 供应链条款/§四5 验证协议）
  - 验证: 3.12.14+3.13.5 双版本 220/220 PASS（217→220 门禁只增）；meta_check 46/46；aegis_check 44/44；bash -n 三件语法过
owner: 老鲁
due: 终裁后当日批
actions:
  - {id: 1, ref: F-001-01, action: meta_check yaml 硬依赖解除——受控子集 mini-parser（纯标准库）, verify: 现存 meta 模板全量回放逐键对账==yaml.safe_load}
  - {id: 2, ref: F-001-02, action: 调研报告 §1.2 表述收紧（静态推定+两端夹逼·未逐版本实测）}
  - {id: 3, ref: F-001-03, action: 报告 §四3 补供应链条款（未来启用 B 必须哈希白名单+META 判型）}
  - {id: 4, ref: F-001-04, action: 执行批验证协议补用户面复跑（install 环境探测报版本即验）}
