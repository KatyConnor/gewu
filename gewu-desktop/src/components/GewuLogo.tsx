/**
 * 格物品牌商标（build/icon.svg 同源版式）：深绿渐变圆角底 + 青绿「格物」+ 副语。
 * tagline 关闭时输出紧凑方形（标题栏小尺寸用）；渐变 id 经 useId 隔离同页多实例。
 */
import { useId } from 'react';

export default function GewuLogo({ size = 128, tagline = true }: { size?: number; tagline?: boolean }) {
  const uid = useId();
  const gradId = `gewu-bg-${uid.replace(/[:]/g, '')}`;
  return (
    <svg viewBox="0 0 512 512" width={size} height={size} role="img" aria-label="格物">
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#0e1c1b" />
          <stop offset="1" stopColor="#0a1413" />
        </linearGradient>
      </defs>
      <rect width="512" height="512" rx="112" fill={`url(#${gradId})`} />
      <rect x="20" y="20" width="472" height="472" rx="96" fill="none" stroke="rgba(0,184,148,0.4)" strokeWidth="8" />
      <text x="256" y={tagline ? 325 : 320} fontFamily="'Noto Sans CJK SC','Source Han Sans SC','Microsoft YaHei',sans-serif"
        fontWeight="700" fontSize={tagline ? 175 : 200} fill="#00b894" textAnchor="middle" letterSpacing="10">格物</text>
      {tagline && (
        <text x="256" y="415" fontFamily="'Noto Sans CJK SC','Source Han Sans SC','Microsoft YaHei',sans-serif"
          fontSize="34" fill="rgba(139,168,157,0.85)" textAnchor="middle" letterSpacing="6">致知在格物</text>
      )}
    </svg>
  );
}
