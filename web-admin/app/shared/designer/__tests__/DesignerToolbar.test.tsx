import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string, fallback?: string) => {
    const translations: Record<string, string> = {
      '$i18n:flow.toolbar.save': '保存',
      '$i18n:flow.toolbar.saving': '保存中...',
      '$i18n:flow.toolbar.unsaved': '未保存',
      '$i18n:flow.toolbar.undo': '撤销',
      '$i18n:flow.toolbar.redo': '重做',
    };
    return translations[key] || fallback || key;
  },
}));

import { DesignerToolbar } from '~/shared/designer/DesignerToolbar';

describe('DesignerToolbar responsive actions', () => {
  it('keeps the action group inside the viewport on compact widths by wrapping full-width', () => {
    render(
      <DesignerToolbar
        title="Designer"
        isDirty
        isSaving={false}
        onSave={vi.fn()}
        onUndo={vi.fn()}
        onRedo={vi.fn()}
        canUndo
        canRedo
        testId="designer-toolbar"
      >
        {Array.from({ length: 6 }, (_, index) => (
          <button key={index} type="button" className="shrink-0 whitespace-nowrap">
            Action {index + 1}
          </button>
        ))}
      </DesignerToolbar>,
    );

    expect(screen.getByTestId('designer-toolbar-actions')).toHaveClass('w-full', 'flex-wrap');
    expect(screen.getByTestId('designer-toolbar-actions')).toHaveClass('sm:w-auto');
  });

  it('localizes default save and dirty-state labels', () => {
    const { rerender } = render(
      <DesignerToolbar
        title="Designer"
        isDirty
        isSaving={false}
        onSave={vi.fn()}
        testId="designer-toolbar"
      />,
    );

    expect(screen.getByTestId('designer-toolbar-btn-save')).toHaveTextContent('保存');
    expect(screen.getByText('未保存')).toBeInTheDocument();
    expect(screen.queryByText('Save')).toBeNull();
    expect(screen.queryByText('Unsaved')).toBeNull();

    rerender(
      <DesignerToolbar
        title="Designer"
        isDirty
        isSaving
        onSave={vi.fn()}
        testId="designer-toolbar"
      />,
    );

    expect(screen.getByTestId('designer-toolbar-btn-save')).toHaveTextContent('保存中...');
    expect(screen.getAllByText('保存中...').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText('Saving...')).toBeNull();
  });
});
