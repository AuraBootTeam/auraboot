/**
 * Design Tokens - 设计令牌系统
 * 支持主题变量和设计系统token
 */

/**
 * 设计令牌定义
 */
export const designTokens = {
  color: {
    text: {
      primary: 'var(--text-primary, #1f2937)',
      secondary: 'var(--text-secondary, #6b7280)',
      tertiary: 'var(--text-tertiary, #9ca3af)',
      disabled: 'var(--text-disabled, #d1d5db)',
      inverse: 'var(--text-inverse, #ffffff)',
      onHeader: 'var(--text-on-header, #ffffff)',
      onPrimary: 'var(--text-on-primary, #ffffff)',
      onSecondary: 'var(--text-on-secondary, #000000)',
    },
    bg: {
      primary: 'var(--bg-primary, #ffffff)',
      secondary: 'var(--bg-secondary, #f9fafb)',
      tertiary: 'var(--bg-tertiary, #f3f4f6)',
      header: 'var(--bg-header, #1f2937)',
      sider: 'var(--bg-sider, #f9fafb)',
      layout: 'var(--bg-layout, #f3f4f6)',
      hover: 'var(--bg-hover, #f3f4f6)',
      active: 'var(--bg-active, #e5e7eb)',
      disabled: 'var(--bg-disabled, #f3f4f6)',
    },
    border: {
      default: 'var(--border-default, #e5e7eb)',
      light: 'var(--border-light, #f3f4f6)',
      dark: 'var(--border-dark, #d1d5db)',
    },
    brand: {
      primary: 'var(--brand-primary, #3b82f6)',
      secondary: 'var(--brand-secondary, #8b5cf6)',
      success: 'var(--brand-success, #10b981)',
      warning: 'var(--brand-warning, #f59e0b)',
      error: 'var(--brand-error, #ef4444)',
      info: 'var(--brand-info, #06b6d4)',
    },
  },

  spacing: {
    xs: '4px',
    sm: '8px',
    md: '16px',
    lg: '24px',
    xl: '32px',
    '2xl': '48px',
    '3xl': '64px',
  },

  font: {
    size: {
      xs: '12px',
      sm: '14px',
      base: '16px',
      lg: '18px',
      xl: '20px',
      '2xl': '24px',
      '3xl': '30px',
      '4xl': '36px',
    },
    weight: {
      light: '300',
      normal: '400',
      medium: '500',
      semibold: '600',
      bold: '700',
    },
    family: {
      sans: 'var(--font-sans, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif)',
      mono: 'var(--font-mono, "SF Mono", Monaco, "Cascadia Code", "Roboto Mono", Consolas, monospace)',
    },
  },

  radius: {
    none: '0',
    sm: '2px',
    base: '4px',
    md: '6px',
    lg: '8px',
    xl: '12px',
    '2xl': '16px',
    full: '9999px',
  },

  shadow: {
    sm: 'var(--shadow-sm, 0 1px 2px 0 rgba(0, 0, 0, 0.05))',
    base: 'var(--shadow-base, 0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.06))',
    md: 'var(--shadow-md, 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06))',
    lg: 'var(--shadow-lg, 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05))',
    xl: 'var(--shadow-xl, 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04))',
  },

  zIndex: {
    base: '0',
    dropdown: '1000',
    sticky: '1020',
    fixed: '1030',
    modalBackdrop: '1040',
    modal: '1050',
    popover: '1060',
    tooltip: '1070',
  },

  transition: {
    fast: '150ms',
    base: '200ms',
    slow: '300ms',
    slower: '500ms',
  },
};

/**
 * Token 类型
 */
export type DesignToken = typeof designTokens;

/* ==========================================================================
 * dsTokens — canonical UX Design System tokens (single source of truth)
 * --------------------------------------------------------------------------
 * Spec : auraboot-enterprise/docs/standards/core/ux-design-system.md §1
 * Ref  : auraboot-enterprise/docs/mockups/ux-design-system/index.html
 *
 * These are the authoritative visual tokens. They flow into Tailwind v4 via the
 * generated `@theme` block (see `buildThemeCss`) which emits utilities
 * (bg-accent, text-text-2, border-border-strong, rounded-control, …) AND CSS
 * custom properties (var(--color-accent), …) consumed across `app/ui`.
 *
 * Additive: the legacy `designTokens` object + `resolveToken` DSL `$path`
 * resolver above are intentionally left as-is for backward compatibility.
 * ========================================================================== */
