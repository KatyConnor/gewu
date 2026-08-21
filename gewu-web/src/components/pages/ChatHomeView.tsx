'use client';
import { Plus, Bot, FileText, MessageSquare } from 'lucide-react';

interface ChatHomeViewProps {
  onStartNewChat: () => void;
  onSwitchTab: (tab: string) => void;
}

export default function ChatHomeView({ onStartNewChat, onSwitchTab }: ChatHomeViewProps) {
  return (
    <div className="flex-1 flex flex-col items-center justify-center relative overflow-hidden">
      <div className="absolute inset-0 pointer-events-none opacity-[0.015]" style={{ backgroundImage: 'linear-gradient(rgba(0,184,148,0.5) 1px, transparent 1px), linear-gradient(90deg, rgba(0,184,148,0.5) 1px, transparent 1px)', backgroundSize: '80px 80px' }} />
      <div className="absolute top-[-80px] left-[-80px] w-[300px] h-[300px] rounded-full bg-tech-500/8 blur-3xl animate-breathe" />
      <div className="absolute bottom-[-60px] right-[-60px] w-[200px] h-[200px] rounded-full bg-cyber-500/5 blur-3xl animate-breathe" style={{ animationDelay: '2s' }} />

      <div className="text-center max-w-2xl px-8 relative z-10">
        <div className="w-20 h-20 mx-auto mb-6 rounded-2xl overflow-hidden shadow-lg shadow-tech-500/20 animate-float-slow">
          <img src="/logo/logo1.png" alt="格物致虚" className="w-full h-full object-cover" />
        </div>
        <h2 className="calligraphy-text text-3xl font-bold text-gradient-animate mb-3">格物<span className="mx-1.5 icon-glow-animate inline-block text-tech-400">·</span>致虚</h2>
        <p className="text-ink-400 text-base mb-2">AI 智能协作平台</p>
        <div className="w-16 h-0.5 bg-gradient-to-r from-transparent via-tech-400 to-transparent mx-auto my-4" />
        <p className="text-ink-500 text-sm leading-relaxed mb-8">致知在格物，物格而后知至。<br />选择左侧会话记录开始对话，或创建新的会话。</p>
        <div className="flex items-center justify-center gap-4">
          <button onClick={onStartNewChat} className="flex flex-col items-center gap-2 p-4 rounded-xl glass-dark hover:border-tech-500/20 transition-all group hover:scale-105">
            <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center group-hover:bg-tech-500/15 transition-colors">
              <Plus className="w-5 h-5 text-tech-400" />
            </div>
            <span className="text-xs text-ink-300 group-hover:text-tech-400 transition-colors">新建会话</span>
          </button>
          <button onClick={() => onSwitchTab('project')} className="flex flex-col items-center gap-2 p-4 rounded-xl glass-dark hover:border-tech-500/20 transition-all group hover:scale-105">
            <div className="w-10 h-10 rounded-lg bg-cyber-500/10 flex items-center justify-center group-hover:bg-cyber-500/15 transition-colors">
              <FileText className="w-5 h-5 text-cyber-400" />
            </div>
            <span className="text-xs text-ink-300 group-hover:text-tech-400 transition-colors">项目会话</span>
          </button>
          <button onClick={() => onSwitchTab('requirement')} className="flex flex-col items-center gap-2 p-4 rounded-xl glass-dark hover:border-tech-500/20 transition-all group hover:scale-105">
            <div className="w-10 h-10 rounded-lg bg-gold-500/10 flex items-center justify-center group-hover:bg-gold-500/15 transition-colors">
              <MessageSquare className="w-5 h-5 text-gold-400" />
            </div>
            <span className="text-xs text-ink-300 group-hover:text-tech-400 transition-colors">需求会话</span>
          </button>
        </div>
      </div>
    </div>
  );
}
