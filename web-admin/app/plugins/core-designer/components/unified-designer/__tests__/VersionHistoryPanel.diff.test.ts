import { describe, expect, it } from 'vitest';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import { normalizeDifferenceType } from '../workbench/VersionHistoryPanel';

describe('VersionHistoryPanel semantic diff', () => {
  it('preserves the backend MOVED operation instead of collapsing it to modified', () => {
    expect(normalizeDifferenceType('MOVED')).toBe('moved');
    expect(normalizeDifferenceType('MODIFIED')).toBe('modified');
  });

  it('localizes move badges and includes moved count in the summary', () => {
    expect(resolveDesignerText(DESIGNER_I18N.unified.versionDiffMoved, 'zh-CN')).toBe('移动');
    expect(
      resolveDesignerText(DESIGNER_I18N.unified.versionDiffSummary, 'en-US', {
        added: 1,
        removed: 2,
        modified: 3,
        moved: 4,
      }),
    ).toBe('1 added / 2 removed / 3 modified / 4 moved');
  });
});
