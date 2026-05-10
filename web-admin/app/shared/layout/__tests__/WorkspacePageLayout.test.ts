import { describe, expect, it } from 'vitest';
import {
  WORKSPACE_PAGE_CLASSES,
  workspacePageClassName,
} from '~/shared/layout/WorkspacePageLayout';

describe('WorkspacePageLayout', () => {
  it('keeps workspace sections full width', () => {
    for (const className of Object.values(WORKSPACE_PAGE_CLASSES)) {
      expect(className).toContain('w-full');
      expect(className).not.toMatch(/\bmax-w-/);
    }
  });

  it('preserves appended page-specific classes', () => {
    expect(workspacePageClassName('content', 'space-y-6')).toContain('space-y-6');
  });
});