export const dsTokens = {
  font: {
    // UI sans stack (CJK-aware); data/number columns use mono with tabular-nums
    ui: '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Segoe UI", Roboto, sans-serif',
    mono: 'ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace',
  },
  color: {
    text: '#1A1A1E',
    text2: '#5A5E66',
    text3: '#9A9DA5',
    border: '#ECEDEF',
    borderStrong: '#E2E3E6',
    bg: '#F7F7F8',
    panel: '#FFFFFF',
    subtle: '#FAFAFB',
    hover: '#F3F4F6',
    selection: '#EEF4FF',
    accent: '#2563EB',
    accentHover: '#1D4ED8',
    accentWeak: '#EFF4FF',
    // Always-dark inverse surface for floating action bars / tooltips
    // (§3 深色批量操作栏). Theme-independent — same in light and dark.
    inverse: '#1A1A22',
    inverseText: '#FFFFFF',
    inverseMuted: '#C9CCD3',
    inverseHover: '#2C2C36',
    inverseBorder: '#3A3A45',
  },
  // §1.3 semantic status colors — business dicts pick from these 5 only.
  status: {
    gray: { fg: '#71717A', bg: '#F1F1F3' }, // draft / not-started / closed
    blue: { fg: '#2563EB', bg: '#EAF1FE' }, // in-progress / processing
    amber: { fg: '#C2750A', bg: '#FBF1E2' }, // pending / warning
    green: { fg: '#15A34A', bg: '#E7F6ED' }, // done / passed / normal
    red: { fg: '#DC2626', bg: '#FCECEC' }, // error / rejected / failed / overdue
  },
  // 4px spacing grid (adds the 12/20 steps the legacy scale skipped).
  space: {
    1: '4px',
    2: '8px',
    3: '12px',
    4: '16px',
    5: '20px',
    6: '24px',
    8: '32px',
  },
  radius: {
    control: '6px',
    card: '8px',
    cardLg: '10px',
    pill: '9999px',
  },
  // Control heights — same-form input/select/button must align to one of these.
  control: {
    sm: '28px',
    md: '32px',
    lg: '40px',
    field: '34px', // form-field default (slightly taller for tap comfort)
  },
  // Type scale: page title / section / body / aux / eyebrow.
  textScale: {
    title: { size: '20px', weight: '660' },
    section: { size: '15px', weight: '620' },
    body: { size: '13.5px', weight: '400' },
    aux: { size: '12.5px', weight: '400' },
    eyebrow: { size: '11.5px', weight: '600' },
  },
  shadow: {
    card: '0 1px 2px rgba(16,18,23,.03)',
    pop: '0 1px 2px rgba(16,18,23,.04), 0 8px 24px -6px rgba(16,18,23,.14)',
    toast: '0 6px 20px -4px rgba(16,18,23,.22)',
  },
  // Unified focus ring (standard §1/§2 authoritative = 3px accent-weak; the
  // mockup currently renders 2px — flagged for sync).
  focusRing: '0 0 0 3px #EFF4FF',
  disabledOpacity: '0.5',
  // Dark-mode overrides for the semantic color tokens (T3 "补 dark mode").
  // Derived from the app's existing `.dark` conventions (gray-900 bg /
  // gray-50 text / blue-400 accent — see app/app.css). The `@theme`-generated
  // utilities reference `var(--color-*)`, so overriding those under `.dark`
  // makes bg-panel / text-text / bg-accent / status colors auto-switch.
  dark: {
    color: {
      text: '#F9FAFB',
      text2: '#9CA3AF',
      text3: '#6B7280',
      border: '#374151',
      borderStrong: '#4B5563',
      bg: '#111827',
      panel: '#1F2937',
      subtle: '#19212E',
      hover: '#374151',
      selection: '#1E3A5F',
      accent: '#60A5FA',
      accentHover: '#3B82F6',
      accentWeak: '#1E293B',
    },
    status: {
      gray: { fg: '#A1A1AA', bg: '#27272A' },
      blue: { fg: '#60A5FA', bg: '#1E293B' },
      amber: { fg: '#FBBF24', bg: '#2A2113' },
      green: { fg: '#34D399', bg: '#14271C' },
      red: { fg: '#F87171', bg: '#2A1515' },
    },
  },
} as const;

