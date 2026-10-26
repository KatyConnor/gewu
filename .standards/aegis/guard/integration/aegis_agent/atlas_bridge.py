# -*- coding: utf-8 -*-
"""AtlasBridge —— 修复作业事件留痕（对接 ATLAS V1.0 TraceEvent 形态）

事件类型沿用规范的 x- 扩展位:
  x-fix.stage    阶段推进          x-fix.guard   护栏检查结果
  x-fix.escalate 安灯升级(critical) decision.required 审批触发
留痕即审计: 三单、diff、门禁日志按 ATLAS L2 呈报（规范 9.2）。
"""
import datetime
import json
import os
import uuid


class AtlasBridge(object):

    def __init__(self, out_path, agent_id='agent-self-dev-01',
                 agent_type='coding-agent', project='demo-repo'):
        self.out_path = out_path
        self.agent = {'agent_id': agent_id, 'agent_type': agent_type,
                      'version': 'aegis-integration/1.0'}
        self.project = project
        self.events = []

    def emit(self, event_type, payload, severity='info', context=None):
        rec = {
            'event_id': str(uuid.uuid4()),
            'event_type': event_type,
            'severity': severity,
            'timestamp': datetime.datetime.now(
                datetime.timezone.utc).astimezone().isoformat(timespec='seconds'),
            'agent': self.agent,
            'context': dict({'project': self.project}, **(context or {})),
            'payload': payload,
        }
        self.events.append(rec)
        d = os.path.dirname(self.out_path)
        if d:
            os.makedirs(d, exist_ok=True)
        with open(self.out_path, 'a', encoding='utf-8') as f:
            f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        return rec

    def by_type(self, event_type):
        return [e for e in self.events if e['event_type'] == event_type]
