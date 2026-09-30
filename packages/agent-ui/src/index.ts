/**
 * @gewu/agent-ui — 格物智能体 UI 共享包
 *
 * gewu-web 与 gewu-desktop 的单一实现源（docs/design/42 ADR-003/004）。
 * 双运行时（本地引擎 / 平台网关）仅以 ChatTransport 区分，UI 与协议零感知。
 */
export * from './protocol/index';
export * from './api-client/index';
export * from './process/index';
export * from './ui/MarkdownRenderer';