/**
 * dsTokens 类型
 */
export type DsTokens = typeof dsTokens;

/**
 * Derive the Tailwind v4 `@theme` block + companion `:root` custom properties
 * from dsTokens. Output is deterministic so a committed copy
 * (`app/styles/tokens.theme.css`) can be drift-checked against it, keeping
 * dsTokens the single source of truth.
 *
 * `@theme` entries generate utilities (bg-accent, text-text-2, rounded-control,
 * shadow-focus, text-title, …) and expose the values as CSS variables.
 * The `:root` companion carries tokens that have no Tailwind theme namespace
 * (control heights, the spacing grid for raw CSS, disabled opacity).
 */
export function buildThemeCss(tokens: DsTokens = dsTokens): string {
  const c = tokens.color;
  const lines: string[] = [];

  lines.push(
    '/* AUTO-GENERATED from dsTokens in app/framework/meta/runtime/theme/tokens.ts — do not edit by hand. Run `pnpm gen:tokens`. */',
  );
  lines.push('@theme {');

  lines.push(`  --font-ui: ${tokens.font.ui};`);
  lines.push(`  --font-mono: ${tokens.font.mono};`);
  lines.push('');

  lines.push(`  --color-text: ${c.text};`);
  lines.push(`  --color-text-2: ${c.text2};`);
  lines.push(`  --color-text-3: ${c.text3};`);
  lines.push(`  --color-border: ${c.border};`);
  lines.push(`  --color-border-strong: ${c.borderStrong};`);
  lines.push(`  --color-bg: ${c.bg};`);
  lines.push(`  --color-panel: ${c.panel};`);
  lines.push(`  --color-subtle: ${c.subtle};`);
  lines.push(`  --color-hover: ${c.hover};`);
  lines.push(`  --color-selection: ${c.selection};`);
  lines.push(`  --color-accent: ${c.accent};`);
  lines.push(`  --color-accent-hover: ${c.accentHover};`);
  lines.push(`  --color-accent-weak: ${c.accentWeak};`);
  lines.push(`  --color-inverse: ${c.inverse};`);
  lines.push(`  --color-inverse-text: ${c.inverseText};`);
  lines.push(`  --color-inverse-muted: ${c.inverseMuted};`);
  lines.push(`  --color-inverse-hover: ${c.inverseHover};`);
  lines.push(`  --color-inverse-border: ${c.inverseBorder};`);
  lines.push('');

  for (const [name, pair] of Object.entries(tokens.status)) {
    lines.push(`  --color-status-${name}: ${pair.fg};`);
    lines.push(`  --color-status-${name}-bg: ${pair.bg};`);
  }
  lines.push('');

  lines.push(`  --radius-control: ${tokens.radius.control};`);
  lines.push(`  --radius-card: ${tokens.radius.card};`);
  lines.push(`  --radius-card-lg: ${tokens.radius.cardLg};`);
  lines.push(`  --radius-pill: ${tokens.radius.pill};`);
  lines.push('');

  lines.push(`  --shadow-card: ${tokens.shadow.card};`);
  lines.push(`  --shadow-pop: ${tokens.shadow.pop};`);
  lines.push(`  --shadow-toast: ${tokens.shadow.toast};`);
  lines.push(`  --shadow-focus: ${tokens.focusRing};`);
  lines.push('');

  for (const [name, def] of Object.entries(tokens.textScale)) {
    lines.push(`  --text-${name}: ${def.size};`);
    lines.push(`  --text-${name}--font-weight: ${def.weight};`);
  }
  lines.push('}');
  lines.push('');

  lines.push('/* Design-system raw custom properties (non-utility access). */');
  lines.push(':root {');
  lines.push(`  --ds-control-sm: ${tokens.control.sm};`);
  lines.push(`  --ds-control-md: ${tokens.control.md};`);
  lines.push(`  --ds-control-lg: ${tokens.control.lg};`);
  lines.push(`  --ds-control-field: ${tokens.control.field};`);
  for (const [step, value] of Object.entries(tokens.space)) {
    lines.push(`  --ds-space-${step}: ${value};`);
  }
  lines.push(`  --ds-disabled-opacity: ${tokens.disabledOpacity};`);
  lines.push('}');
  lines.push('');

  // Dark-mode overrides. The @theme utilities reference var(--color-*), so
  // overriding those variables under `.dark` switches bg-panel / text-text /
  // bg-accent / status colors automatically (darkMode: 'class').
  const d = tokens.dark.color;
  lines.push('/* Dark-mode token overrides (T3). */');
  lines.push('.dark {');
  lines.push(`  --color-text: ${d.text};`);
  lines.push(`  --color-text-2: ${d.text2};`);
  lines.push(`  --color-text-3: ${d.text3};`);
  lines.push(`  --color-border: ${d.border};`);
  lines.push(`  --color-border-strong: ${d.borderStrong};`);
  lines.push(`  --color-bg: ${d.bg};`);
  lines.push(`  --color-panel: ${d.panel};`);
  lines.push(`  --color-subtle: ${d.subtle};`);
  lines.push(`  --color-hover: ${d.hover};`);
  lines.push(`  --color-selection: ${d.selection};`);
  lines.push(`  --color-accent: ${d.accent};`);
  lines.push(`  --color-accent-hover: ${d.accentHover};`);
  lines.push(`  --color-accent-weak: ${d.accentWeak};`);
  for (const [name, pair] of Object.entries(tokens.dark.status)) {
    lines.push(`  --color-status-${name}: ${pair.fg};`);
    lines.push(`  --color-status-${name}-bg: ${pair.bg};`);
  }
  lines.push('}');
  lines.push('');

  return lines.join('\n');
}

