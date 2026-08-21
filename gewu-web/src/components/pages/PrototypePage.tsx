'use client';
import { useState, useRef, useEffect, useCallback, memo } from 'react';
import { Plus, Eye, Clock, Send, Bot, User, ArrowLeft, Check, Smartphone, Monitor, X, Loader2 } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import CustomSelect from '@/components/ui/Select';
import { Message } from '@/types';
import { listMyProjects, listPhaseDocuments, type PhaseDocumentDTO } from '@/lib/project';

const versionData = [
  { v: 'v3', date: '2026-07-10', desc: '优化布局，增加交互动画', changes: [{ type: 'add' as const, text: '新增支付方式选择器组件' }, { type: 'mod' as const, text: '优化表单布局与间距' }, { type: 'add' as const, text: '增加交互动画效果' }] },
  { v: 'v2', date: '2026-07-08', desc: '增加支付方式选择组件', changes: [{ type: 'add' as const, text: '新增支付方式选择组件' }, { type: 'add' as const, text: '新增支付密码输入框' }, { type: 'mod' as const, text: '表单字段从 2 个增加到 4 个' }] },
  { v: 'v1', date: '2026-07-05', desc: '初始版本：支付表单基础布局', changes: [{ type: 'add' as const, text: '创建基础支付表单' }] },
];

const mentionOptions = ['优化布局', '增加交互', '调整配色', '添加表单验证', '生成响应式布局', '添加加载动画'];
const skillOptions = ['布局生成', '组件库', '交互设计', '图标库', '配色方案', '响应式适配', '动效设计'];
const aiResponses = [
  '已为你生成金融科技领域竞争格局分析，包含：\n1. 支付领域竞品功能矩阵对比\n2. 财富管理产品差异化分析\n\n请告诉我需要深入的方向。',
  '已根据你的要求生成新版本，主要更新：\n1. 优化组件布局\n2. 调整交互逻辑\n3. 完善视觉细节\n\n请在右侧预览。',
  '已处理你的请求：\n1. 优化页面结构与信息层级\n2. 增加交互反馈与状态提示\n3. 完善响应式布局适配',
];

type View = 'list' | 'edit' | 'preview';

const PrototypeCanvas = memo(function PrototypeCanvas() {
  return (
    <div className="bg-white rounded-xl shadow-2xl w-[375px] h-[667px] p-4">
      <div className="text-center mb-4"><div className="w-10 h-10 rounded-full bg-blue-500 mx-auto mb-2 flex items-center justify-center"><span className="text-white text-sm font-bold">¥</span></div><p className="text-sm font-semibold text-gray-800">确认支付</p></div>
      <div className="space-y-3">
        <div className="bg-gray-50 rounded-lg p-3"><p className="text-xs text-gray-500">支付金额</p><p className="text-lg font-bold text-gray-900">¥ 299.00</p></div>
        <div className="bg-gray-50 rounded-lg p-3"><p className="text-xs text-gray-500 mb-2">支付方式</p><div className="space-y-2"><div className="flex items-center gap-2 p-2 border-2 border-blue-500 rounded-lg bg-blue-50"><div className="w-6 h-6 rounded bg-blue-500" /><span className="text-xs text-gray-800">招商银行 (****8888)</span></div><div className="flex items-center gap-2 p-2 border border-gray-200 rounded-lg"><div className="w-6 h-6 rounded bg-green-500" /><span className="text-xs text-gray-800">支付宝</span></div></div></div>
        <button className="w-full py-2.5 bg-blue-500 text-white text-sm rounded-lg font-medium">立即支付</button>
      </div>
    </div>
  );
});

