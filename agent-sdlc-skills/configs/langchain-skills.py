#!/usr/bin/env python3
"""
LangChain 技能定义 — 软件生命周期角色技能体系
用于将SDLC角色技能转化为LangChain Tool调用逻辑

使用方式：
    from configs.langchain_skills import SDLC_SKILL_REGISTRY, get_tools_for_role
    tools = get_tools_for_role("role-04")  # 获取全栈开发工程师的工具集
"""

from langchain.tools import Tool
from langchain.prompts import PromptTemplate
from typing import List, Dict, Optional
import os, json

# ═══════════════════════════════════════════════════
# 角色定义注册表
# ═══════════════════════════════════════════════════

ROLE_REGISTRY: Dict[str, Dict] = {
    "role-00": {
        "name": "托管业务调研专家",
        "prompt_file": "roles/00-custody-research-expert.md",
        "skills": ["industry-policy-search", "competitor-analysis"],
        "keywords": ["政策", "行业", "同业", "对标", "调研", "托管"],
    },
    "role-01": {
        "name": "业务需求分析师",
        "prompt_file": "roles/01-business-analyst.md",
        "skills": ["market-feasibility-check", "user-requirement-mining"],
        "keywords": ["市场", "竞品", "需求", "可行性", "用户画像"],
    },
    "role-02": {
        "name": "产品经理/产品专家",
        "prompt_file": "roles/02-product-manager.md",
        "skills": ["prd-generation", "user-journey-mapping"],
        "keywords": ["PRD", "产品需求", "功能设计", "原型", "用户体验"],
    },
    "role-03": {
        "name": "资深技术架构师",
        "prompt_file": "roles/03-technical-architect.md",
        "skills": ["system-design-doc", "api-contract-definition"],
        "keywords": ["架构", "技术选型", "API", "系统设计"],
    },
    "role-04": {
        "name": "全栈高级开发工程师",
        "prompt_file": "roles/04-fullstack-developer.md",
        "skills": ["fullstack-implementation", "unit-test-generator", "refactoring-guide"],
        "keywords": ["开发", "代码", "功能", "前后端", "全栈", "重构"],
    },
    "role-05": {
        "name": "安全与漏洞防护专家",
        "prompt_file": "roles/05-security-expert.md",
        "skills": ["vulnerability-scanning", "owasp-top10-audit"],
        "keywords": ["漏洞", "渗透", "安全审计", "应急响应"],
    },
    "role-06": {
        "name": "高级测试与质量专家",
        "prompt_file": "roles/06-testing-expert.md",
        "skills": ["test-case-generation", "test-report-summary"],
        "keywords": ["测试用例", "自动化测试", "性能测试", "质量准出"],
    },
    "role-07": {
        "name": "合规与风控专家",
        "prompt_file": "roles/07-compliance-expert.md",
        "skills": ["regulatory-compliance-check", "data-privacy-audit"],
        "keywords": ["合规", "隐私", "等保", "数据安全"],
    },
    "role-08": {
        "name": "数据与算法分析专家",
        "prompt_file": "roles/08-data-algorithm-expert.md",
        "skills": ["data-metrics-design", "ab-test-analysis"],
        "keywords": ["埋点", "指标", "A/B测试", "算法"],
    },
    "role-09": {
        "name": "技术运维与SRE专家",
        "prompt_file": "roles/09-sre-expert.md",
        "skills": ["cicd-pipeline-config", "incident-response-sop"],
        "keywords": ["部署", "CI/CD", "监控", "SLO", "故障", "容灾"],
    },
    "role-10": {
        "name": "项目管理与敏捷教练",
        "prompt_file": "roles/10-project-manager.md",
        "skills": ["epic-task-breakdown", "project-risk-tracker"],
        "keywords": ["需求拆解", "排期", "里程碑", "敏捷", "站会", "迭代"],
    },
}

# ═══════════════════════════════════════════════════
# 技能定义注册表
# ═══════════════════════════════════════════════════

