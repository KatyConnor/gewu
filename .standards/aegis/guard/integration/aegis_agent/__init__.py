# -*- coding: utf-8 -*-
"""AEGIS Agent SDK —— 自研智能体的进程内集成层（AEGIS V1.0 第六章落地）"""
from .guarded_tools import GuardedWorkspace, ToolError, ToolResult
from .stage_controller import StageController, PHASES
from .promptpack import build_system_prompt, build_stage_prompt, LAWS
from .atlas_bridge import AtlasBridge

__all__ = ['GuardedWorkspace', 'ToolError', 'ToolResult',
           'StageController', 'PHASES',
           'build_system_prompt', 'build_stage_prompt', 'LAWS',
           'AtlasBridge']
