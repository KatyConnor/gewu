'use client';
import { Component, ReactNode } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';

interface Props { children: ReactNode; fallback?: ReactNode }
interface State { hasError: boolean; error?: Error }

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) { super(props); this.state = { hasError: false }; }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback || (
        <div className="flex flex-col items-center justify-center h-64 text-center">
          <AlertTriangle className="w-12 h-12 text-cinnabar-400 mb-4" />
          <h3 className="text-lg font-semibold text-ink-100 mb-2">页面出现错误</h3>
          <p className="text-sm text-ink-400 mb-4">{this.state.error?.message || '未知错误'}</p>
          <button onClick={() => this.setState({ hasError: false })} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
            <RefreshCw className="w-4 h-4" />重新加载
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