SKILL_REGISTRY: Dict[str, Dict] = {
    "industry-policy-search": {
        "name": "监管政策检索",
        "description": "定向检索监管机构政策，解读核心要求，标注业务影响",
        "prompt_file": "skills/industry-policy-search.md",
        "bound_roles": ["role-00"],
    },
    "competitor-analysis": {
        "name": "同业对标分析",
        "description": "多维度对标同业机构，输出结构化对比表与提升建议",
        "prompt_file": "skills/competitor-analysis.md",
        "bound_roles": ["role-00"],
    },
    "market-feasibility-check": {
        "name": "市场可行性研判",
        "description": "市场规模测算、增速预判、合规性校验、商业化潜力评估",
        "prompt_file": "skills/market-feasibility-check.md",
        "bound_roles": ["role-01"],
    },
    "user-requirement-mining": {
        "name": "用户需求挖掘",
        "description": "目标用户核心需求挖掘，用户画像初稿，需求清单",
        "prompt_file": "skills/user-requirement-mining.md",
        "bound_roles": ["role-01"],
    },
    "prd-generation": {
        "name": "PRD文档生成",
        "description": "根据需求清单输出完整PRD文档",
        "prompt_file": "skills/prd-generation.md",
        "bound_roles": ["role-02"],
    },
    "user-journey-mapping": {
        "name": "用户旅程图",
        "description": "用户旅程图设计，覆盖触点、情绪曲线、痛点、机会点",
        "prompt_file": "skills/user-journey-mapping.md",
        "bound_roles": ["role-02"],
    },
    "system-design-doc": {
        "name": "架构设计文档",
        "description": "系统架构设计文档，含技术栈选型、分层架构、高可用预案",
        "prompt_file": "skills/system-design-doc.md",
        "bound_roles": ["role-03"],
    },
    "api-contract-definition": {
        "name": "API契约定义",
        "description": "API契约规范文档，含请求参数、响应格式、错误码",
        "prompt_file": "skills/api-contract-definition.md",
        "bound_roles": ["role-03"],
    },
    "fullstack-implementation": {
        "name": "全栈代码实现",
        "description": "前后端+数据库+部署全链路代码实现",
        "prompt_file": "skills/fullstack-implementation.md",
        "bound_roles": ["role-04"],
    },
    "unit-test-generator": {
        "name": "单元测试生成",
        "description": "自动生成单元测试，覆盖正常/边界/异常场景",
        "prompt_file": "skills/unit-test-generator.md",
        "bound_roles": ["role-04"],
    },
    "refactoring-guide": {
        "name": "代码重构方案",
        "description": "代码重构方案设计，匹配设计模式优化质量",
        "prompt_file": "skills/refactoring-guide.md",
        "bound_roles": ["role-04"],
    },
    "vulnerability-scanning": {
        "name": "漏洞扫描",
        "description": "漏洞扫描与风险识别，输出结构化风险台账",
        "prompt_file": "skills/vulnerability-scanning.md",
        "bound_roles": ["role-05"],
    },
    "owasp-top10-audit": {
        "name": "OWASP安全审计",
        "description": "OWASP TOP10安全审计",
        "prompt_file": "skills/owasp-top10-audit.md",
        "bound_roles": ["role-05"],
    },
    "test-case-generation": {
        "name": "测试用例生成",
        "description": "测试用例设计，覆盖正常/边界/异常+AI专项",
        "prompt_file": "skills/test-case-generation.md",
        "bound_roles": ["role-06"],
    },
    "test-report-summary": {
        "name": "测试报告",
        "description": "测试报告输出与质量准出决策",
        "prompt_file": "skills/test-report-summary.md",
        "bound_roles": ["role-06"],
    },
    "regulatory-compliance-check": {
        "name": "合规审查",
        "description": "业务合规性审查，输出合规风险清单",
        "prompt_file": "skills/regulatory-compliance-check.md",
        "bound_roles": ["role-07"],
    },
    "data-privacy-audit": {
        "name": "数据隐私审计",
        "description": "数据隐私保护审计",
        "prompt_file": "skills/data-privacy-audit.md",
        "bound_roles": ["role-07"],
    },
    "data-metrics-design": {
        "name": "指标体系设计",
        "description": "分层指标体系设计与全链路埋点方案",
        "prompt_file": "skills/data-metrics-design.md",
        "bound_roles": ["role-08"],
    },
    "ab-test-analysis": {
        "name": "A/B测试分析",
        "description": "A/B测试方案设计与结果分析",
        "prompt_file": "skills/ab-test-analysis.md",
        "bound_roles": ["role-08"],
    },
    "cicd-pipeline-config": {
        "name": "CI/CD配置",
        "description": "CI/CD流水线配置方案",
        "prompt_file": "skills/cicd-pipeline-config.md",
        "bound_roles": ["role-09"],
    },
    "incident-response-sop": {
        "name": "故障应急响应",
        "description": "故障应急响应标准流程",
        "prompt_file": "skills/incident-response-sop.md",
        "bound_roles": ["role-09"],
    },
    "epic-task-breakdown": {
        "name": "需求拆解",
        "description": "需求拆解为Epic→Story→Task，输出WBS和排期",
        "prompt_file": "skills/epic-task-breakdown.md",
        "bound_roles": ["role-10"],
    },
    "project-risk-tracker": {
        "name": "风险跟踪",
        "description": "项目风险识别、分级、跟踪、闭环",
        "prompt_file": "skills/project-risk-tracker.md",
        "bound_roles": ["role-10"],
    },
}

# ═══════════════════════════════════════════════════
# 路由规则
# ═══════════════════════════════════════════════════

