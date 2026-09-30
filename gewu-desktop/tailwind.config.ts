import type { Config } from 'tailwindcss';

/**
 * 桌面端 Tailwind 配置（D1-24 收尾）：与 gewu-web 主题逐字对齐（ink/tech 等品牌色），
 * content 必须包含共享包源码——@gewu/agent-ui 以源码分发，其工具类在此扫描生成。
 */
const config: Config = {
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
    '../packages/agent-ui/src/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        ink: { 50:'#f5f7f6', 100:'#dde6e2', 200:'#b8ccc4', 300:'#8ba89d', 400:'#5d8076', 500:'#3d6359', 600:'#2d4d45', 700:'#1f3833', 800:'#152826', 900:'#0e1c1b', 950:'#081211' },
        cinnabar: { 400:'#e05a4f', 500:'#c9483d', 600:'#a33830' },
        jade: { 400:'#4ec9a0', 500:'#3aad86', 600:'#2d8c6c' },
        gold: { 400:'#d4a853', 500:'#c49a3f', 600:'#a68232' },
        tech: { 400:'#00d4aa', 500:'#00b894', 600:'#009b7d' },
        cyber: { 400:'#06b6d4', 500:'#0891b2', 600:'#0e7490' },
        mist: { 400:'#8ea9b8', 500:'#6b8a9b', 600:'#567081' },
        danger: { 400:'#e05a4f', 500:'#c9483d', 600:'#a33830' },
        'nav-hover': 'rgba(0,184,148,0.06)',
        'glass-border': 'rgba(0,184,148,0.08)',
        'primary-400': '#00d4aa',
        'primary-500': '#00b894',
      },
      fontFamily: {
        serif: ['"Noto Serif SC"', '"Source Han Serif SC"', 'serif'],
        sans: ['"Noto Sans SC"', '"Source Han Sans SC"', 'sans-serif'],
      },
    },
  },
  plugins: [],
};

export default config;
