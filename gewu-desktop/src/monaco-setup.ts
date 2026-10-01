// Monaco npm 模式同步预配置（必须最先导入——先于任何共享组件求值，
// 消除 loader 异步配置与 Editor init 的竞态——用户实测卡死根因）。
// worker：0.56 exports 对深路径 ?worker 解析有缺陷，Phase 2 深化项接入
// （MonacoEnvironment + vite alias）；基础编辑/审查主线程降级可用。
import * as monaco from 'monaco-editor';
import { loader } from '@monaco-editor/react';

loader.config({ monaco });
(globalThis as { __GEWU_MONACO_MODE__?: 'npm' | 'paths' }).__GEWU_MONACO_MODE__ = 'npm';
