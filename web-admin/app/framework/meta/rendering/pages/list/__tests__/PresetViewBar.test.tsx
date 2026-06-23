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
    saved_view_preset_saved_badge: '已保存',
    saved_view_preset_edited_badge: '已编辑',
    saved_view_preset_reset: '重置预设视图',
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

  it('marks system presets that already have personal saved copies', () => {
    renderBar({ savedPresetKeys: ['created_today'] });

    const createdToday = screen.getByTestId('preset-view-created_today');
    expect(createdToday.getAttribute('data-preset-saved')).toBe('true');
    expect(screen.getByTestId('preset-view-created_today-saved').textContent).toContain('已保存');
    expect(screen.getByTestId('preset-view-my_records').getAttribute('data-preset-saved')).toBe(
      'false',
    );
  });

  it('marks the active personal preset copy and exposes reset when edited', () => {
    const onResetActiveSavedPreset = vi.fn();
    renderBar({
      savedPresetKeys: ['modified_this_week'],
      activeSavedPresetKey: 'modified_this_week',
      activeSavedPresetEdited: true,
      onResetActiveSavedPreset,
    });

    const modified = screen.getByTestId('preset-view-modified_this_week');
    expect(modified.getAttribute('data-preset-active')).toBe('true');
    expect(modified.getAttribute('data-preset-edited')).toBe('true');
    expect(screen.getByTestId('preset-view-modified_this_week-saved').textContent).toContain(
      '已编辑',
    );

    const reset = screen.getByTestId('preset-view-reset-saved');
    expect(reset.getAttribute('aria-label')).toBe('重置预设视图');
    fireEvent.click(reset);
    expect(onResetActiveSavedPreset).toHaveBeenCalledTimes(1);
  });
});
