import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { getLocalizedText } from '~/utils/i18n';
import { ShortcutHelpPanel } from '../ShortcutHelpPanel';
import { CATEGORIES, SHORTCUTS, searchShortcuts, formatKeyCombo } from '../shortcuts';

const context = vi.hoisted(() => ({ locale: 'en-US' }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: context.locale }) }));
afterEach(cleanup);
beforeEach(() => { context.locale = 'en-US'; });

describe('designer shortcut help localization', () => {
  it('renders every category, label and description in English with unchanged key bindings', () => {
    render(<ShortcutHelpPanel isOpen onClose={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Keyboard shortcuts' })).toBeTruthy();
    expect(CATEGORIES).toHaveLength(6);
    expect(SHORTCUTS).toHaveLength(28);
    for (const category of CATEGORIES) {
      expect(screen.getByRole('button', { name: getLocalizedText(category.name, 'en-US') })).toBeTruthy();
    }
    for (const shortcut of SHORTCUTS) {
      expect(screen.getAllByText(getLocalizedText(shortcut.label, 'en-US')).length).toBeGreaterThan(0);
      expect(screen.getAllByText(getLocalizedText(shortcut.description, 'en-US')).length).toBeGreaterThan(0);
    }
    expect(document.body.textContent).not.toMatch(/[\u4e00-\u9fff]/);
    expect(SHORTCUTS.find(s => s.id === 'save')?.keys).toEqual([{ key: 'S', ctrl: true }]);
    expect(formatKeyCombo([{ key: 'Click', shift: true }], 'en-US')).toContain('Click');
  });
  it('retains complete Chinese copy', () => {
    context.locale = 'zh-CN';
    render(<ShortcutHelpPanel isOpen onClose={vi.fn()} />);
    expect(screen.getByRole('heading', { name: '\u952e\u76d8\u5feb\u6377\u952e' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '\u901a\u7528' })).toBeTruthy();
    expect(screen.getByText('\u4fdd\u5b58\u5f53\u524d\u9875\u9762')).toBeTruthy();
    expect(document.body.textContent).toContain('\u6309 Esc \u5173\u95ed');
    expect(formatKeyCombo([{ key: 'Click', shift: true }], 'zh-CN')).toContain('\u70b9\u51fb');
  });
  it('searches translated descriptions and preserves Chinese and English tags', () => {
    render(<ShortcutHelpPanel isOpen onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search shortcuts...' }), { target: { value: 'current page' } });
    expect(screen.getByText('Save the current page')).toBeTruthy();
    expect(screen.getByText('Preview the current page')).toBeTruthy();
    expect(screen.queryByText('Copy selected components')).toBeNull();
    expect(searchShortcuts('\u4fdd\u5b58', 'en-US').map(s => s.id)).toEqual(['save']);
    expect(searchShortcuts('save', 'zh-CN').map(s => s.id)).toEqual(['save']);
  });
  it('updates displayed text and existing search results when the context locale changes', () => {
    const view = render(<ShortcutHelpPanel isOpen onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search shortcuts...' }), { target: { value: 'current page' } });
    context.locale = 'zh-CN';
    view.rerender(<ShortcutHelpPanel isOpen onClose={vi.fn()} />);
    expect(screen.getByRole('heading', { name: '\u952e\u76d8\u5feb\u6377\u952e' })).toBeTruthy();
    expect(screen.getByText('\u6ca1\u6709\u627e\u5230\u5339\u914d\u7684\u5feb\u6377\u952e')).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: '\u641c\u7d22\u5feb\u6377\u952e...' }), { target: { value: '\u5f53\u524d\u9875\u9762' } });
    expect(screen.getByText('\u4fdd\u5b58\u5f53\u524d\u9875\u9762')).toBeTruthy();
    expect(screen.getByText('\u9884\u89c8\u5f53\u524d\u9875\u9762')).toBeTruthy();
  });
  it('filters categories, reports an empty search and resets search when selecting a category', () => {
    render(<ShortcutHelpPanel isOpen onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Canvas' }));
    expect(screen.getByText('Pan canvas')).toBeTruthy();
    expect(screen.queryByText('Save the current page')).toBeNull();
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'unmatched-fixture' } });
    expect(screen.getByText('No matching shortcuts')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'General' }));
    expect((screen.getByRole('textbox') as HTMLInputElement).value).toBe('');
    expect(screen.getByText('Save the current page')).toBeTruthy();
  });
  it('closes via the accessible button and Escape without leaving a keyboard handler', () => {
    const close = vi.fn();
    const view = render(<ShortcutHelpPanel isOpen onClose={close} />);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(close).toHaveBeenCalledTimes(2);
    view.rerender(<ShortcutHelpPanel isOpen={false} onClose={close} />);
    expect(screen.queryByRole('heading')).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(close).toHaveBeenCalledTimes(2);
  });
});
