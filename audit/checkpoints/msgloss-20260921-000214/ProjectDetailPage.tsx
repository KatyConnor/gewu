'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import { ArrowLeft, Play, Check, RotateCcw, Upload, FileText, History, Send, Bot, MessageSquare } from 'lucide-react';
import { useDispatch } from 'react-redux';
import { setPage } from '@/store';
import { useToast } from '@/components/ui/Toast';
import { getProject, getProjectPhases, startPhase, completePhase, revertPhase,
  listPhaseDocuments, uploadDocument, getDocumentContent, updateDocumentContent,
  deleteDocument, getDocumentVersions, submitForReview, createMarkdownDoc,
  type ProjectDTO, type ProjectPhaseDTO, type PhaseDocumentDTO } from '@/lib/project';
import { listMySessions, createSession, updateSession, listMessages,
  type SessionDTO } from '@/lib/session';
import { chatStream } from '@/lib/chat';
import { listActiveModels, type ModelConfig } from '@/lib/model-config';
import MarkdownRenderer from '@/components/ui/MarkdownRenderer';
import CustomSelect from '@/components/ui/Select';
import AIProcessTimeline from './AIProcessTimeline';
import { createProcessStreamHandler, type ProcessSnapshot, type ProcessItem } from '@/lib/agentProcess';

const phaseDisplayMap: Record<string, string> = {
  RESEARCH: '项目调研', PROTOTYPE: '原型设计', INITIATION: '立项中',
  REQUIREMENT_REVIEW: '需求评审', DESIGN_REVIEW: '设计评审', ESTIMATION: '工作量评估',
  PLANNING: '计划制定', DEVELOPMENT: '开发中', SELF_TEST: '开发自测',
  SMOKE_TEST: '冒烟测试', PENDING_SIT: '待SIT测试', SIT_TEST: 'SIT测试',
  PENDING_UAT: '待UAT测试', UAT_TEST: 'UAT测试', PENDING_RELEASE: '等待上线',
  RELEASED: '上线完成', CLOSED: '已结项',
};

const agentModeOptions = [
  { value: 'assistant', label: '助手模式' },
  { value: 'expert', label: '专家模式' },
  { value: 'creative', label: '创意模式' },
  { value: 'precise', label: '精确模式' },
];

const thinkingStyleOptions = [
  { value: 'chain-of-thought', label: '链式推理' },
  { value: 'tree-of-thought', label: '树状推理' },
  { value: 'react', label: 'ReAct' },
  { value: 'step-by-step', label: '逐步分析' },
  { value: 'socratic', label: '苏格拉底式' },
];

const phaseStatusColors: Record<number, string> = {
  0: 'bg-ink-700 text-ink-500',
  1: 'bg-tech-500/20 text-tech-400',
  2: 'bg-green-500/20 text-green-400',
};

const phaseStatusBorder: Record<number, string> = {
  0: 'border-ink-600',
  1: 'border-tech-500',
  2: 'border-green-500',
};

interface EditPanel {
  documentId: string;
  content: string;
  versions: { versionNo: number; changeSource: string; changeSummary: string }[];
  showVersions?: boolean;
  comparedVersion?: number;
  comparedContent?: string;
}

interface ChatPanelMessage {
  role: 'user' | 'ai';
  content: string;
  process?: ProcessItem[];
  processMs?: number;
  processExpanded?: boolean;
}

interface ChatPanel {
  show: boolean;
  messages: ChatPanelMessage[];
  input: string;
  streaming: boolean;
  streamText: string;
  streamProcess: ProcessSnapshot | null;
}

