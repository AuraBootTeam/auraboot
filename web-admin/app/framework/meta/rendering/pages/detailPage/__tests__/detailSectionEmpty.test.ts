/**
 * detailSectionIsEmpty / isDetailValueEmpty / detailSectionEmptyText: when a detail section counts
 * as empty and what the line in its place says.
 */
import { describe, expect, it } from 'vitest';
import {
  DETAIL_SECTION_EMPTY_KEY,
  detailSectionIsEmpty,
  detailSectionEmptyText,
  isDetailValueEmpty,
} from '../detailSectionEmpty';
import type { FieldConfig } from '~/framework/meta/schemas/types';

const field = (name: string, extra: Partial<FieldConfig> = {}): FieldConfig => ({ field: name, ...extra } as FieldConfig);

describe('isDetailValueEmpty', () => {
  it.each([undefined, null, '', '   ', {}, []])('has nothing to read: %j', (value) => {
    expect(isDetailValueEmpty(value)).toBe(true);
  });

  it.each([0, false, 'x', 3, { a: 1 }, [1], true])('is a value: %j', (value) => {
    expect(isDetailValueEmpty(value)).toBe(false);
  });

  it('a display name resolved by the backend is content even when the raw value is empty', () => {
    expect(isDetailValueEmpty(null, 'chain A v1')).toBe(false);
    expect(isDetailValueEmpty('', '   ')).toBe(true);
  });
});

describe('detailSectionIsEmpty', () => {
  it('true when every field is empty on the record', () => {
    expect(detailSectionIsEmpty([field('a'), field('b')], { a: null, b: '' })).toBe(true);
    expect(detailSectionIsEmpty([field('a'), field('b')], {})).toBe(true);
    expect(detailSectionIsEmpty([field('a')], undefined)).toBe(true);
  });

  it('false as soon as one field has a value', () => {
    expect(detailSectionIsEmpty([field('a'), field('b')], { a: null, b: 0 })).toBe(false);
  });

  it('false for a section without fields: there is nothing it could have shown', () => {
    expect(detailSectionIsEmpty([], { a: 1 })).toBe(false);
    expect(detailSectionIsEmpty(undefined, { a: 1 })).toBe(false);
  });

  it('a name the backend resolved for the field counts as content', () => {
    expect(detailSectionIsEmpty([field('a')], { a: null, a_display: 'chain A v1' })).toBe(false);
  });

  it('reads a field by its camel-case name too, as the field grid does', () => {
    expect(detailSectionIsEmpty([field('chain_a_version')], { chainAVersion: 'v1' })).toBe(false);
  });
});

describe('detailSectionEmptyText', () => {
  it("says the page's own sentence when it gives one", () => {
    expect(detailSectionEmptyText({ emptyText: '这次运行的计划里没有数据迁移。' } as never, 'zh-CN', (k) => k)).toBe('这次运行的计划里没有数据迁移。');
  });

  it("takes the page's sentence in the reader's language", () => {
    const block = { extension: { emptyText: { 'zh-CN': '还没有内容。', en: 'Nothing yet.' } } } as never;
    expect(detailSectionEmptyText(block, 'zh-CN', (k) => k)).toBe('还没有内容。');
    expect(detailSectionEmptyText(block, 'en', (k) => k)).toBe('Nothing yet.');
  });

  it('uses the translated platform sentence when the catalog has it', () => {
    const t = (key: string) => (key === DETAIL_SECTION_EMPTY_KEY ? '本段暂无数据。' : key);
    expect(detailSectionEmptyText({} as never, 'zh-CN', t)).toBe('本段暂无数据。');
  });

  it('falls back to a built-in sentence in the reader language when nothing is translated', () => {
    expect(detailSectionEmptyText({} as never, 'zh-CN', (k) => k)).toBe('这一段还没有内容。');
    expect(detailSectionEmptyText({} as never, 'en', (k) => k)).toBe('Nothing has been recorded in this section yet.');
  });

  it('ignores a blank page sentence and uses the platform one', () => {
    expect(detailSectionEmptyText({ emptyText: '  ' } as never, 'zh-CN', (k) => k)).toBe('这一段还没有内容。');
  });
});
