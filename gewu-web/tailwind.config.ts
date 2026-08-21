import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
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
      animation: {
        'ink-flow': 'inkFlow 8s ease-in-out infinite',
        'float-slow': 'floatSlow 6s ease-in-out infinite',
        'fade-up': 'fadeUp 0.5s ease-out forwards',
      },
      keyframes: {
        inkFlow: { '0%,100%':{transform:'translateY(0) scale(1)',opacity:'0.3'}, '50%':{transform:'translateY(-20px) scale(1.05)',opacity:'0.6'} },
        floatSlow: { '0%,100%':{transform:'translateY(0)'}, '50%':{transform:'translateY(-8px)'} },
        fadeUp: { '0%':{opacity:'0',transform:'translateY(12px)'}, '100%':{opacity:'1',transform:'translateY(0)'} },
      },
    },
  },
  plugins: [],
};
export default config;