export default function ProjectDetailPage({ projectId, onBack }: { projectId: string; onBack: () => void }) {
  const [project, setProject] = useState<ProjectDTO | null>(null);
  const [phases, setPhases] = useState<ProjectPhaseDTO[]>([]);
  const [selectedPhase, setSelectedPhase] = useState<string | null>(null);
  const [documents, setDocuments] = useState<PhaseDocumentDTO[]>([]);
  const [editPanel, setEditPanel] = useState<EditPanel | null>(null);
  const [chatPanel, setChatPanel] = useState<ChatPanel>({ show: false, messages: [], input: '', streaming: false, streamText: '', streamProcess: null });
  const [jumpingToChat, setJumpingToChat] = useState(false);

  // 模型/模式/思维选择
  const [modelOptions, setModelOptions] = useState<{ value: string; label: string }[]>([]);
  const [selectedModel, setSelectedModel] = useState('');
  const [agentMode, setAgentMode] = useState('assistant');
  const [thinkingStyle, setThinkingStyle] = useState('chain-of-thought');

  // 当前阶段的会话管理
  const [phaseSessionId, setPhaseSessionId] = useState<string | null>(null);
  const [phaseSessionTitle, setPhaseSessionTitle] = useState('');

  const toast = useToast();
  const dispatch = useDispatch();

  const loadData = useCallback(async () => {
    try {
      const [p, ph] = await Promise.all([getProject(projectId), getProjectPhases(projectId)]);
      setProject(p);
      setPhases(ph);
      if (ph.length > 0 && !selectedPhase) {
        const active = ph.find(x => x.status === 1) || ph[0];
        setSelectedPhase(active.phaseCode);
      }
    } catch { toast('加载失败', 'error'); }
  }, [projectId]);

  useEffect(() => { loadData(); }, [loadData]);

  // 加载模型列表
  useEffect(() => {
    (async () => {
      try {
        const models: ModelConfig[] = await listActiveModels();
        const options = models.map(m => ({ value: m.modelId, label: m.modelName }));
        setModelOptions(options);
        if (options.length > 0 && !selectedModel) {
          setSelectedModel(options[0].value);
        }
      } catch (err) {
        console.error('模型列表加载失败:', err);
        setModelOptions([]);
      }
    })();
  }, []);

  // 加载当前阶段的最近会话
  const loadPhaseSession = useCallback(async (phaseCode: string) => {
    try {
      const result = await listMySessions(1, 50);
      const sessions = result.records || [];
      // 筛选当前项目 + 当前阶段的会话
      const phaseName = phaseDisplayMap[phaseCode] || phaseCode;
      const phaseSessions = sessions.filter(s =>
        s.projectId === projectId &&
        s.title.includes(phaseName)
      );
      if (phaseSessions.length > 0) {
        // 取最近的一个会话
        const latest = phaseSessions.sort((a, b) =>
          (b.lastMessageAt || b.createdAt) - (a.lastMessageAt || a.createdAt)
        )[0];
        setPhaseSessionId(latest.sessionId);
        setPhaseSessionTitle(latest.title);
        // 加载该会话的消息
        const msgResult = await listMessages(latest.sessionId, 1, 100);
        const msgs = (msgResult.records || []).map(m => ({
          role: m.messageType === 'assistant' ? 'ai' as const : 'user' as const,
          content: m.content,
        }));
        setChatPanel(prev => ({ ...prev, messages: msgs }));
      } else {
        setPhaseSessionId(null);
        setPhaseSessionTitle('');
        setChatPanel(prev => ({ ...prev, messages: [] }));
      }
    } catch (err) {
      console.error('加载阶段会话失败:', err);
    }
  }, [projectId]);

  // 当阶段切换时，加载对应的会话
  useEffect(() => {
    if (selectedPhase) {
      loadPhaseSession(selectedPhase);
    }
  }, [selectedPhase, loadPhaseSession]);

  const loadDocuments = useCallback(async (phaseCode: string) => {
    try {
      const docs = await listPhaseDocuments(projectId, phaseCode);
      setDocuments(docs);
    } catch { setDocuments([]); }
  }, [projectId]);

  useEffect(() => {
    if (selectedPhase) loadDocuments(selectedPhase);
  }, [selectedPhase, loadDocuments]);

  const handleStart = async (phaseCode: string) => {
    if (!confirm(`确定开始「${phaseDisplayMap[phaseCode] || phaseCode}」阶段？`)) return;
    try { await startPhase(projectId, phaseCode); loadData(); toast('阶段已开始', 'success'); }
    catch { toast('操作失败', 'error'); }
  };

  const handleComplete = async (phaseCode: string) => {
    if (!confirm(`确定完成「${phaseDisplayMap[phaseCode] || phaseCode}」阶段？请确保已上传必要文档。`)) return;
    try { await completePhase(projectId, phaseCode); loadData(); toast('阶段已完成', 'success'); }
    catch (e: unknown) { toast(e instanceof Error ? e.message : '操作失败', 'error'); }
  };

  const handleRevert = async (phaseCode: string) => {
    if (!confirm(`确定回退「${phaseDisplayMap[phaseCode] || phaseCode}」阶段？`)) return;
    try { await revertPhase(projectId, phaseCode); loadData(); toast('阶段已回退', 'success'); }
    catch { toast('操作失败', 'error'); }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !selectedPhase) return;
    try { await uploadDocument(projectId, selectedPhase, file); loadDocuments(selectedPhase); toast('上传成功', 'success'); }
    catch { toast('上传失败', 'error'); }
  };

  const openEditor = async (doc: PhaseDocumentDTO) => {
    try {
      const [content, versions] = await Promise.all([getDocumentContent(doc.id), getDocumentVersions(doc.id)]);
      setEditPanel({ documentId: doc.id, content, versions, showVersions: false });
    } catch { toast('加载文档失败', 'error'); }
  };

  const saveDocument = async () => {
    if (!editPanel) return;
    try { await updateDocumentContent(editPanel.documentId, { content: editPanel.content, changeSummary: '手动编辑' });
      toast('已保存，请提交审核', 'success');
      setEditPanel(null);
      if (selectedPhase) loadDocuments(selectedPhase);
    } catch { toast('保存失败', 'error'); }
  };

  const handleSubmitReview = async (docId: string) => {
    if (!confirm('提交审核后文档将锁定，是否继续？')) return;
    try { await submitForReview(docId); toast('已提交审核', 'success'); if (selectedPhase) loadDocuments(selectedPhase); }
    catch { toast('操作失败', 'error'); }
  };

  const viewVersion = async (docId: string, vno: number) => {
    try {
      const { getVersionContent } = await import('@/lib/project');
      const content = await getVersionContent(docId, vno);
      setEditPanel(prev => prev ? { ...prev, comparedVersion: vno, comparedContent: content } : null);
    } catch { toast('加载失败', 'error'); }
  };

  const handleJumpToChat = async () => {
    if (jumpingToChat) return;
    setJumpingToChat(true);
    try {
      let sessionId = phaseSessionId;
      // 如果当前阶段没有会话，创建一个新的
      if (!sessionId) {
        const { createSession } = await import('@/lib/session');
        const phaseName = selectedPhase ? phaseDisplayMap[selectedPhase] || selectedPhase : '';
        const sessionTitle = `${project?.projectName || '项目'} - ${phaseName || '阶段'}对话`;
        const session = await createSession({
          title: sessionTitle,
          type: 1,
          projectId: projectId,
        });
        sessionId = session.sessionId;
      }
      // 使用 URL hash 传递项目 ID 和会话 ID，让 ChatPage 可以筛选项目会话
      window.location.hash = `#/chat?projectId=${projectId}&sessionId=${sessionId}`;
      dispatch(setPage('chat'));
      toast('已跳转到 ChatPage', 'success');
    } catch {
      toast('跳转失败', 'error');
    } finally {
      setJumpingToChat(false);
    }
  };

  const toggleChatPanel = () => setChatPanel(p => ({ ...p, show: !p.show }));

  const toggleMsgProcess = (idx: number) => {
    setChatPanel(p => ({ ...p, messages: p.messages.map((m, i) => i === idx ? { ...m, processExpanded: !(m.processExpanded ?? false) } : m) }));
  };

  const sendChatMessage = async () => {
    if (!chatPanel.input.trim() || chatPanel.streaming || !selectedPhase) return;
    const userMsg = chatPanel.input;
    const resetStreaming = (p: ChatPanel): ChatPanel => ({ ...p, streaming: false, streamText: '', streamProcess: null });
    setChatPanel(p => ({
      ...p,
      messages: [...p.messages, { role: 'user', content: userMsg }],
      input: '',
      streaming: true,
      streamText: '',
      streamProcess: null,
    }));

    // 如果没有会话 ID，先创建一个
    let currentSessionId = phaseSessionId;
    if (!currentSessionId) {
      try {
        const phaseName = phaseDisplayMap[selectedPhase] || selectedPhase;
        const sessionTitle = `${project?.projectName || '项目'} - ${phaseName || '阶段'}对话`;
        const session = await createSession({
          title: sessionTitle,
          type: 1,
          projectId: projectId,
        });
        currentSessionId = session.sessionId;
        setPhaseSessionId(session.sessionId);
        setPhaseSessionTitle(session.title);
      } catch {
        toast('创建会话失败', 'error');
        setChatPanel(resetStreaming);
        return;
      }
    }

    // 处理过程时间线：思考/工具/搜索事件按发生顺序实时累积渲染
    const proc = createProcessStreamHandler((snapshot) => {
      setChatPanel(p => ({ ...p, streamProcess: snapshot }));
    });

    try {
      let acc = '';
      let firstContent = false;

      await chatStream(
        { message: userMsg, model: selectedModel, agentMode, thinkingStyle, sessionId: currentSessionId },
        {
          ...proc.handlers,
          onContent: (t) => {
            if (!firstContent) {
              firstContent = true;
              // 首段正文输出：闭合当前思考片段
              proc.handlers.onContentStarted();
            }
            proc.tracker.addContent(t);
            acc += t;
            setChatPanel(p => ({ ...p, streamText: acc }));
          },
          onComplete: () => {
            proc.tracker.finish();
            const snapshot = proc.tracker.snapshot();
            // 无内容时显示截断提示（推理模型可能因 token 上限不足未输出正式回复）
            const hasThinking = snapshot.items.some(i => i.kind === 'thinking' && i.text);
            const reply = acc || (hasThinking
              ? '⚠️ AI 生成了思考过程但未能输出正式回复，可能是 token 上限不足导致截断。请尝试增大 max_tokens 或简化问题。'
              : '⚠️ AI 未能输出正式回复，可能是 token 上限不足导致截断。请尝试增大 max_tokens 或简化问题。');
            // 后端 appendChatInteraction 已保存用户消息和 AI 回复，前端无需重复保存
            setChatPanel(p => ({
              ...resetStreaming(p),
              messages: [...p.messages, {
                role: 'ai',
                content: reply,
                process: snapshot.items.length > 0 ? snapshot.items : undefined,
                processMs: snapshot.items.length > 0 ? snapshot.elapsedMs : undefined,
              }],
            }));
          },
          onError: (err) => {
            toast(err, 'error');
            setChatPanel(p => ({
              ...resetStreaming(p),
              messages: [...p.messages, { role: 'ai', content: `⚠️ ${err}` }],
            }));
          },
        }
      );
    } catch { toast('发送失败', 'error'); setChatPanel(resetStreaming); }
  };

  if (!project) return <div className="flex justify-center py-16"><div className="w-6 h-6 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" /></div>;

  return (
    <div className="flex h-[calc(100vh-7rem)]">
      {/* 左侧：阶段时间线 */}
      <div className="w-72 border-r border-tech-500/10 flex flex-col bg-ink-950/30">
        <div className="p-4 border-b border-tech-500/10 flex items-center gap-2">
          <button onClick={onBack} className="text-ink-400 hover:text-ink-200"><ArrowLeft className="w-4 h-4" /></button>
          <div className="flex-1 min-w-0"><h3 className="text-sm font-semibold text-ink-50 truncate">{project.projectName}</h3></div>
        </div>
        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          {phases.map(phase => (
            <div key={phase.phaseCode}
              onClick={() => setSelectedPhase(phase.phaseCode)}
              className={`p-3 rounded-lg cursor-pointer transition-all border ${
                selectedPhase === phase.phaseCode ? 'bg-tech-500/10 border-tech-500/20' : 'border-transparent hover:bg-tech-500/5'
              }`}
            >
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${phase.status === 2 ? 'bg-green-500' : phase.status === 1 ? 'bg-tech-400 animate-pulse' : 'bg-ink-600'}`} />
                <span className="text-xs text-ink-200">{phaseDisplayMap[phase.phaseCode] || phase.phaseCode}</span>
              </div>
              <p className="text-[10px] text-ink-500 mt-1">{phase.statusDesc}
                {phase.documentCount > 0 && ` · ${phase.documentCount}份文档`}
              </p>
            </div>
          ))}
        </div>
      </div>

      {/* 中间：阶段详情 */}
      <div className="flex-1 flex flex-col overflow-hidden">
        <div className="p-4 border-b border-tech-500/10 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-ink-100">{selectedPhase ? phaseDisplayMap[selectedPhase] || selectedPhase : ''}</h3>
          <div className="flex items-center gap-2">
            {selectedPhase && phases.find(p => p.phaseCode === selectedPhase)?.status === 0 && (
              <button onClick={() => handleStart(selectedPhase)} className="flex items-center gap-1 px-3 py-1.5 text-xs bg-tech-500/20 text-tech-400 rounded-lg hover:bg-tech-500/30"><Play className="w-3 h-3" />开始</button>
            )}
            {selectedPhase && phases.find(p => p.phaseCode === selectedPhase)?.status === 1 && (
              <>
              <button onClick={() => handleComplete(selectedPhase)} className="flex items-center gap-1 px-3 py-1.5 text-xs bg-green-500/20 text-green-400 rounded-lg hover:bg-green-500/30"><Check className="w-3 h-3" />完成</button>
              {phases.find(p => p.phaseCode === selectedPhase)?.revertible && (
                <button onClick={() => handleRevert(selectedPhase)} className="flex items-center gap-1 px-3 py-1.5 text-xs bg-gold-500/20 text-gold-400 rounded-lg hover:bg-gold-500/30"><RotateCcw className="w-3 h-3" />回退</button>
              )}
              </>
            )}
            <button onClick={toggleChatPanel} className={`flex items-center gap-1 px-3 py-1.5 text-xs rounded-lg ${chatPanel.show ? 'bg-tech-500/30 text-tech-300' : 'bg-tech-500/10 text-tech-400 hover:bg-tech-500/20'}`}><Bot className="w-3 h-3" />{chatPanel.show ? '隐藏对话' : 'Agent'}</button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          {/* 文档上传区 */}
          <div className="mb-6">
            <div className="flex items-center justify-between mb-3">
              <h4 className="text-sm font-medium text-ink-200">阶段文档</h4>
              <label className="flex items-center gap-1 px-3 py-1.5 text-xs bg-tech-500/10 text-tech-400 rounded-lg cursor-pointer hover:bg-tech-500/20">
                <Upload className="w-3 h-3" />上传
                <input type="file" className="hidden" onChange={handleFileUpload} />
              </label>
            </div>
            {documents.length === 0 ? (
              <p className="text-xs text-ink-500">暂无文档</p>
            ) : (
              <div className="space-y-2">
                {documents.map(doc => (
                  <div key={doc.id} className="flex items-center justify-between p-3 bg-ink-800/30 rounded-lg border border-tech-500/5">
                    <div className="flex items-center gap-2 flex-1 min-w-0">
                      <FileText className="w-4 h-4 text-ink-400 flex-shrink-0" />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm text-ink-200 truncate">{doc.docName}</p>
                        <p className="text-[10px] text-ink-500">v{doc.currentVersion} · {doc.reviewStatusDesc || doc.reviewStatus}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1">
                      {doc.docType === 'md' && (
                        <button onClick={() => openEditor(doc)} className="px-2 py-1 text-[10px] text-tech-400 hover:bg-tech-500/10 rounded">编辑</button>
                      )}
                      {doc.reviewStatus === 'pending' && (
                        <button onClick={() => handleSubmitReview(doc.id)} className="px-2 py-1 text-[10px] text-gold-400 hover:bg-gold-500/10 rounded">提交审核</button>
                      )}
                      <button onClick={async () => { if (confirm('删除此文档？')) { await deleteDocument(doc.id); loadDocuments(selectedPhase!); } }} className="px-2 py-1 text-[10px] text-cinnabar-400 hover:bg-cinnabar-500/10 rounded">删除</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* 文档编辑器弹框 */}
        {editPanel && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={() => setEditPanel(null)}>
            <div className="glass-dark rounded-xl w-full max-w-4xl h-[80vh] flex flex-col border border-tech-500/20" onClick={e => e.stopPropagation()}>
              <div className="p-3 border-b border-tech-500/10 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-ink-100">文档编辑</span>
                  <button onClick={() => setEditPanel(p => p ? { ...p, showVersions: !p.showVersions, comparedVersion: undefined, comparedContent: undefined } : null)} className="text-[10px] text-tech-400 hover:text-tech-300"><History className="w-3 h-3 inline mr-1" />版本历史</button>
                </div>
                <div className="flex items-center gap-2">
                  <button onClick={saveDocument} className="px-3 py-1.5 text-xs bg-tech-500/20 text-tech-400 rounded hover:bg-tech-500/30">保存</button>
                  <button onClick={() => setEditPanel(null)} className="text-ink-400 hover:text-ink-200">✕</button>
                </div>
              </div>
              <div className="flex-1 flex overflow-hidden">
                {editPanel.showVersions ? (
                  <div className="w-48 border-r border-tech-500/10 p-3 overflow-y-auto shrink-0">
                    <h4 className="text-xs font-medium text-ink-300 mb-2">版本历史</h4>
                    {editPanel.versions.map(v => (
                      <div key={v.versionNo} onClick={() => viewVersion(editPanel.documentId, v.versionNo)}
                        className={`p-2 rounded cursor-pointer text-xs mb-1 ${editPanel.comparedVersion === v.versionNo ? 'bg-tech-500/10 text-tech-400' : 'text-ink-400 hover:bg-ink-800/30'}`}>
                        v{v.versionNo} · {v.changeSource === 'agent' ? 'AI' : '人工'}<br />
                        <span className="text-[10px] text-ink-500">{v.changeSummary || ''}</span>
                      </div>
                    ))}
                  </div>
                ) : null}
                <div className="flex-1 flex overflow-hidden">
                  <textarea
                    value={editPanel.content}
                    onChange={e => setEditPanel(p => p ? { ...p, content: e.target.value } : null)}
                    className="flex-1 p-4 bg-ink-900/50 text-sm text-ink-100 outline-none resize-none font-mono"
                    placeholder="Markdown 内容..."
                  />
                  {editPanel.comparedContent && (
                    <div className="w-1/2 border-l border-tech-500/10 p-4 overflow-y-auto bg-ink-900/30">
                      <p className="text-[10px] text-ink-500 mb-2">版本 v{editPanel.comparedVersion}（只读）</p>
                      <MarkdownRenderer content={editPanel.comparedContent} />
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 右侧：Agent 对话面板 */}
      {chatPanel.show && (
        <div className="w-80 border-l border-tech-500/10 flex flex-col bg-ink-950/30">
          <div className="p-3 border-b border-tech-500/10">
            <div className="flex items-center justify-between mb-1">
              <h4 className="text-xs font-medium text-ink-200">阶段 Agent</h4>
              <button
                onClick={handleJumpToChat}
                disabled={jumpingToChat}
                className="flex items-center gap-1 px-2 py-1 text-[10px] bg-tech-500/20 text-tech-400 rounded hover:bg-tech-500/30 disabled:opacity-50 transition-colors"
                title="跳转到 ChatPage 进行完整对话"
              >
                <MessageSquare className="w-3 h-3" />
                {jumpingToChat ? '跳转中...' : '完整对话'}
              </button>
            </div>
            <p className="text-[10px] text-ink-500">与 AI 协作完成阶段任务</p>
          </div>
          <div className="flex-1 overflow-y-auto p-3 space-y-3">
            {chatPanel.messages.map((msg, i) => (
              <div key={i} className={`flex flex-col ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
                {msg.role === 'ai' && msg.process && msg.process.length > 0 && (
                  <AIProcessTimeline items={msg.process} streaming={false} totalMs={msg.processMs} expanded={msg.processExpanded ?? false} onToggle={() => toggleMsgProcess(i)} />
                )}
                <div className={`max-w-[85%] rounded-lg p-2.5 text-xs ${msg.role === 'user' ? 'chat-bubble-user' : 'chat-bubble-ai'}`}>
                  <MarkdownRenderer content={msg.content} />
                </div>
              </div>
            ))}
            {chatPanel.streaming && chatPanel.streamProcess && chatPanel.streamProcess.items.length > 0 && (
              <AIProcessTimeline
                items={chatPanel.streamProcess.items}
                status={chatPanel.streamProcess.status}
                streaming
                startAt={chatPanel.streamProcess.startedAt}
                expanded
                onToggle={() => {}}
              />
            )}
            {chatPanel.streaming && (
              <div className="flex"><div className="max-w-[85%] rounded-lg p-2.5 text-xs chat-bubble-ai"><MarkdownRenderer content={chatPanel.streamText} isStreaming /></div></div>
            )}
          </div>
          <div className="p-3 border-t border-tech-500/10">
            <div className="flex items-center gap-1 mb-2">
              <input value={chatPanel.input} onChange={e => setChatPanel(p => ({ ...p, input: e.target.value }))}
                onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChatMessage(); } }}
                placeholder="输入问题..."
                className="flex-1 px-2 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded text-xs text-ink-100 outline-none"
                disabled={chatPanel.streaming}
              />
              <button onClick={sendChatMessage} disabled={chatPanel.streaming || !chatPanel.input.trim()} className="p-1.5 btn-primary text-white rounded disabled:opacity-50"><Send className="w-3 h-3" /></button>
            </div>
            {/* 模型/模式/思维选择 */}
            <div className="space-y-1.5">
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-ink-500 w-10 flex-shrink-0">模型</span>
                <CustomSelect value={selectedModel} onChange={setSelectedModel} options={modelOptions} />
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-ink-500 w-10 flex-shrink-0">模式</span>
                <CustomSelect value={agentMode} onChange={setAgentMode} options={agentModeOptions} />
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-[10px] text-ink-500 w-10 flex-shrink-0">思维</span>
                <CustomSelect value={thinkingStyle} onChange={setThinkingStyle} options={thinkingStyleOptions} />
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