export default function PrototypePage() {
  const [view, setView] = useState<View>('list');
  const [messages, setMessages] = useState<Message[]>([
    { id: '1', role: 'ai', content: '你好！我是原型设计助手。请描述你想要的原型，我会帮你生成。', timestamp: '10:00' },
  ]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamText, setStreamText] = useState('');
  const [showVersions, setShowVersions] = useState(false);
  const [showMention, setShowMention] = useState(false);
  const [showSkillDropdown, setShowSkillDropdown] = useState(false);
  const [selectedSkills, setSelectedSkills] = useState(['布局生成', '交互设计']);
  const [compareVer, setCompareVer] = useState<{ v1: number; v2: number } | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const streamRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const responseIndex = useRef(0);
  const toast = useToast();
  // 原型产物列表（真实数据：项目 DESIGN 阶段文档）
  const [prototypes, setPrototypes] = useState<PhaseDocumentDTO[]>([]);
  const [listLoading, setListLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setListLoading(true);
      try {
        const page = await listMyProjects({ page: 1, size: 20 });
        const docs: PhaseDocumentDTO[] = [];
        for (const p of (page.records || []).slice(0, 10)) {
          try {
            const phaseDocs = await listPhaseDocuments(p.projectId, 'DESIGN');
            docs.push(...phaseDocs.filter(d => d.docType === 'prototype' || d.docType === 'html'));
          } catch { /* 单项目失败不阻塞 */ }
        }
        if (!cancelled) setPrototypes(docs);
      } catch { /* 静默空态 */ }
      finally { if (!cancelled) setListLoading(false); }
    })();
    return () => { cancelled = true; };
  }, []);

  // C1 Fix: Cleanup interval on unmount
  useEffect(() => { return () => { if (streamRef.current) clearInterval(streamRef.current); }; }, []);
  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages, streamText]);

  const simulateStreaming = useCallback((fullText: string) => {
    setIsStreaming(true);
    setStreamText('');
    let i = 0;
    streamRef.current = setInterval(() => {
      if (i < fullText.length) { setStreamText(fullText.substring(0, i + 1)); i++; }
      else {
        if (streamRef.current) { clearInterval(streamRef.current); streamRef.current = null; }
        setIsStreaming(false);
        setMessages(prev => [...prev, { id: Date.now().toString(), role: 'ai', content: fullText, timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }]);
        setStreamText('');
      }
    }, 30);
  }, []);

  const sendMessage = () => {
    if (!input.trim() || isStreaming) return;
    setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user' as const, content: input, timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }]);
    setInput(''); setShowMention(false);
    const response = aiResponses[responseIndex.current % aiResponses.length];
    responseIndex.current++;
    setTimeout(() => simulateStreaming(response), 500);
  };

  const handleInputChange = (val: string) => {
    setInput(val);
    const lastAt = val.lastIndexOf('@');
    setShowMention(lastAt !== -1 && !val.substring(lastAt + 1).includes(' '));
  };

  const insertMention = (text: string) => {
    const lastAt = input.lastIndexOf('@');
    setInput(input.substring(0, lastAt) + '@' + text + ' ');
    setShowMention(false);
  };

  const toggleSkill = (skill: string) => setSelectedSkills(prev => prev.includes(skill) ? prev.filter(s => s !== skill) : [...prev, skill]);

  if (view === 'list') {
    return (
      <div>
        <header className="flex items-center justify-between mb-8"><div><h1 className="text-2xl font-semibold text-ink-50">原型设计</h1><p className="text-ink-400 text-sm mt-1">通过 AI 对话快速生成产品原型</p></div><button onClick={() => setView('edit')} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"><Plus className="w-4 h-4" />新建原型</button></header>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {listLoading && (
            <div className="col-span-3 flex items-center justify-center py-16 text-ink-500">
              <Loader2 className="w-6 h-6 animate-spin mr-2" />加载原型产物...
            </div>
          )}
          {!listLoading && prototypes.length === 0 && (
            <div className="col-span-3 text-center py-16 text-ink-500 text-sm">
              暂无原型产物，在项目设计阶段上传原型文档后在此展示
            </div>
          )}
          {prototypes.map(p => (
            <div key={p.id} className="glass-dark rounded-xl overflow-hidden card-hover group">
              <div className="h-32 bg-gradient-to-br from-ink-800 to-ink-900 flex items-center justify-center cursor-pointer" onClick={() => setView('edit')}><Monitor className="w-10 h-10 text-ink-600 group-hover:text-tech-400 transition-colors" /></div>
              <div className="p-4">
                <div className="flex items-center justify-between mb-1.5"><h3 className="text-sm font-semibold text-ink-50 truncate">{p.docName}</h3><span className="text-[10px] px-1.5 py-0.5 rounded bg-tech-500/10 text-tech-400">v{p.currentVersion} {p.reviewStatusDesc || '草稿'}</span></div>
                <div className="flex items-center gap-2 mt-2"><div className="w-5 h-5 rounded-full bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center text-[8px] text-white">{(p.createdBy || '?')[0].toUpperCase()}</div><span className="text-[10px] text-ink-200">{p.createdBy || '-'}</span><span className="text-[10px] text-ink-400 ml-auto">历史 {p.totalVersions} 版</span></div>
                <div className="flex items-center gap-2 mt-1.5"><Clock className="w-3 h-3 text-ink-300" /><span className="text-[10px] text-ink-300">{p.createdAt ? new Date(p.createdAt > 1e12 ? p.createdAt : p.createdAt * 1000).toLocaleDateString('zh-CN') : '-'}</span><span className="text-[10px] text-ink-300 ml-auto">{p.phaseCode}</span></div>
                <div className="flex items-center gap-2 mt-3 pt-3 border-t border-glass-border"><button onClick={() => setView('preview')} className="flex-1 py-1.5 text-[10px] text-tech-400 border border-tech-500/15 rounded hover:bg-tech-500/10 transition-all">预览</button><button onClick={() => toast('需求文档生成中...', 'info')} className="flex-1 py-1.5 text-[10px] text-gold-400 border border-gold-500/15 rounded hover:bg-gold-500/10 transition-all">需求文档</button></div>
                <div className="mt-2 pt-2 border-t border-glass-border"><button onClick={() => { setView('edit'); setShowVersions(true); }} className="w-full flex items-center justify-between text-[10px] text-ink-300 hover:text-tech-400 transition-colors"><span className="flex items-center gap-1.5"><Clock className="w-3 h-3" />版本历史</span><span>v{p.currentVersion}</span></button></div>
              </div>
            </div>
          ))}
          <div onClick={() => setView('edit')} className="glass-dark rounded-xl overflow-hidden card-hover cursor-pointer border-2 border-dashed border-ink-700 hover:border-tech-500/30"><div className="h-32 flex flex-col items-center justify-center gap-2"><div className="w-10 h-10 rounded-full bg-ink-800 flex items-center justify-center"><Plus className="w-5 h-5 text-ink-500" /></div><span className="text-xs text-ink-500">创建新原型</span></div></div>
        </div>
      </div>
    );
  }

  if (view === 'preview') {
    return (
      <div className="flex h-[calc(100vh-7rem)]">
        <div className="flex-1 flex flex-col bg-ink-900/30">
          <div className="p-3 border-b border-glass-border flex items-center justify-between"><div className="flex items-center gap-2 text-xs text-ink-400"><Eye className="w-4 h-4 text-gold-400" />预览模式</div><div className="flex items-center gap-2"><button onClick={() => setView('list')} className="flex items-center gap-1.5 px-3 py-1.5 text-xs border border-tech-500/15 text-ink-300 rounded-lg hover:bg-ink-800/50"><ArrowLeft className="w-3.5 h-3.5" />返回</button><button onClick={() => setView('edit')} className="flex items-center gap-1.5 px-3 py-1.5 text-xs border border-tech-500/15 text-tech-300 rounded-lg hover:bg-ink-800/50">编辑</button><button onClick={() => { toast('原型已定稿！', 'success'); setView('list'); }} className="flex items-center gap-1.5 px-3 py-1.5 text-xs btn-primary text-white rounded-lg"><Check className="w-3.5 h-3.5" />定稿</button></div></div>
          <div className="flex-1 flex items-center justify-center p-8 overflow-auto"><PrototypeCanvas /></div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-[calc(100vh-7rem)]">
      <div className="w-80 border-r border-glass-border flex flex-col bg-ink-950/50 h-full relative z-10">
        <div className="p-3 border-b border-glass-border flex-shrink-0">
          <div className="flex items-center justify-between"><h3 className="text-sm font-semibold text-ink-50">Q3 产品规划原型</h3><div className="flex items-center gap-2"><span className="text-[10px] px-1.5 py-0.5 bg-tech-500/10 text-tech-400 rounded">v3</span><button onClick={() => setView('list')} className="text-ink-500 hover:text-ink-300"><ArrowLeft className="w-3.5 h-3.5" /></button></div></div>
        </div>
        <div className="flex-1 overflow-y-auto scrollbar-thin p-4 pb-6 space-y-4 min-h-0">
          {messages.map(msg => (
            <div key={msg.id} className={`flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''}`}>
              {msg.role === 'ai' && <div className="w-7 h-7 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0"><Bot className="w-3.5 h-3.5 text-white" /></div>}
              <div className={`flex-1 max-w-[85%] ${msg.role === 'user' ? 'flex flex-col items-end' : ''}`}>
                <div className={`rounded-xl p-3 text-xs leading-relaxed ${msg.role === 'ai' ? 'chat-bubble-ai rounded-tl-sm' : 'chat-bubble-user rounded-tr-sm'}`}><p className="text-ink-100 whitespace-pre-wrap">{msg.content}</p></div>
                <span className="text-[10px] text-ink-500 mt-1 block">{msg.timestamp}</span>
              </div>
              {msg.role === 'user' && <div className="w-7 h-7 rounded-full bg-gradient-to-br from-tech-400 to-cyber-500 flex items-center justify-center flex-shrink-0"><User className="w-3.5 h-3.5 text-white" /></div>}
            </div>
          ))}
          {isStreaming && <div className="flex gap-3"><div className="w-7 h-7 rounded-md bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0"><Bot className="w-3.5 h-3.5 text-white" /></div><div className="flex-1"><div className="chat-bubble-ai rounded-xl rounded-tl-sm p-4"><p className="text-sm text-ink-100 leading-relaxed whitespace-pre-wrap">{streamText}<span className="inline-block w-1.5 h-4 bg-tech-400 ml-0.5 animate-pulse" /></p></div></div></div>}
          <div ref={bottomRef} />
        </div>
        <div className="border-t border-glass-border flex-shrink-0">
          <div className="px-4 pb-2 pt-3 relative">
            <textarea value={input} onChange={e => handleInputChange(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }} rows={2} placeholder="输入 @ 快速插入提示词..." className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none pr-10" disabled={isStreaming} />
            {showMention && <div className="absolute bottom-full left-4 right-4 mb-1 glass-dark rounded-lg border border-tech-500/15 shadow-xl overflow-hidden z-50 max-h-36 overflow-y-auto">{mentionOptions.map(opt => <button key={opt} onClick={() => insertMention(opt)} className="w-full text-left px-3 py-2 text-xs text-ink-200 hover:bg-tech-500/10 flex items-center gap-2"><span className="text-tech-400 font-mono">@</span>{opt}</button>)}</div>}
            <button onClick={sendMessage} disabled={isStreaming || !input.trim()} className="absolute bottom-3.5 right-5 p-1 btn-primary text-white rounded-md disabled:opacity-50"><Send className="w-3 h-3" /></button>
          </div>
          <div className="px-4 pb-3 space-y-2">
            <div className="flex gap-2"><div className="flex-1 flex items-center gap-1.5"><span className="text-[10px] text-ink-500">模型</span><CustomSelect value="gpt4o" onChange={() => {}} className="flex-1" options={[{ value: 'gpt4o', label: 'GPT-4o' }, { value: 'claude', label: 'Claude 3.5' }]} /></div><div className="flex-1 flex items-center gap-1.5"><span className="text-[10px] text-ink-500">模式</span><CustomSelect value="balanced" onChange={() => {}} className="flex-1" options={[{ value: 'fast', label: '快速' }, { value: 'balanced', label: '均衡' }, { value: 'quality', label: '高质量' }]} /></div></div>
            <div className="flex items-center gap-2"><span className="text-[10px] text-ink-500 w-10">Agent</span><CustomSelect value="design" onChange={() => {}} className="flex-1" options={[{ value: 'design', label: '原型设计专家' }, { value: 'ui', label: 'UI 设计师' }]} /></div>
            <div className="flex items-center gap-2 relative" onClick={e => e.stopPropagation()}>
              <span className="text-[10px] text-ink-500 w-10">Skill</span>
              <button onClick={() => setShowSkillDropdown(!showSkillDropdown)} className="flex-1 px-2 py-1 bg-ink-800/50 border border-tech-500/10 rounded text-[10px] text-ink-200 text-left flex items-center justify-between min-h-[24px]">
                <span className="truncate">{selectedSkills.join(', ')}{selectedSkills.length > 2 && <span className="text-ink-500"> (+{selectedSkills.length - 2})</span>}</span>
              </button>
              {showSkillDropdown && <div className="absolute top-full left-12 right-0 mt-1 glass-dark rounded-lg border border-tech-500/15 shadow-xl z-[99999] p-2 space-y-1 max-h-40 overflow-y-auto">{skillOptions.map(s => <label key={s} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-ink-800/40 cursor-pointer"><input type="checkbox" checked={selectedSkills.includes(s)} onChange={() => toggleSkill(s)} className="w-3 h-3 rounded accent-tech-500" /><span className="text-[10px] text-ink-200">{s}</span></label>)}</div>}
            </div>
          </div>
        </div>
      </div>

      <div className="flex-1 flex flex-col bg-ink-900/30">
        <div className="p-3 border-b border-glass-border flex items-center justify-between"><div className="flex items-center gap-2"><button className="p-1.5 rounded-md bg-tech-500/15 text-tech-400"><Smartphone className="w-4 h-4" /></button><button className="p-1.5 rounded-md text-ink-500 hover:text-ink-300"><Monitor className="w-4 h-4" /></button></div><div className="flex items-center gap-2"><button onClick={() => setShowVersions(!showVersions)} className="flex items-center gap-1.5 px-3 py-1.5 text-xs border border-tech-500/15 text-ink-300 rounded-lg hover:bg-ink-800/50"><Clock className="w-3.5 h-3.5" />版本历史</button><button onClick={() => setView('preview')} className="flex items-center gap-1.5 px-3 py-1.5 text-xs btn-primary text-white rounded-lg"><Eye className="w-3.5 h-3.5" />预览</button></div></div>
        <div className="flex-1 flex items-center justify-center p-8 overflow-auto"><PrototypeCanvas /></div>
      </div>

      {showVersions && (
        <div className="w-72 border-l border-glass-border flex flex-col bg-ink-950/50">
          <div className="p-3 border-b border-glass-border flex items-center justify-between"><h3 className="text-sm font-semibold text-ink-50">版本历史</h3><button onClick={() => { setShowVersions(false); setCompareVer(null); }} className="text-ink-500 hover:text-ink-300"><X className="w-4 h-4" /></button></div>
          <div className="flex-1 overflow-y-auto scrollbar-thin p-3 space-y-2">
            {versionData.map((v, i) => (
              <div key={v.v} className={`p-3 rounded-lg cursor-pointer ${i === 0 ? 'bg-tech-500/10 border border-tech-500/20' : 'bg-ink-800/30 border border-transparent hover:border-tech-500/10'}`}>
                <div className="flex items-center justify-between mb-1"><span className={`text-xs font-medium ${i === 0 ? 'text-tech-400' : 'text-ink-200'}`}>{v.v}{i === 0 && <span className="ml-1 text-[10px] px-1.5 py-0.5 bg-green-500/10 text-green-400 rounded">当前</span>}</span><span className="text-[10px] text-ink-400">{v.date}</span></div>
                <p className="text-[10px] text-ink-300 mb-2">{v.desc}</p>
                {i > 0 && <button onClick={() => setCompareVer({ v1: i, v2: i - 1 })} className="text-[10px] text-tech-400 hover:underline">对比 {versionData[i-1].v}</button>}
              </div>
            ))}
          </div>
          {compareVer && (
            <div className="border-t border-glass-border p-3">
              <p className="text-xs text-ink-300 mb-2 font-medium">{versionData[compareVer.v1].v} vs {versionData[compareVer.v2].v} 差异</p>
              <div className="space-y-1.5">{versionData[compareVer.v1].changes.map((c, i) => <div key={i} className={`flex items-start gap-2 text-[10px] ${c.type === 'add' ? 'text-green-400' : 'text-gold-400'}`}><span>{c.type === 'add' ? '+' : '~'}</span><span>{c.text}</span></div>)}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
