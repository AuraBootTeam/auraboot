import { describe, expect, it } from 'vitest';
import {
  resolveColumnLabel,
  resolveFieldLabel,
  resolveFieldPlaceholder,
} from '../i18nResolver';

/**
 * Raw `$i18n:` keys from imported page DSL must never reach the UI. When the
 * dictionary lacks the key, resolvers fall through to the label-based default
 * instead of returning t(key) verbatim (P2 finding: the team-members picker
 * button rendered `$i18n:model.showcase_all_fields.sc_team_members.placeholder`).
 */
const missingTranslation = (key: string) => key;
const withTeamMembers = (key: string) =>
  (({
    'model.showcase_all_fields.sc_team_members.placeholder': '选择团队成员（可多选）',
  } as Record<string, string>)[key] ?? key);

describe('resolveFieldPlaceholder — raw $i18n key fallback', () => {
  it('resolves a known $i18n placeholder from props', () => {
    const field = {
      field: 'sc_team_members',
      props: { placeholder: '$i18n:model.showcase_all_fields.sc_team_members.placeholder' },
    };
    expect(resolveFieldPlaceholder(field, 'showcase_all_fields', withTeamMembers, '团队成员')).toBe(
      '选择团队成员（可多选）',
    );
  });

  it('falls back to the label-based default when the dictionary lacks the key', () => {
    const field = {
      field: 'sc_team_members',
      props: { placeholder: '$i18n:model.showcase_all_fields.sc_team_members.placeholder' },
    };
    const resolved = resolveFieldPlaceholder(field, 'showcase_all_fields', missingTranslation, '团队成员');
    expect(resolved).toBe('请输入团队成员');
    expect(resolved).not.toContain('$i18n:');
  });

  it('falls back without a provided label (resolves the label first)', () => {
    const field = {
      field: 'sc_team_members',
      props: { placeholder: '$i18n:model.showcase_all_fields.sc_team_members.placeholder' },
    };
    const resolved = resolveFieldPlaceholder(field, 'showcase_all_fields', missingTranslation);
    expect(resolved).not.toContain('$i18n:');
  });
});

describe('resolveFieldLabel — raw $i18n key fallback', () => {
  it('falls back to the model-key path and field code when the label key is missing', () => {
    const field = { field: 'sc_team_members', label: '$i18n:model.showcase_all_fields.sc_team_members.label' };
    const resolved = resolveFieldLabel(field, 'showcase_all_fields', missingTranslation);
    expect(resolved).not.toContain('$i18n:');
  });
});

describe('resolveColumnLabel — raw $i18n key fallback', () => {
  it('falls back to the field label path when the column label key is missing', () => {
    const resolved = resolveColumnLabel(
      { field: 'sc_team_members', label: '$i18n:model.showcase_all_fields.sc_team_members.label' },
      'showcase_all_fields',
      missingTranslation,
    );
    expect(resolved).not.toContain('$i18n:');
  });
});