ROUTING_RULES: List[Dict] = [
    {"keywords": ["政策", "行业", "同业", "对标", "调研", "托管"], "roles": ["role-00", "role-01"]},
    {"keywords": ["PRD", "产品需求", "功能设计", "原型", "用户体验"], "roles": ["role-02"]},
    {"keywords": ["架构", "技术选型", "API定义", "系统设计"], "roles": ["role-03"]},
    {"keywords": ["需求拆解", "排期", "里程碑", "敏捷", "站会", "迭代"], "roles": ["role-10"]},
    {"keywords": ["开发", "写代码", "实现功能", "前后端", "全栈", "重构"], "roles": ["role-04"]},
    {"keywords": ["漏洞", "渗透测试", "安全审计", "应急响应"], "roles": ["role-05"]},
    {"keywords": ["测试用例", "自动化测试", "性能测试", "质量准出"], "roles": ["role-06"]},
    {"keywords": ["合规", "隐私", "等保", "数据安全"], "roles": ["role-07"]},
    {"keywords": ["埋点", "指标", "A/B测试", "算法"], "roles": ["role-08"]},
    {"keywords": ["部署", "CI/CD", "监控", "SLO", "故障", "容灾"], "roles": ["role-09"]},
]

# ═══════════════════════════════════════════════════
# 工具函数
# ═══════════════════════════════════════════════════

def route_to_roles(user_input: str) -> List[str]:
    """根据用户输入关键词路由到对应角色"""
    matched_roles = set()
    for rule in ROUTING_RULES:
        for kw in rule["keywords"]:
            if kw in user_input:
                matched_roles.update(rule["roles"])
                break
    return list(matched_roles) if matched_roles else ["role-04"]  # 默认开发角色


def get_role_prompt(role_id: str) -> str:
    """读取角色定义文件作为系统提示词"""
    role = ROLE_REGISTRY.get(role_id)
    if not role:
        raise ValueError(f"Unknown role_id: {role_id}")
    prompt_path = os.path.join(os.path.dirname(__file__), "..", role["prompt_file"])
    with open(prompt_path, "r", encoding="utf-8") as f:
        return f.read()


def get_skill_prompt(skill_name: str) -> str:
    """读取技能模块文件"""
    skill = SKILL_REGISTRY.get(skill_name)
    if not skill:
        raise ValueError(f"Unknown skill: {skill_name}")
    prompt_path = os.path.join(os.path.dirname(__file__), "..", skill["prompt_file"])
    with open(prompt_path, "r", encoding="utf-8") as f:
        return f.read()


def get_tools_for_role(role_id: str) -> List[Tool]:
    """获取指定角色的LangChain工具集"""
    role = ROLE_REGISTRY.get(role_id)
    if not role:
        raise ValueError(f"Unknown role_id: {role_id}")

    tools = []
    for skill_name in role["skills"]:
        skill = SKILL_REGISTRY[skill_name]
        prompt = get_skill_prompt(skill_name)

        def make_func(s_prompt, s_name):
            def _run(query: str) -> str:
                return f"[Skill: {s_name}]\nPrompt:\n{s_prompt}\n\nInput: {query}\n(请基于上述技能定义执行任务)"
            return _run

        tool = Tool(
            name=skill["name"],
            description=skill["description"],
            func=make_func(prompt, skill_name),
        )
        tools.append(tool)

    return tools


def get_all_tools() -> List[Tool]:
    """获取所有技能工具"""
    tools = []
    for skill_name in SKILL_REGISTRY:
        skill = SKILL_REGISTRY[skill_name]
        prompt = get_skill_prompt(skill_name)

        def make_func(s_prompt, s_name):
            def _run(query: str) -> str:
                return f"[Skill: {s_name}]\n{s_prompt}\n\nInput: {query}"
            return _run

        tool = Tool(
            name=skill["name"],
            description=skill["description"],
            func=make_func(prompt, skill_name),
        )
        tools.append(tool)
    return tools


# ═══════════════════════════════════════════════════
# 使用示例
# ═══════════════════════════════════════════════════

if __name__ == "__main__":
    # 示例1：根据用户输入路由到角色
    user_input = "我需要设计一个托管业务的PRD文档"
    roles = route_to_roles(user_input)
    print(f"用户输入: {user_input}")
    print(f"匹配角色: {[ROLE_REGISTRY[r]['name'] for r in roles]}")

    # 示例2：获取角色的工具集
    for role_id in roles:
        tools = get_tools_for_role(role_id)
        print(f"\n{ROLE_REGISTRY[role_id]['name']} 的工具集:")
        for t in tools:
            print(f"  - {t.name}: {t.description}")

    # 示例3：导出注册表为JSON
    export = {
        "roles": {k: {"name": v["name"], "skills": v["skills"]} for k, v in ROLE_REGISTRY.items()},
        "skills": {k: {"name": v["name"], "bound_roles": v["bound_roles"]} for k, v in SKILL_REGISTRY.items()},
        "total_roles": len(ROLE_REGISTRY),
        "total_skills": len(SKILL_REGISTRY),
    }
    print(f"\n体系总览: {export['total_roles']}角色, {export['total_skills']}技能")
