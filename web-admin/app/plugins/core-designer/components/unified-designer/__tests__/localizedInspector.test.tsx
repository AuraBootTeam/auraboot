import React, { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: state.locale, t: (key: string) => key }),
}));
vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn().mockResolvedValue({ data: { records: [] } }),
}));
vi.mock('../inspector/schemas', () => ({
  getInspectorFields: () => [
    { key: 'title', label: 'Title', type: 'text' },
    { key: 'description', label: 'Description', type: 'textarea' },
  ],
}));

import { SchemaInspector } from '../inspector/SchemaInspector';
import type { DslBlockV3 } from '../types';

function Harness({ initial, onUpdate }: { initial: Record<string, unknown>; onUpdate: (path: string, value: unknown) => void }) {
  const [block, setBlock] = useState({ id: 'section', blockType: 'detail-section', ...initial });
  return <SchemaInspector block={block as DslBlockV3} onChange={(path, value) => {
    onUpdate(path, value);
    setBlock((current) => ({ ...current, [path]: value }));
  }} />;
}

describe('localized inspector authoring', () => {
  beforeEach(() => { state.locale = 'zh-CN'; });

  it('edits the current language while retaining other languages for text and textarea', () => {
    const onUpdate = vi.fn();
    render(<Harness initial={{
      title: { 'zh-CN': '客户信息', en: 'Customer information' },
      description: { 'zh-CN': '原说明', en: 'Original description' },
    }} onUpdate={onUpdate} />);
    expect(screen.getByTestId('inspector-field-title')).toHaveValue('客户信息');
    fireEvent.change(screen.getByTestId('inspector-field-title'), { target: { value: '客户跟进' } });
    expect(onUpdate).toHaveBeenCalledWith('title', { 'zh-CN': '客户跟进', en: 'Customer information' });
    const textarea = screen.getByTestId('inspector-field-description');
    expect(textarea.tagName).toBe('TEXTAREA');
    fireEvent.change(textarea, { target: { value: '新的说明\n下一行' } });
    expect(onUpdate).toHaveBeenCalledWith('description', { 'zh-CN': '新的说明\n下一行', en: 'Original description' });
  });

  it('switches the displayed and edited language without losing earlier changes', () => {
    const onUpdate = vi.fn();
    const initial = { title: { 'zh-CN': '原中文', en: 'Original English' } };
    const view = render(<Harness initial={initial} onUpdate={onUpdate} />);
    fireEvent.change(screen.getByTestId('inspector-field-title'), { target: { value: '新中文' } });
    state.locale = 'en';
    view.rerender(<Harness initial={initial} onUpdate={onUpdate} />);
    expect(screen.getByTestId('inspector-field-title')).toHaveValue('Original English');
    fireEvent.change(screen.getByTestId('inspector-field-title'), { target: { value: 'New English' } });
    expect(onUpdate).toHaveBeenLastCalledWith('title', { 'zh-CN': '新中文', en: 'New English' });
    state.locale = 'zh-CN';
    view.rerender(<Harness initial={initial} onUpdate={onUpdate} />);
    expect(screen.getByTestId('inspector-field-title')).toHaveValue('新中文');
  });

  it('retains plain string compatibility and an intentionally blank locale value', () => {
    const onUpdate = vi.fn();
    render(<Harness initial={{ title: 'Plain title', description: { 'zh-CN': '', en: 'Fallback' } }} onUpdate={onUpdate} />);
    fireEvent.change(screen.getByTestId('inspector-field-title'), { target: { value: 'Updated title' } });
    expect(onUpdate).toHaveBeenCalledWith('title', 'Updated title');
    expect(screen.getByTestId('inspector-field-description')).toHaveValue('');
  });
});
