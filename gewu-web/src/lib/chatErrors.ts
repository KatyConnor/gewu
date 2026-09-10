// 对话流式错误分类 —— 把 SSE / 网络层的原始错误串解析为结构化信息，
// 供会话页错误提示框展示：第一行摘要 + 错误分类 + 可能原因 + 处理建议。
// 后端会把所有上游非 2xx 统一包装为「xxx API 认证失败或请求错误 (HTTP xxx)」，
// 因此 HTTP 状态码比错误文案更可靠，优先按状态码分类，网络层错误按关键词兜底。

export interface ChatErrorInfo {
  raw: string; // 完整原始错误信息
  title: string; // 第一行摘要（折叠态展示）
  category: string; // 错误分类
  cause: string; // 可能原因
  suggestion: string; // 建议处理
  time: number; // 发生时间（毫秒时间戳）
}

interface CategoryTemplate {
  category: string;
  cause: string;
  suggestion: string;
}

const TEMPLATES: Record<string, CategoryTemplate> = {
  timeout: {
    category: '请求超时',
    cause: '在规定时间内未收到 AI 服务的完整响应：可能是模型推理耗时过长、网络链路慢，或服务端异步等待超时后强制断开了本次请求。',
    suggestion: '请重试一次；若反复出现，尝试简化当前问题或缩短上下文后再试。',
  },
  concurrency: {
    category: '访问并发过高 / 限流',
    cause: '当前模型服务的请求量达到上限（账号限流或服务端过载保护），本次请求被拒绝。',
    suggestion: '请等待几秒到几分钟后重试；也可在设置页临时切换到其他已启用的模型。',
  },
  connection: {
    category: '连接异常关闭',
    cause: '与 AI 服务的连接被意外中断：可能是网络抖动、代理/网关重启、服务端进程异常关闭，或请求被提前终止。',
    suggestion: '请检查网络后重新发送；若持续出现，请确认后端服务与网关是否正常运行。',
  },
  auth: {
    category: '认证 / 鉴权失败',
    cause: '身份凭证无效，或模型供应商的 API Key 缺失/失效，请求被服务端拒绝。',
    suggestion: '刷新页面重新登录；若为模型供应商鉴权问题，请在设置页检查对应供应商的 API Key 配置。',
  },
  upstream: {
    category: '上游模型服务错误',
    cause: 'AI 服务或模型供应商内部出现错误（非本地问题），本次请求未能完成。',
    suggestion: '请稍后重试；若持续出现，请到设置页检查模型供应商状态或切换其他模型。',
  },
  notFound: {
    category: '服务 / 资源不存在',
    cause: '请求的接口或模型端点不存在：常见为供应商 Base URL 配置不完整（缺少接口路径）、模型 ID 有误或模型已下线。',
    suggestion: '请到设置页核对该供应商的 Base URL 与模型 ID 配置是否与供应商文档一致。',
  },
  badRequest: {
    category: '请求参数 / 配置错误',
    cause: '请求内容或模型配置不合法：常见为模型供应商拒绝了当前请求（如免费档模型不允许 API 直连、参数超出取值范围）。',
    suggestion: '检查会话页所选模型是否支持 API 调用；必要时到设置页核对模型与供应商配置。',
  },
  unknown: {
    category: '未知错误',
    cause: '未能定位到明确的错误原因，可能是未知的服务端或网络异常。',
    suggestion: '请重试一次；若反复出现，请复制详情中的完整错误信息反馈给管理员排查。',
  },
};

/** 按关键词匹配网络层/超时类错误（无 HTTP 状态码时的兜底） */
const KEYWORD_RULES: Array<{ match: RegExp; key: keyof typeof TEMPLATES }> = [
  { match: /timeout|timed?\s*out|超时|ETIMEDOUT|AsyncRequestTimeout/i, key: 'timeout' },
  { match: /429|rate\s*limit|too\s*many\s*requests|并发|限流|overloaded|busy|529/i, key: 'concurrency' },
  { match: /failed\s*to\s*fetch|network\s*error|networkerror|socket|econnreset|econnrefused|连接|中断|断开|异常关闭|premature|closed|aborted|abort/i, key: 'connection' },
  { match: /401|403|unauthorized|forbidden|鉴权失败|api\s*key|invalid[_\s]*token/i, key: 'auth' },
  { match: /HTTP\s*5\d\d|服务端|系统异常|internal\s*server/i, key: 'upstream' },
];

export function classifyChatError(raw: string): ChatErrorInfo {
  const text = (raw || '').trim() || '未知错误';
  const firstLine = text.split('\n').map(l => l.trim()).find(Boolean) || '未知错误';
  const codeMatch = text.match(/HTTP\s*(\d{3})/i);
  const code = codeMatch ? Number(codeMatch[1]) : null;

  let key: keyof typeof TEMPLATES;
  if (code === 400) key = 'badRequest';
  else if (code === 401 || code === 403) key = 'auth';
  else if (code === 404) key = 'notFound';
  else if (code === 408 || code === 504) key = 'timeout';
  else if (code === 429) key = 'concurrency';
  else if (code !== null && code >= 500) key = 'upstream';
  else {
    const rule = KEYWORD_RULES.find(r => r.match.test(text));
    key = rule?.key ?? 'unknown';
  }

  const tpl = TEMPLATES[key];
  return {
    raw: text,
    title: firstLine.length > 120 ? firstLine.slice(0, 120) + '…' : firstLine,
    category: tpl.category,
    cause: tpl.cause,
    suggestion: tpl.suggestion,
    time: Date.now(),
  };
}
