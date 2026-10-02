import { describe, expect, it } from 'vitest';
import Ajv2020 from 'ajv/dist/2020';
import { readFileSync } from 'node:fs';
import { resolveAuthAppearance, resolveAuthContent, authForeground } from '../auth-appearance';
import schema from '../auth-appearance.schema.json';
import corpus from './auth-appearance.corpus.json';

const validate = new Ajv2020({ strict: false, allErrors: true }).compile(schema);
describe('auth appearance contract', () => {
  for (const fixture of corpus) {
    it(fixture.name, () => {
      expect(validate(fixture.value), JSON.stringify(validate.errors)).toBe(fixture.valid);
      if (fixture.valid) expect(resolveAuthAppearance(fixture.value)).toEqual(fixture.value);
      else expect(() => resolveAuthAppearance(fixture.value)).toThrow(/authAppearance/);
    });
  }
  it('keeps the Java classpath schema byte-identical', () => {
    const backend = JSON.parse(readFileSync('../platform/src/main/resources/branding/auth-appearance.schema.json', 'utf8'));
    expect(backend).toEqual(schema);
  });
  it('distinguishes default, custom, and hidden copy', () => {
    expect(resolveAuthContent(undefined, 'en-US', 'zh-CN', 'Legacy')).toBe('Legacy');
    expect(resolveAuthContent({ mode: 'default' }, 'en-US', 'zh-CN', 'Legacy')).toBe('Legacy');
    expect(resolveAuthContent({ mode: 'hidden' }, 'en-US', 'zh-CN', 'Legacy')).toBeUndefined();
    expect(resolveAuthContent({ mode: 'custom', values: { 'zh-CN': '学校', 'en-US': 'School' } }, 'en-US', 'zh-CN', 'Legacy')).toBe('School');
    expect(resolveAuthContent({ mode: 'custom', values: { 'zh-CN': '学校' } }, 'en-US', 'zh-CN', 'Legacy')).toBe('学校');
  });
  it('selects contrasting text on pale, dark and mid-tone brand colors', () => {
    expect(authForeground('#ffffff')).toBe('#000000');
    expect(authForeground('#000000')).toBe('#ffffff');
    expect(authForeground('#35745b')).toBe('#ffffff');
    expect(authForeground('#ffffee')).toBe('#000000');
  });
  it('keeps omitted legacy appearance unset and rejects explicit null', () => {
    expect(resolveAuthAppearance(undefined)).toBeUndefined();
    expect(() => resolveAuthAppearance(null)).toThrow();
  });
});