/**
 * 解析 Design Token
 * 将 $token.path 转换为实际的 CSS 值
 *
 * @example
 * resolveToken('$color.text.primary') => 'var(--text-primary, #1f2937)'
 * resolveToken('$spacing.md') => '16px'
 * resolveToken('#ff0000') => '#ff0000'
 */
export function resolveToken(token: string | number): string {
  // 数字直接返回
  if (typeof token === 'number') {
    return `${token}px`;
  }

  if (typeof token !== 'string') {
    return String(token);
  }

  // 非 token 语法，直接返回
  if (!token.startsWith('$')) {
    return token;
  }

  // 移除 $ 前缀，分割路径
  const path = token.slice(1).split('.');

  // 从 designTokens 中查找
  let value: any = designTokens;
  for (const key of path) {
    value = value?.[key];
    if (value === undefined) {
      console.warn(`Design token not found: ${token}`);
      return token;
    }
  }

  return String(value);
}

/**
 * 解析样式对象中的所有 token (支持嵌套)
 */
export function resolveStyleTokens(style: Record<string, any>): Record<string, any> {
  const resolved: Record<string, any> = {};

  for (const [key, value] of Object.entries(style)) {
    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      // 递归处理嵌套对象
      resolved[key] = resolveStyleTokens(value);
    } else if (Array.isArray(value)) {
      // 处理数组
      resolved[key] = value.map((item) =>
        typeof item === 'object' && item !== null
          ? resolveStyleTokens(item)
          : typeof item === 'string' && item.startsWith('$')
            ? resolveToken(item)
            : item,
      );
    } else if (typeof value === 'string' && value.startsWith('$')) {
      // 解析 token
      resolved[key] = resolveToken(value);
    } else if (typeof value === 'number') {
      // 数字自动添加 px 单位
      resolved[key] = `${value}px`;
    } else {
      // 其他类型保持不变
      resolved[key] = value;
    }
  }

  return resolved;
}

/**
 * 批量解析 token
 */
export function resolveTokens(tokens: string[]): string[] {
  return tokens.map(resolveToken);
}

/**
 * 暗色主题 token 值
 */
