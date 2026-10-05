import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActionPhaseEditor } from './ActionPhaseEditor';
import type { ActionPhase } from './types';

const f = vi.hoisted(() => ({ locale: 'en-US' }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: f.locale }) }));
const phase: ActionPhase = { id: 'api-1', type: 'apiCall', config: { endpoint: '/api/custom', method: 'post', untouched: 0 }, onError: 'stop' };
const callbacks = { onAdd: vi.fn(), onRemove: vi.fn(), onUpdate: vi.fn(), onMove: vi.fn() };
afterEach(cleanup);
beforeEach(() => { vi.clearAllMocks(); f.locale = 'en-US'; });
function open() { fireEvent.click(screen.getAllByRole('button')[1]); }

describe('action phase editor localized controls and unchanged action semantics', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    const en = locale === 'en-US';
    it(`${locale} filters additions by category and emits only the phase type`, () => {
      f.locale = locale;
      render(<ActionPhaseEditor category="execute" phases={[]} {...callbacks} />);
      expect(screen.getByText(en ? 'Execution phase' : '\u6267\u884c\u9636\u6bb5')).toBeTruthy();
      expect(screen.getByText(en ? 'No steps' : '\u65e0\u6b65\u9aa4')).toBeTruthy();
      fireEvent.click(screen.getByTitle(en ? 'Add step' : '\u6dfb\u52a0\u6b65\u9aa4'));
      expect(screen.queryByText(en ? 'Navigate' : '\u9875\u9762\u8df3\u8f6c')).toBeNull();
      fireEvent.click(screen.getByRole('button', { name: new RegExp(en ? 'Execute command' : '\u6267\u884c\u547d\u4ee4') }));
      expect(callbacks.onAdd).toHaveBeenCalledExactlyOnceWith('apiCall');
      expect(screen.queryByRole('button', { name: new RegExp(en ? 'Execute command' : '\u6267\u884c\u547d\u4ee4') })).toBeNull();
    });
    it(`${locale} edits the endpoint and error policy without changing other configuration`, () => {
      f.locale = locale;
      render(<ActionPhaseEditor category="execute" phases={[phase]} {...callbacks} />);
      open();
      expect(screen.getByText(en ? 'Error policy' : '\u5931\u8d25\u7b56\u7565')).toBeTruthy();
      expect(screen.getByTitle(en ? 'Stop on failure' : '\u5931\u8d25\u65f6\u505c\u6b62')).toBeTruthy();
      fireEvent.change(screen.getByRole('combobox'), { target: { value: 'retry' } });
      expect(callbacks.onUpdate).toHaveBeenLastCalledWith('api-1', { onError: 'retry' });
      fireEvent.change(screen.getByDisplayValue('/api/custom'), { target: { value: '/api/changed' } });
      expect(callbacks.onUpdate).toHaveBeenLastCalledWith('api-1', { config: { endpoint: '/api/changed', method: 'post', untouched: 0 } });
      expect(phase.config.endpoint).toBe('/api/custom');
      expect(screen.getByText(en ? 'Endpoint' : '\u7aef\u70b9')).toBeTruthy();
      expect(screen.getByText(en ? 'Method' : '\u65b9\u6cd5')).toBeTruthy();
    });
    it(`${locale} preserves user labels and routes reorder/remove to exact identities`, () => {
      f.locale = locale;
      const custom = { ...phase, label: '\u7528\u6237\u5b9a\u4e49\u540d\u79f0' };
      render(<ActionPhaseEditor category="execute" phases={[custom, { ...phase, id: 'api-2' }]} {...callbacks} />);
      expect(screen.getByText('\u7528\u6237\u5b9a\u4e49\u540d\u79f0')).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: en ? 'Move down' : '\u4e0b\u79fb' }));
      expect(callbacks.onMove).toHaveBeenCalledExactlyOnceWith('api-1', 'down');
      fireEvent.click(screen.getAllByRole('button', { name: en ? 'Remove step' : '\u5220\u9664\u6b65\u9aa4' })[1]);
      expect(callbacks.onRemove).toHaveBeenCalledExactlyOnceWith('api-2');
    });
    for (const [type, key, labelEn, labelZh] of [
      ['clientValidate', 'expression', 'Expression', '\u8868\u8fbe\u5f0f'],
      ['apiCall', 'endpoint', 'Endpoint', '\u7aef\u70b9'],
      ['navigate', 'path', 'Path', '\u8def\u5f84'],
      ['notify', 'message', 'Message', '\u6d88\u606f'],
      ['refresh', 'target', 'Target', '\u76ee\u6807'],
      ['openModal', 'modalId', 'Modal ID', '\u5f39\u7a97ID'],
      ['custom', 'handler', 'Handler', '\u5904\u7406\u5668'],
    ] as const) {
      it(`${locale} ${type} edits only the selected field`, () => {
        f.locale = locale;
        const current: ActionPhase = { id: 'field-1', type, config: { [key]: 'existing-value', keep: false } };
        const category = type === 'clientValidate' ? 'pre' : type === 'apiCall' ? 'execute' : 'post';
        render(<ActionPhaseEditor category={category} phases={[current]} {...callbacks} />);
        open();
        fireEvent.change(screen.getByRole('textbox', { name: en ? labelEn : labelZh }), { target: { value: 'new-value' } });
        expect(callbacks.onUpdate).toHaveBeenCalledExactlyOnceWith('field-1', { config: { [key]: 'new-value', keep: false } });
        expect(current.config[key]).toBe('existing-value');
        if (type === 'notify') {
          expect(screen.getByRole('option', { name: en ? 'Warning' : '\u8b66\u544a' })).toHaveAttribute('value', 'warning');
          fireEvent.change(screen.getAllByRole('combobox')[1], { target: { value: 'warning' } });
          expect(callbacks.onUpdate).toHaveBeenLastCalledWith('field-1', { config: { message: 'existing-value', keep: false, type: 'warning' } });
        }
      });
    }
    it(`${locale} readonly exposes configuration but blocks all mutations`, () => {
      f.locale = locale;
      render(<ActionPhaseEditor category="execute" phases={[phase]} {...callbacks} readonly />);
      expect(screen.queryByTitle(en ? 'Add step' : '\u6dfb\u52a0\u6b65\u9aa4')).toBeNull();
      fireEvent.click(screen.getAllByRole('button')[0]);
      expect(screen.getByRole('combobox')).toBeDisabled();
      expect(screen.getByDisplayValue('/api/custom')).toBeDisabled();
      expect(screen.queryByRole('button', { name: en ? 'Remove step' : '\u5220\u9664\u6b65\u9aa4' })).toBeNull();
      expect(callbacks.onUpdate).not.toHaveBeenCalled();
      expect(callbacks.onAdd).not.toHaveBeenCalled();
      expect(callbacks.onRemove).not.toHaveBeenCalled();
      expect(callbacks.onMove).not.toHaveBeenCalled();
    });
  }
  it('locale switching retains expanded config and emits no changes', () => {
    const props = { category: 'execute' as const, phases: [phase], ...callbacks };
    const view = render(<ActionPhaseEditor {...props} />);
    open();
    expect(screen.getByText('Error policy')).toBeTruthy();
    f.locale = 'zh-CN'; view.rerender(<ActionPhaseEditor {...props} />);
    expect(screen.getByText('\u5931\u8d25\u7b56\u7565')).toBeTruthy();
    expect(screen.queryByText('Error policy')).toBeNull();
    expect(screen.getByDisplayValue('/api/custom')).toBeTruthy();
    expect(callbacks.onUpdate).not.toHaveBeenCalled();
    expect(callbacks.onAdd).not.toHaveBeenCalled();
  });
});
