function darken(hex: string, amount = 0.15): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  const dr = Math.round(r * (1 - amount))
  const dg = Math.round(g * (1 - amount))
  const db = Math.round(b * (1 - amount))
  return `#${dr.toString(16).padStart(2, '0')}${dg.toString(16).padStart(2, '0')}${db.toString(16).padStart(2, '0')}`
}

function lighten(hex: string, amount = 0.15): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  const lr = Math.min(255, Math.round(r + (255 - r) * amount))
  const lg = Math.min(255, Math.round(g + (255 - g) * amount))
  const lb = Math.min(255, Math.round(b + (255 - b) * amount))
  return `#${lr.toString(16).padStart(2, '0')}${lg.toString(16).padStart(2, '0')}${lb.toString(16).padStart(2, '0')}`
}

function mix(hex1: string, hex2: string, ratio = 0.5): string {
  const r1 = parseInt(hex1.slice(1, 3), 16)
  const g1 = parseInt(hex1.slice(3, 5), 16)
  const b1 = parseInt(hex1.slice(5, 7), 16)
  const r2 = parseInt(hex2.slice(1, 3), 16)
  const g2 = parseInt(hex2.slice(3, 5), 16)
  const b2 = parseInt(hex2.slice(5, 7), 16)
  const r = Math.round(r1 * (1 - ratio) + r2 * ratio)
  const g = Math.round(g1 * (1 - ratio) + g2 * ratio)
  const b = Math.round(b1 * (1 - ratio) + b2 * ratio)
  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`
}

interface Palette {
  neutral: string
  ink: string
  primary: string
  success: string
  warning: string
  error: string
  info?: string
  accent?: string
  interactive?: string
}

function toVars(palette: Palette, isDark: boolean): Record<string, string> {
  const { neutral, ink, primary, success, warning, error, info, accent, interactive } = palette
  const bg = neutral
  const fg = ink
  const act = accent || primary

  return {
    '--bg-primary': bg,
    '--bg-secondary': isDark ? lighten(bg, 0.06) : darken(bg, 0.04),
    '--bg-tertiary': isDark ? lighten(bg, 0.10) : darken(bg, 0.07),
    '--bg-active': isDark ? lighten(bg, 0.14) : darken(bg, 0.10),
    '--border': isDark ? lighten(bg, 0.18) : darken(bg, 0.15),
    '--accent': primary,
    '--accent-hover': lighten(primary, 0.15),
    '--user-bg': isDark ? mix(bg, primary, 0.12) : mix(bg, primary, 0.08),
    '--user-border': isDark ? mix(bg, primary, 0.22) : mix(bg, primary, 0.18),
    '--success': success,
    '--danger': error,
    '--warning': warning,
    '--info': info || primary,
    '--text-primary': fg,
    '--text-secondary': isDark ? darken(fg, 0.25) : darken(fg, 0.20),
    '--text-muted': isDark ? darken(fg, 0.45) : darken(fg, 0.40),
    '--interactive': interactive || primary,
  }
}

interface Theme {
  id: string
  name: string
  dark: Record<string, string>
  light: Record<string, string>
}

export const themes: Theme[] = [
  {
    id: 'github',
    name: 'GitHub',
    dark: toVars({ neutral: '#0d1117', ink: '#c9d1d9', primary: '#58a6ff', success: '#3fb950', warning: '#e3b341', error: '#f85149', info: '#d29922', accent: '#39c5cf' }, true),
    light: toVars({ neutral: '#ffffff', ink: '#1f2328', primary: '#0969da', success: '#1a7f37', warning: '#9a6700', error: '#cf222e', info: '#9a6700', accent: '#0969da' }, false),
  },
  {
    id: 'opencode',
    name: 'OpenCode',
    dark: toVars({ neutral: '#0a0a0a', ink: '#eeeeee', primary: '#fab283', success: '#7fd88f', warning: '#f5a742', error: '#e06c75', info: '#56b6c2', accent: '#9d7cd8' }, true),
    light: toVars({ neutral: '#ffffff', ink: '#1a1a1a', primary: '#c06030', success: '#2d8a4e', warning: '#b07830', error: '#c04050', info: '#308090', accent: '#7050b0' }, false),
  },
  {
    id: 'catppuccin',
    name: 'Catppuccin',
    dark: toVars({ neutral: '#1e1e2e', ink: '#cdd6f4', primary: '#b4befe', success: '#a6d189', warning: '#f4b8e4', error: '#f38ba8', info: '#89dceb', accent: '#f5c2e7' }, true),
    light: toVars({ neutral: '#eff1f5', ink: '#4c4f69', primary: '#1e66f5', success: '#40a02b', warning: '#df8e1d', error: '#d20f39', info: '#04a5e5', accent: '#ea76cb' }, false),
  },
  {
    id: 'dracula',
    name: 'Dracula',
    dark: toVars({ neutral: '#1d1e28', ink: '#f8f8f2', primary: '#bd93f9', success: '#50fa7b', warning: '#ffb86c', error: '#ff5555', info: '#8be9fd', accent: '#ff79c6' }, true),
    light: toVars({ neutral: '#f8f8f2', ink: '#1d1e28', primary: '#6272a4', success: '#4d8b31', warning: '#b8730e', error: '#e64747', info: '#2980b9', accent: '#c03050' }, false),
  },
  {
    id: 'nord',
    name: 'Nord',
    dark: toVars({ neutral: '#2e3440', ink: '#e5e9f0', primary: '#88c0d0', success: '#a3be8c', warning: '#d08770', error: '#bf616a', info: '#81a1c1', accent: '#5e81ac' }, true),
    light: toVars({ neutral: '#eceff4', ink: '#2e3440', primary: '#5e81ac', success: '#a3be8c', warning: '#d08770', error: '#bf616a', info: '#81a1c1', accent: '#88c0d0' }, false),
  },
  {
    id: 'tokyonight',
    name: 'Tokyo Night',
    dark: toVars({ neutral: '#1a1b26', ink: '#c0caf5', primary: '#7aa2f7', success: '#9ece6a', warning: '#e0af68', error: '#f7768e', info: '#7dcfff', accent: '#ff9e64' }, true),
    light: toVars({ neutral: '#ffffff', ink: '#1a1b26', primary: '#1a73e8', success: '#1a8c4e', warning: '#c07830', error: '#c04060', info: '#0f85a0', accent: '#d06040' }, false),
  },
  {
    id: 'one-dark',
    name: 'One Dark',
    dark: toVars({ neutral: '#282c34', ink: '#abb2bf', primary: '#61afef', success: '#98c379', warning: '#e5c07b', error: '#e06c75', info: '#d19a66', accent: '#56b6c2' }, true),
    light: toVars({ neutral: '#fafafa', ink: '#383a42', primary: '#4078f2', success: '#50a14f', warning: '#c18401', error: '#e45649', info: '#986801', accent: '#0184bc' }, false),
  },
  {
    id: 'gruvbox',
    name: 'Gruvbox',
    dark: toVars({ neutral: '#282828', ink: '#ebdbb2', primary: '#83a598', success: '#b8bb26', warning: '#fabd2f', error: '#fb4934', info: '#d3869b', accent: '#fe8019' }, true),
    light: toVars({ neutral: '#fbf1c7', ink: '#3c3836', primary: '#458588', success: '#98971a', warning: '#d79921', error: '#cc241d', info: '#b16286', accent: '#d65d0e' }, false),
  },
  {
    id: 'solarized',
    name: 'Solarized',
    dark: toVars({ neutral: '#002b36', ink: '#93a1a1', primary: '#6c71c4', success: '#859900', warning: '#b58900', error: '#dc322f', info: '#2aa198', accent: '#d33682' }, true),
    light: toVars({ neutral: '#fdf6e3', ink: '#657b83', primary: '#268bd2', success: '#859900', warning: '#b58900', error: '#dc322f', info: '#2aa198', accent: '#d33682' }, false),
  },
  {
    id: 'monokai',
    name: 'Monokai',
    dark: toVars({ neutral: '#272822', ink: '#f8f8f2', primary: '#ae81ff', success: '#a6e22e', warning: '#fd971f', error: '#f92672', info: '#66d9ef', accent: '#e6db74' }, true),
    light: toVars({ neutral: '#f9f8f5', ink: '#1a1a1a', primary: '#7c3aed', success: '#16a34a', warning: '#ea580c', error: '#dc2626', info: '#0891b2', accent: '#ca8a04' }, false),
  },
  {
    id: 'material',
    name: 'Material',
    dark: toVars({ neutral: '#263238', ink: '#eeffff', primary: '#82aaff', success: '#c3e88d', warning: '#ffcb6b', error: '#f07178', info: '#ffcb6b', accent: '#89ddff' }, true),
    light: toVars({ neutral: '#fafafa', ink: '#1a1a1a', primary: '#1565c0', success: '#2e7d32', warning: '#f9a825', error: '#c62828', info: '#f9a825', accent: '#0277bd' }, false),
  },
  {
    id: 'nightowl',
    name: 'Night Owl',
    dark: toVars({ neutral: '#011627', ink: '#d6deeb', primary: '#82aaff', success: '#c5e478', warning: '#ecc48d', error: '#ef5350', info: '#82aaff', accent: '#f78c6c' }, true),
    light: toVars({ neutral: '#fbfcff', ink: '#1a1a1a', primary: '#0156b2', success: '#22863a', warning: '#e36209', error: '#d73a49', info: '#0156b2', accent: '#d73a49' }, false),
  },
  {
    id: 'aura',
    name: 'Aura',
    dark: toVars({ neutral: '#15141b', ink: '#edecee', primary: '#a277ff', success: '#61ffca', warning: '#ffca85', error: '#ff6767', info: '#82e2ff', accent: '#f694ff' }, true),
    light: toVars({ neutral: '#fffffe', ink: '#1a1a1a', primary: '#7c3aed', success: '#059669', warning: '#d97706', error: '#dc2626', info: '#0284c7', accent: '#c026d3' }, false),
  },
  {
    id: 'carbonfox',
    name: 'Carbon Fox',
    dark: toVars({ neutral: '#393939', ink: '#f2f4f8', primary: '#33b1ff', success: '#42be65', warning: '#f1c21b', error: '#ff8389', info: '#78a9ff', accent: '#be95ff' }, true),
    light: toVars({ neutral: '#ffffff', ink: '#161616', primary: '#0f62fe', success: '#198038', warning: '#8e6a00', error: '#da1e28', info: '#0043ce', accent: '#a56eff' }, false),
  },
  {
    id: 'cyberpunk',
    name: 'Cyberpunk Neon',
    dark: toVars({ neutral: '#0a0012', ink: '#f0e6ff', primary: '#ff2d95', success: '#00ff88', warning: '#ffea00', error: '#ff1744', info: '#00fff0', accent: '#00fff0' }, true),
    light: toVars({ neutral: '#faf5ff', ink: '#1a0028', primary: '#c0006a', success: '#008044', warning: '#b89600', error: '#c01030', info: '#008080', accent: '#008080' }, false),
  },
  {
    id: 'vercel',
    name: 'Vercel',
    dark: toVars({ neutral: '#000000', ink: '#EDEDED', primary: '#0070F3', success: '#46A758', warning: '#FFB224', error: '#E5484D', info: '#52A8FF', accent: '#8E4EC6' }, true),
    light: toVars({ neutral: '#ffffff', ink: '#171717', primary: '#0070F3', success: '#16a34a', warning: '#f59e0b', error: '#dc2626', info: '#2563eb', accent: '#7c3aed' }, false),
  },
]

export function getThemeById(id: string): Theme | undefined {
  return themes.find(t => t.id === id)
}

export function getThemeVars(themeId: string, mode: string): Record<string, string> | undefined {
  const theme = getThemeById(themeId)
  if (!theme) return undefined
  return (theme as any)[mode] || theme.dark
}

export function applyThemeVars(vars: Record<string, string>) {
  const root = document.documentElement
  for (const [key, value] of Object.entries(vars)) {
    root.style.setProperty(key, value)
  }
}

export const defaultThemeId = 'github'
