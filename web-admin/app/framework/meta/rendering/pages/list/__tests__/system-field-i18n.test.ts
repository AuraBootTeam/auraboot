import { describe, expect, it } from 'vitest';
import { getSystemFieldI18nKey } from '../../ListPageContent';

describe('list page system field i18n keys', () => {
  it('maps snake_case audit fields to existing common keys before probing model keys', () => {
    expect(getSystemFieldI18nKey('created_at')).toBe('common.created_at');
    expect(getSystemFieldI18nKey('updated_at')).toBe('common.updated_at');
    expect(getSystemFieldI18nKey('created_by')).toBe('common.creator');
    expect(getSystemFieldI18nKey('updated_by')).toBe('common.modifier');
  });

  it('leaves business fields to model-scoped resolution', () => {
    expect(getSystemFieldI18nKey('mission_status')).toBeUndefined();
  });
});
