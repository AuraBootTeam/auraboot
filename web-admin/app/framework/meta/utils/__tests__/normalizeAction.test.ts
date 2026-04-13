import { describe, it, expect, beforeEach } from 'vitest';
import { normalizeAction, normalizeButtonProps, _resetWarnings } from '../normalizeAction';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

describe('normalizeAction', () => {
  beforeEach(() => {
    _resetWarnings();
  });

  it('passes through new-format ActionDef objects', () => {
    const button: ButtonConfig = {
      code: 'save',
      action: { type: 'command', command: 'save_order' },
    };
    expect(normalizeAction(button)).toEqual({ type: 'command', command: 'save_order' });
  });

  it('passes through navigate ActionDef', () => {
    const button: ButtonConfig = {
      code: 'go',
      action: { type: 'navigate', to: '/orders/{id}' },
    };
    expect(normalizeAction(button)).toEqual({ type: 'navigate', to: '/orders/{id}' });
  });

  it('converts commandCode to command action', () => {
    const button: ButtonConfig = { code: 'approve', commandCode: 'approve_order' };
    expect(normalizeAction(button)).toEqual({ type: 'command', command: 'approve_order' });
  });

  it('converts navigateTo to navigate action', () => {
    const button: ButtonConfig = { code: 'detail', navigateTo: '/orders/{id}' };
    expect(normalizeAction(button)).toEqual({ type: 'navigate', to: '/orders/{id}' });
  });

  it('converts commandCode + navigateTo to navigate with command', () => {
    const button: ButtonConfig = {
      code: 'create-and-go',
      commandCode: 'create_order',
      navigateTo: '/orders/{id}',
    };
    expect(normalizeAction(button)).toEqual({
      type: 'navigate',
      to: '/orders/{id}',
      command: 'create_order',
    });
  });

  it('converts apiAction to flow with api.request step', () => {
    const button: ButtonConfig = {
      code: 'publish',
      apiAction: {
        endpoint: '/api/pages/{pid}/publish',
        method: 'post',
        successMessage: 'Published!',
      },
    };
    const result = normalizeAction(button);
    expect(result).toEqual({
      type: 'flow',
      steps: [
        { action: 'api.request', endpoint: '/api/pages/{pid}/publish', method: 'post' },
        { action: 'toast.success', args: { message: 'Published!' } },
        { action: 'dataSource.reload', args: { target: 'list' } },
      ],
    });
  });

  it('converts apiAction without successMessage', () => {
    const button: ButtonConfig = {
      code: 'deploy',
      apiAction: { endpoint: '/api/deploy', method: 'put' },
    };
    const result = normalizeAction(button);
    expect(result).toEqual({
      type: 'flow',
      steps: [
        { action: 'api.request', endpoint: '/api/deploy', method: 'put' },
        { action: 'dataSource.reload', args: { target: 'list' } },
      ],
    });
  });

  it('converts apiAction with default method POST', () => {
    const button: ButtonConfig = {
      code: 'trigger',
      apiAction: { endpoint: '/api/trigger' },
    };
    const result = normalizeAction(button);
    expect((result as any).steps[0].method).toBe('post');
  });

  it('converts events.onClick.handler to flow handler', () => {
    const button: ButtonConfig = {
      code: 'custom',
      events: { onClick: { handler: 'handleCustomAction' } },
    };
    expect(normalizeAction(button)).toEqual({ type: 'flow', handler: 'handleCustomAction' });
  });

  it('converts navigateBack handler to builtin back', () => {
    const button: ButtonConfig = {
      code: 'goback',
      events: { onClick: { handler: 'navigateBack' } },
    };
    expect(normalizeAction(button)).toEqual({ type: 'builtin', name: 'back' });
  });

  it('maps known builtin codes (search)', () => {
    expect(normalizeAction({ code: 'search' })).toEqual({ type: 'builtin', name: 'search' });
  });

  it('maps known builtin codes (reset)', () => {
    expect(normalizeAction({ code: 'reset' })).toEqual({ type: 'builtin', name: 'reset' });
  });

  it('maps known builtin codes (export)', () => {
    expect(normalizeAction({ code: 'export' })).toEqual({ type: 'builtin', name: 'export' });
  });

  it('maps known builtin codes (delete)', () => {
    expect(normalizeAction({ code: 'delete' })).toEqual({ type: 'builtin', name: 'delete' });
  });

  it('returns noop for unknown code without any action hints', () => {
    expect(normalizeAction({ code: 'mystery' })).toEqual({ type: 'builtin', name: 'noop' });
  });
});

describe('normalizeButtonProps', () => {
  it('copies action string to label when label is absent', () => {
    const button: ButtonConfig = { code: 'save', action: 'create' };
    const result = normalizeButtonProps(button);
    expect(result.label).toBe('create');
  });

  it('does not overwrite existing label', () => {
    const button: ButtonConfig = { code: 'save', action: 'create', label: 'Add New' };
    const result = normalizeButtonProps(button);
    expect(result.label).toBe('Add New');
  });

  it('copies confirmMessageKey to confirm', () => {
    const button: ButtonConfig = { code: 'del', confirmMessageKey: 'confirm.delete' };
    const result = normalizeButtonProps(button);
    expect(result.confirm).toBe('confirm.delete');
  });

  it('does not overwrite existing confirm', () => {
    const button: ButtonConfig = {
      code: 'del',
      confirmMessageKey: 'confirm.delete',
      confirm: 'confirm.remove',
    };
    const result = normalizeButtonProps(button);
    expect(result.confirm).toBe('confirm.remove');
  });

  it('does not copy ActionDef object to label', () => {
    const button: ButtonConfig = {
      code: 'save',
      action: { type: 'command', command: 'save' },
    };
    const result = normalizeButtonProps(button);
    expect(result.label).toBeUndefined();
  });
});
