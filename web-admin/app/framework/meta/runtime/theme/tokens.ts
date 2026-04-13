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
