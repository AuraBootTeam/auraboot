import { describe, it, expect } from 'vitest';
import { bindingToConfig, configToBinding } from '../FieldConfigDialog';
import type { ModelFieldBinding } from '~/types/model';

describe('FieldConfigDialog adapters', () => {
  it('bindingToConfig detects expression mode from extension', () => {
    const binding = {
      required: true, readonly: false, visible: true, displayOrder: 5,
      defaultValue: null,
      extension: { defaultValueExpression: '#NOW()' },
      dictCode: 'gender', validationRules: [],
    } as unknown as ModelFieldBinding;

    const cfg = bindingToConfig(binding);
    expect(cfg.defaultValueMode).toBe('expression');
    expect(cfg.defaultValueExpression).toBe('#NOW()');
    expect(cfg.defaultValue).toBeUndefined();
    expect(cfg.dictCode).toBe('gender');
    expect(cfg.required).toBe(true);
  });

  it('bindingToConfig defaults to static mode when no expression', () => {
    const binding = {
      required: false, readonly: false, visible: true, displayOrder: 0,
      defaultValue: 'hello', extension: {},
    } as unknown as ModelFieldBinding;
    const cfg = bindingToConfig(binding);
    expect(cfg.defaultValueMode).toBe('static');
    expect(cfg.defaultValue).toBe('hello');
    expect(cfg.defaultValueExpression).toBeUndefined();
  });

  it('configToBinding in static mode wipes extension and keeps defaultValue', () => {
    const binding = configToBinding({
      required: true, readonly: false, visible: true, displayOrder: 1,
      defaultValueMode: 'static', defaultValue: 'foo', dictCode: 'role',
      validationRules: [],
    });
    expect(binding.defaultValue).toBe('foo');
    expect(binding.extension).toEqual({});
    expect(binding.dictCode).toBe('role');
    expect(binding.validationRules).toBeUndefined();
  });

  it('configToBinding in expression mode nullifies defaultValue and stores expression', () => {
    const binding = configToBinding({
      required: false, readonly: false, visible: true, displayOrder: 0,
      defaultValueMode: 'expression', defaultValueExpression: '#currentUser',
      validationRules: [{ type: 'required', message: '必填' }],
    });
    expect(binding.defaultValue).toBeNull();
    expect(binding.extension).toEqual({ defaultValueExpression: '#currentUser' });
    expect(binding.validationRules).toHaveLength(1);
  });

  it('configToBinding omits dictCode when blank', () => {
    const binding = configToBinding({
      required: false, readonly: false, visible: true, displayOrder: 0,
      defaultValueMode: 'static', dictCode: '',
    });
    expect(binding.dictCode).toBeUndefined();
  });
});
