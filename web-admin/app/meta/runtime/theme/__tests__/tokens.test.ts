/**
 * Design Tokens 测试
 * 测试设计令牌解析和主题系统
 */

import { describe, it, expect } from 'vitest';
import {
  designTokens,
  resolveToken,
  resolveStyleTokens,
  resolveTokens,
  generateCSSVariables,
} from '~/meta/runtime/theme/tokens';

describe('Design Tokens', () => {
  describe('resolveToken', () => {
    it('应该解析颜色 token', () => {
      expect(resolveToken('$color.text.primary')).toBe('var(--text-primary, #1f2937)');
      expect(resolveToken('$color.bg.header')).toBe('var(--bg-header, #1f2937)');
      expect(resolveToken('$color.brand.primary')).toBe('var(--brand-primary, #3b82f6)');
    });

    it('应该解析间距 token', () => {
      expect(resolveToken('$spacing.xs')).toBe('4px');
      expect(resolveToken('$spacing.sm')).toBe('8px');
      expect(resolveToken('$spacing.md')).toBe('16px');
      expect(resolveToken('$spacing.lg')).toBe('24px');
      expect(resolveToken('$spacing.xl')).toBe('32px');
    });

    it('应该解析字体 token', () => {
      expect(resolveToken('$font.size.xs')).toBe('12px');
      expect(resolveToken('$font.size.base')).toBe('16px');
      expect(resolveToken('$font.weight.bold')).toBe('700');
      expect(resolveToken('$font.family.sans')).toContain('apple-system');
    });

    it('应该解析圆角 token', () => {
      expect(resolveToken('$radius.none')).toBe('0');
      expect(resolveToken('$radius.sm')).toBe('2px');
      expect(resolveToken('$radius.base')).toBe('4px');
      expect(resolveToken('$radius.full')).toBe('9999px');
    });

    it('应该解析阴影 token', () => {
      expect(resolveToken('$shadow.sm')).toContain('rgba');
      expect(resolveToken('$shadow.base')).toContain('rgba');
    });

    it('应该解析 z-index token', () => {
      expect(resolveToken('$zIndex.modal')).toBe('1050');
      expect(resolveToken('$zIndex.tooltip')).toBe('1070');
    });

    it('应该解析过渡时间 token', () => {
      expect(resolveToken('$transition.fast')).toBe('150ms');
      expect(resolveToken('$transition.base')).toBe('200ms');
    });

    it('应该处理数字（转为 px）', () => {
      expect(resolveToken(16)).toBe('16px');
      expect(resolveToken(24)).toBe('24px');
    });

    it('应该保持非 token 字符串不变', () => {
      expect(resolveToken('#ff0000')).toBe('#ff0000');
      expect(resolveToken('red')).toBe('red');
      expect(resolveToken('100%')).toBe('100%');
      expect(resolveToken('2rem')).toBe('2rem');
    });

    it('应该处理不存在的 token（返回原值并警告）', () => {
      const invalidToken = '$color.invalid.token';
      expect(resolveToken(invalidToken)).toBe(invalidToken);
    });
  });

  describe('resolveStyleTokens', () => {
    it('应该解析简单样式对象', () => {
      const style = {
        color: '$color.text.primary',
        fontSize: '$font.size.base',
        padding: '$spacing.md',
      };

      const resolved = resolveStyleTokens(style);

      expect(resolved.color).toBe('var(--text-primary, #1f2937)');
      expect(resolved.fontSize).toBe('16px');
      expect(resolved.padding).toBe('16px');
    });

    it('应该处理数字自动添加 px', () => {
      const style = {
        width: 100,
        height: 200,
        marginTop: 16,
      };

      const resolved = resolveStyleTokens(style);

      expect(resolved.width).toBe('100px');
      expect(resolved.height).toBe('200px');
      expect(resolved.marginTop).toBe('16px');
    });

    it('应该保持非 token 值不变', () => {
      const style = {
        color: '#ff0000',
        backgroundColor: 'blue',
        display: 'flex',
        width: '100%',
      };

      const resolved = resolveStyleTokens(style);

      expect(resolved.color).toBe('#ff0000');
      expect(resolved.backgroundColor).toBe('blue');
      expect(resolved.display).toBe('flex');
      expect(resolved.width).toBe('100%');
    });

    it('应该处理嵌套对象', () => {
      const style = {
        button: {
          color: '$color.text.primary',
          backgroundColor: '$color.brand.primary',
          padding: '$spacing.md',
        },
        hover: {
          backgroundColor: '$color.brand.secondary',
        },
      };

      const resolved = resolveStyleTokens(style);

      expect(resolved.button.color).toBe('var(--text-primary, #1f2937)');
      expect(resolved.button.backgroundColor).toBe('var(--brand-primary, #3b82f6)');
      expect(resolved.button.padding).toBe('16px');
      expect(resolved.hover.backgroundColor).toBe('var(--brand-secondary, #8b5cf6)');
    });

    it('应该处理数组', () => {
      const style = {
        colors: ['$color.brand.primary', '$color.brand.secondary', '#ff0000'],
        spacing: [8, 16, '$spacing.lg'],
      };

      const resolved = resolveStyleTokens(style);

      expect(resolved.colors[0]).toBe('var(--brand-primary, #3b82f6)');
      expect(resolved.colors[1]).toBe('var(--brand-secondary, #8b5cf6)');
      expect(resolved.colors[2]).toBe('#ff0000');
      expect(resolved.spacing[0]).toBe(8);
      expect(resolved.spacing[1]).toBe(16);
      expect(resolved.spacing[2]).toBe('24px');
    });

    it('应该处理复杂嵌套结构', () => {
      const style = {
        theme: {
          colors: {
            primary: '$color.brand.primary',
            text: '$color.text.primary',
          },
          spacing: {
            padding: '$spacing.md',
            margin: 16,
          },
        },
      };

      const resolved = resolveStyleTokens(style);

      expect(resolved.theme.colors.primary).toBe('var(--brand-primary, #3b82f6)');
      expect(resolved.theme.colors.text).toBe('var(--text-primary, #1f2937)');
      expect(resolved.theme.spacing.padding).toBe('16px');
      expect(resolved.theme.spacing.margin).toBe('16px');
    });
  });

  describe('resolveTokens', () => {
    it('应该批量解析 token 数组', () => {
      const tokens = ['$color.brand.primary', '$spacing.md', '$font.size.base', '#ff0000'];

      const resolved = resolveTokens(tokens);

      expect(resolved[0]).toBe('var(--brand-primary, #3b82f6)');
      expect(resolved[1]).toBe('16px');
      expect(resolved[2]).toBe('16px');
      expect(resolved[3]).toBe('#ff0000');
    });
  });

  describe('generateCSSVariables', () => {
    it('应该生成亮色主题 CSS 变量', () => {
      const css = generateCSSVariables('light');

      expect(css).toContain(':root {');
      expect(css).toContain('--text-primary: #1f2937');
      expect(css).toContain('--bg-primary: #ffffff');
      expect(css).toContain('--bg-header: #1f2937');
      expect(css).toContain('--border-default: #e5e7eb');
      expect(css).toContain('--brand-primary: #3b82f6');
    });

    it('应该生成暗色主题 CSS 变量', () => {
      const css = generateCSSVariables('dark');

      expect(css).toContain(':root {');
      expect(css).toContain('--text-primary: #f9fafb');
      expect(css).toContain('--bg-primary: #1f2937');
      expect(css).toContain('--bg-header: #111827');
      expect(css).toContain('--border-default: #4b5563');
      expect(css).toContain('--brand-primary: #3b82f6'); // 品牌色保持不变
    });

    it('应该默认生成亮色主题', () => {
      const css = generateCSSVariables();

      expect(css).toContain('--text-primary: #1f2937');
      expect(css).toContain('--bg-primary: #ffffff');
    });

    it('暗色和亮色主题应该有不同的颜色', () => {
      const lightCSS = generateCSSVariables('light');
      const darkCSS = generateCSSVariables('dark');

      // 文本颜色应该相反
      expect(lightCSS).toContain('--text-primary: #1f2937');
      expect(darkCSS).toContain('--text-primary: #f9fafb');

      // 背景颜色应该相反
      expect(lightCSS).toContain('--bg-primary: #ffffff');
      expect(darkCSS).toContain('--bg-primary: #1f2937');

      // 品牌色应该保持一致
      expect(lightCSS).toContain('--brand-primary: #3b82f6');
      expect(darkCSS).toContain('--brand-primary: #3b82f6');
    });
  });

  describe('designTokens 结构', () => {
    it('应该包含所有必需的分类', () => {
      expect(designTokens).toHaveProperty('color');
      expect(designTokens).toHaveProperty('spacing');
      expect(designTokens).toHaveProperty('font');
      expect(designTokens).toHaveProperty('radius');
      expect(designTokens).toHaveProperty('shadow');
      expect(designTokens).toHaveProperty('zIndex');
      expect(designTokens).toHaveProperty('transition');
    });

    it('color 应该包含完整的颜色系统', () => {
      expect(designTokens.color).toHaveProperty('text');
      expect(designTokens.color).toHaveProperty('bg');
      expect(designTokens.color).toHaveProperty('border');
      expect(designTokens.color).toHaveProperty('brand');
    });

    it('text 颜色应该包含所有变体', () => {
      expect(designTokens.color.text).toHaveProperty('primary');
      expect(designTokens.color.text).toHaveProperty('secondary');
      expect(designTokens.color.text).toHaveProperty('tertiary');
      expect(designTokens.color.text).toHaveProperty('disabled');
      expect(designTokens.color.text).toHaveProperty('inverse');
    });

    it('brand 颜色应该包含所有语义颜色', () => {
      expect(designTokens.color.brand).toHaveProperty('primary');
      expect(designTokens.color.brand).toHaveProperty('success');
      expect(designTokens.color.brand).toHaveProperty('warning');
      expect(designTokens.color.brand).toHaveProperty('error');
      expect(designTokens.color.brand).toHaveProperty('info');
    });

    it('spacing 应该提供完整的间距比例', () => {
      expect(designTokens.spacing).toHaveProperty('xs');
      expect(designTokens.spacing).toHaveProperty('sm');
      expect(designTokens.spacing).toHaveProperty('md');
      expect(designTokens.spacing).toHaveProperty('lg');
      expect(designTokens.spacing).toHaveProperty('xl');
      expect(designTokens.spacing).toHaveProperty('2xl');
      expect(designTokens.spacing).toHaveProperty('3xl');
    });

    it('font 应该包含 size, weight, family', () => {
      expect(designTokens.font).toHaveProperty('size');
      expect(designTokens.font).toHaveProperty('weight');
      expect(designTokens.font).toHaveProperty('family');
    });
  });

  describe('真实 DSL 场景', () => {
    it('应该解析 DSL 中的 style 配置', () => {
      // 模拟 DSL 中的 style 配置
      const blockConfig = {
        type: 'form',
        style: {
          backgroundColor: '$color.bg.primary',
          padding: '$spacing.lg',
          borderRadius: '$radius.md',
          boxShadow: '$shadow.base',
          color: '$color.text.primary',
          fontSize: '$font.size.base',
        },
      };

      const resolvedStyle = resolveStyleTokens(blockConfig.style);

      expect(resolvedStyle.backgroundColor).toBe('var(--bg-primary, #ffffff)');
      expect(resolvedStyle.padding).toBe('24px');
      expect(resolvedStyle.borderRadius).toBe('6px');
      expect(resolvedStyle.color).toBe('var(--text-primary, #1f2937)');
      expect(resolvedStyle.fontSize).toBe('16px');
    });

    it('应该处理响应式样式配置', () => {
      const responsiveStyle = {
        base: {
          padding: '$spacing.sm',
          fontSize: '$font.size.sm',
        },
        md: {
          padding: '$spacing.md',
          fontSize: '$font.size.base',
        },
        lg: {
          padding: '$spacing.lg',
          fontSize: '$font.size.lg',
        },
      };

      const resolved = resolveStyleTokens(responsiveStyle);

      expect(resolved.base.padding).toBe('8px');
      expect(resolved.md.padding).toBe('16px');
      expect(resolved.lg.padding).toBe('24px');
    });
  });
});
