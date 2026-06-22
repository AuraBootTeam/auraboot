/**
 * PresetViewBar — renders the 3 built-in quick-filter presets as selectable
 * "system preset" view chips (T8). Verifies discoverability, active state,
 * toggle callback, and i18n labelling (no raw English leakage under zh-CN).
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { PresetViewBar } from '../PresetViewBar';

const ZH = {
  common: {
    my_records: '我的记录',
    created_today: '今日新建',
    modified_this_week: '本周修改',
    preset_views: '预设视图',
    saved_view_save_preset_to_personal: '保存为我的视图',
  },
};

function renderBar(props: Partial<React.ComponentProps<typeof PresetViewBar>> = {}) {
  return render(
    <I18nProvider initialData={ZH} initialLocale="zh-CN">
      <PresetViewBar activePreset={null} onSelectPreset={() => {}} {...props} />
    </I18nProvider>,
  );
}

describe('PresetViewBar', () => {
  it('renders all three preset views as buttons', () => {
    renderBar();
    expect(screen.getByTestId('preset-view-my_records')).toBeTruthy();
    expect(screen.getByTestId('preset-view-created_today')).toBeTruthy();
    expect(screen.getByTestId('preset-view-modified_this_week')).toBeTruthy();
  });

  it('labels presets via i18n (zh-CN), not raw English', () => {
    renderBar();
    expect(screen.getByTestId('preset-view-my_records').textContent).toContain('我的记录');
    expect(screen.getByTestId('preset-view-created_today').textContent).toContain('今日新建');
    expect(screen.getByTestId('preset-view-modified_this_week').textContent).toContain('本周修改');
    // section label is i18n'd too
    expect(screen.getByTestId('preset-view-label').textContent).toContain('预设视图');
  });

  it('marks the active preset and not the others', () => {
    renderBar({ activePreset: 'created_today' });
    expect(screen.getByTestId('preset-view-created_today').getAttribute('data-preset-active')).toBe(
      'true',
    );
    expect(screen.getByTestId('preset-view-created_today').getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(screen.getByTestId('preset-view-my_records').getAttribute('data-preset-active')).toBe(
      'false',
    );
  });

  it('invokes onSelectPreset with the clicked preset key', () => {
    const onSelectPreset = vi.fn();
    renderBar({ onSelectPreset });
    fireEvent.click(screen.getByTestId('preset-view-modified_this_week'));
    expect(onSelectPreset).toHaveBeenCalledWith('modified_this_week');
  });

  it('renders save-as-personal action only for the active preset', () => {
    const onSaveActivePreset = vi.fn();
    const { rerender } = render(
      <I18nProvider initialData={ZH} initialLocale="zh-CN">
        <PresetViewBar
          activePreset={null}
          onSelectPreset={() => {}}
          onSaveActivePreset={onSaveActivePreset}
        />
      </I18nProvider>,
    );
    expect(screen.queryByTestId('preset-view-save-as-personal')).toBeNull();

    rerender(
      <I18nProvider initialData={ZH} initialLocale="zh-CN">
        <PresetViewBar
          activePreset="created_today"
          onSelectPreset={() => {}}
          onSaveActivePreset={onSaveActivePreset}
        />
      </I18nProvider>,
    );

    const saveButton = screen.getByTestId('preset-view-save-as-personal');
    expect(saveButton.getAttribute('aria-label')).toBe('保存为我的视图');
    fireEvent.click(saveButton);
    expect(onSaveActivePreset).toHaveBeenCalledTimes(1);
  });
});