const darkThemeValues = {
  // Text colors
  '--text-primary': '#f9fafb',
  '--text-secondary': '#d1d5db',
  '--text-tertiary': '#9ca3af',
  '--text-disabled': '#6b7280',
  '--text-inverse': '#1f2937',
  '--text-on-header': '#ffffff',
  '--text-on-primary': '#ffffff',
  '--text-on-secondary': '#ffffff',

  // Background colors
  '--bg-primary': '#1f2937',
  '--bg-secondary': '#374151',
  '--bg-tertiary': '#4b5563',
  '--bg-header': '#111827',
  '--bg-sider': '#374151',
  '--bg-layout': '#111827',
  '--bg-hover': '#4b5563',
  '--bg-active': '#6b7280',
  '--bg-disabled': '#374151',

  // Border colors
  '--border-default': '#4b5563',
  '--border-light': '#374151',
  '--border-dark': '#6b7280',

  // Brand colors (保持不变)
  '--brand-primary': '#3b82f6',
  '--brand-secondary': '#8b5cf6',
  '--brand-success': '#10b981',
  '--brand-warning': '#f59e0b',
  '--brand-error': '#ef4444',
  '--brand-info': '#06b6d4',

  // Shadows (暗色主题使用更深的阴影)
  '--shadow-sm': '0 1px 2px 0 rgba(0, 0, 0, 0.3)',
  '--shadow-base': '0 1px 3px 0 rgba(0, 0, 0, 0.4), 0 1px 2px 0 rgba(0, 0, 0, 0.3)',
  '--shadow-md': '0 4px 6px -1px rgba(0, 0, 0, 0.4), 0 2px 4px -1px rgba(0, 0, 0, 0.3)',
  '--shadow-lg': '0 10px 15px -3px rgba(0, 0, 0, 0.4), 0 4px 6px -2px rgba(0, 0, 0, 0.3)',
  '--shadow-xl': '0 20px 25px -5px rgba(0, 0, 0, 0.4), 0 10px 10px -5px rgba(0, 0, 0, 0.3)',

  // Font (保持不变)
  '--font-sans':
    '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  '--font-mono': '"SF Mono", Monaco, "Cascadia Code", "Roboto Mono", Consolas, monospace',
};

/**
 * 亮色主题 token 值
 */
const lightThemeValues = {
  // Text colors
  '--text-primary': '#1f2937',
  '--text-secondary': '#6b7280',
  '--text-tertiary': '#9ca3af',
  '--text-disabled': '#d1d5db',
  '--text-inverse': '#ffffff',
  '--text-on-header': '#ffffff',
  '--text-on-primary': '#ffffff',
  '--text-on-secondary': '#000000',

  // Background colors
  '--bg-primary': '#ffffff',
  '--bg-secondary': '#f9fafb',
  '--bg-tertiary': '#f3f4f6',
  '--bg-header': '#1f2937',
  '--bg-sider': '#f9fafb',
  '--bg-layout': '#f3f4f6',
  '--bg-hover': '#f3f4f6',
  '--bg-active': '#e5e7eb',
  '--bg-disabled': '#f3f4f6',

  // Border colors
  '--border-default': '#e5e7eb',
  '--border-light': '#f3f4f6',
  '--border-dark': '#d1d5db',

  // Brand colors
  '--brand-primary': '#3b82f6',
  '--brand-secondary': '#8b5cf6',
  '--brand-success': '#10b981',
  '--brand-warning': '#f59e0b',
  '--brand-error': '#ef4444',
  '--brand-info': '#06b6d4',

  // Shadows
  '--shadow-sm': '0 1px 2px 0 rgba(0, 0, 0, 0.05)',
  '--shadow-base': '0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.06)',
  '--shadow-md': '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)',
  '--shadow-lg': '0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05)',
  '--shadow-xl': '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)',

  // Font
  '--font-sans':
    '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  '--font-mono': '"SF Mono", Monaco, "Cascadia Code", "Roboto Mono", Consolas, monospace',
};

/**
 * 生成 CSS 变量定义
 */
export function generateCSSVariables(theme: 'light' | 'dark' = 'light'): string {
  const values = theme === 'dark' ? darkThemeValues : lightThemeValues;
  const vars: string[] = ['/* Design Tokens */'];

  for (const [key, value] of Object.entries(values)) {
    vars.push(`${key}: ${value};`);
  }

  return `:root {\n  ${vars.join('\n  ')}\n}`;
}

/**
 * 注入 CSS 变量到页面
 */
export function injectCSSVariables(theme?: 'light' | 'dark'): void {
  const styleId = 'design-tokens';
  let styleEl = document.getElementById(styleId);

  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = styleId;
    document.head.appendChild(styleEl);
  }

  styleEl.textContent = generateCSSVariables(theme);
}
