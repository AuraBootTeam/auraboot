/**
 * Field配置组件
 *
 * 用于配置Field在Model中的属性
 *
 * 功能特性:
 * - 配置必填属性
 * - 配置默认值
 * - 配置验证规则
 * - 关联字典选择
 * - 配置显示顺序
 */

import React, { useState, useCallback, useEffect } from 'react';
import type { ModelFieldBinding, ValidationRule } from '~/types/model';
import type { MetaFieldDTO } from '~/types/field';
import { FormulaEditor } from '~/smart/components/formula/FormulaEditor';

/**
 * Field配置Props
 */
interface FieldConfigProps {
  /** 是否显示配置器 */
  visible: boolean;
  /** Field信息 */
  field: MetaFieldDTO | null;
  /** 现有配置（编辑模式） */
  existingConfig?: ModelFieldBinding;
  /** 关闭回调 */
  onClose: () => void;
  /** 保存配置回调 */
  onSave: (config: Partial<ModelFieldBinding>) => void;
}

/**
 * Field配置组件
 */
export function FieldConfig({ visible, field, existingConfig, onClose, onSave }: FieldConfigProps) {
  // 配置表单状态
  const [config, setConfig] = useState<Partial<ModelFieldBinding>>({
    required: false,
    defaultValue: undefined,
    validationRules: [],
    dictCode: undefined,
    displayOrder: 0,
    extension: {},
  });

  // Default value mode: static text or expression
  const [defaultValueMode, setDefaultValueMode] = useState<'static' | 'expression'>('static');

  // 可用字典列表
  const [availableDicts, setAvailableDicts] = useState<Array<{ code: string; name: string }>>([]);

  /**
   * 初始化配置
   */
  useEffect(() => {
    if (visible && field) {
      if (existingConfig) {
        // 编辑模式 - 加载现有配置
        setConfig({
          required: existingConfig.required,
          defaultValue: existingConfig.defaultValue,
          validationRules: existingConfig.validationRules || [],
          dictCode: existingConfig.dictCode,
          displayOrder: existingConfig.displayOrder,
          extension: existingConfig.extension || {},
        });
        // Detect expression mode from extension
        if (existingConfig.extension?.defaultValueExpression) {
          setDefaultValueMode('expression');
        }
      } else {
        // 新建模式 - 使用默认配置
        setConfig({
          required: field.required || false,
          defaultValue: field.defaultValue,
          validationRules: [],
          dictCode: field.dictCode,
          displayOrder: 0,
          extension: {},
        });
      }

      // 加载可用字典列表
      loadAvailableDicts();
    }
  }, [visible, field, existingConfig]);

  /**
   * 加载可用字典
   */
  const loadAvailableDicts = useCallback(async () => {
    try {
      // TODO: 调用API加载字典列表
      // const dicts = await dictService.findAll();
      // setAvailableDicts(dicts);

      // 模拟数据
      setAvailableDicts([
        { code: 'user_status', name: '用户状态' },
        { code: 'gender', name: '性别' },
        { code: 'department', name: '部门' },
        { code: 'role', name: '角色' },
      ]);
    } catch (error) {
      console.error('Failed to load dicts:', error);
    }
  }, []);

  /**
   * 更新配置字段
   */
  const updateConfig = useCallback((field: string, value: any) => {
    setConfig((prev) => ({ ...prev, [field]: value }));
  }, []);

  /**
   * 添加验证规则
   */
  const addValidationRule = useCallback(() => {
    const newRule: ValidationRule = {
      type: 'required',
      message: '此字段为必填项',
    };

    setConfig((prev) => ({
      ...prev,
      validationRules: [...(prev.validationRules || []), newRule],
    }));
  }, []);

  /**
   * 更新验证规则
   */
  const updateValidationRule = useCallback((index: number, field: string, value: any) => {
    setConfig((prev) => {
      const rules = [...(prev.validationRules || [])];
      rules[index] = { ...rules[index], [field]: value };
      return { ...prev, validationRules: rules };
    });
  }, []);

  /**
   * 删除验证规则
   */
  const removeValidationRule = useCallback((index: number) => {
    setConfig((prev) => ({
      ...prev,
      validationRules: (prev.validationRules || []).filter((_, i) => i !== index),
    }));
  }, []);

  /**
   * 保存配置
   */
  const handleSave = useCallback(() => {
    onSave(config);
    onClose();
  }, [config, onSave, onClose]);

  /**
   * 取消配置
   */
  const handleCancel = useCallback(() => {
    onClose();
  }, [onClose]);

  if (!visible || !field) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={handleCancel} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="relative flex max-h-[80vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
          {/* 标题栏 */}
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">配置字段</h2>
            <p className="mt-1 text-sm text-gray-500">
              配置字段 <span className="font-mono text-blue-600">{field.code}</span> 在模型中的属性
            </p>
          </div>

          {/* 配置表单 */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            <div className="space-y-6">
              {/* 基本配置 */}
              <div>
                <h3 className="mb-3 text-sm font-medium text-gray-900">基本配置</h3>

                <div className="space-y-4">
                  {/* 必填 */}
                  <div className="flex items-center">
                    <input
                      type="checkbox"
                      id="required"
                      checked={config.required}
                      onChange={(e) => updateConfig('required', e.target.checked)}
                      className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                    <label htmlFor="required" className="ml-2 text-sm text-gray-700">
                      必填字段
                    </label>
                  </div>

                  {/* 默认值 */}
                  <div>
                    <div className="mb-1 flex items-center justify-between">
                      <label className="block text-sm font-medium text-gray-700">默认值</label>
                      <div className="flex overflow-hidden rounded-md border border-gray-300">
                        <button
                          type="button"
                          onClick={() => setDefaultValueMode('static')}
                          className={`px-2.5 py-1 text-xs transition-colors ${
                            defaultValueMode === 'static'
                              ? 'bg-blue-500 text-white'
                              : 'bg-white text-gray-600 hover:bg-gray-50'
                          }`}
                        >
                          静态值
                        </button>
                        <button
                          type="button"
                          onClick={() => setDefaultValueMode('expression')}
                          className={`border-l px-2.5 py-1 text-xs transition-colors ${
                            defaultValueMode === 'expression'
                              ? 'bg-blue-500 text-white'
                              : 'bg-white text-gray-600 hover:bg-gray-50'
                          }`}
                        >
                          表达式
                        </button>
                      </div>
                    </div>
                    {defaultValueMode === 'static' ? (
                      <input
                        type="text"
                        value={String(config.defaultValue ?? '')}
                        onChange={(e) => updateConfig('defaultValue', e.target.value)}
                        placeholder="请输入默认值"
                        className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      />
                    ) : (
                      <FormulaEditor
                        value={(config.extension?.defaultValueExpression as string) || ''}
                        onChange={(val) =>
                          updateConfig('extension', {
                            ...config.extension,
                            defaultValueExpression: val,
                          })
                        }
                        placeholder="例如: #NOW() 或 #currentUser"
                      />
                    )}
                  </div>

                  {/* 显示顺序 */}
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">显示顺序</label>
                    <input
                      type="number"
                      value={config.displayOrder || 0}
                      onChange={(e) => updateConfig('displayOrder', parseInt(e.target.value))}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                    <p className="mt-1 text-xs text-gray-500">数字越小越靠前</p>
                  </div>
                </div>
              </div>

              {/* 字典关联 */}
              <div>
                <h3 className="mb-3 text-sm font-medium text-gray-900">字典关联</h3>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">关联字典</label>
                  <select
                    value={config.dictCode || ''}
                    onChange={(e) => updateConfig('dictCode', e.target.value || undefined)}
                    className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  >
                    <option value="">不关联字典</option>
                    {availableDicts.map((dict) => (
                      <option key={dict.code} value={dict.code}>
                        {dict.name} ({dict.code})
                      </option>
                    ))}
                  </select>
                  <p className="mt-1 text-xs text-gray-500">
                    关联字典后，字段将使用字典中的选项作为可选值
                  </p>
                </div>
              </div>

              {/* 验证规则 */}
              <div>
                <div className="mb-3 flex items-center justify-between">
                  <h3 className="text-sm font-medium text-gray-900">验证规则</h3>
                  <button
                    onClick={addValidationRule}
                    className="text-sm text-blue-600 hover:text-blue-700"
                  >
                    + 添加规则
                  </button>
                </div>

                {(config.validationRules || []).length === 0 ? (
                  <p className="text-sm text-gray-500">暂无验证规则</p>
                ) : (
                  <div className="space-y-3">
                    {(config.validationRules || []).map((rule, index) => (
                      <div key={index} className="rounded-lg border border-gray-200 p-3">
                        <div className="mb-2 flex items-start justify-between">
                          <select
                            value={rule.type}
                            onChange={(e) => updateValidationRule(index, 'type', e.target.value)}
                            className="rounded border border-gray-300 px-2 py-1 text-sm"
                          >
                            <option value="required">必填</option>
                            <option value="pattern">正则表达式</option>
                            <option value="minLength">最小长度</option>
                            <option value="maxLength">最大长度</option>
                            <option value="min">最小值</option>
                            <option value="max">最大值</option>
                            <option value="custom">自定义</option>
                          </select>
                          <button
                            onClick={() => removeValidationRule(index)}
                            className="text-sm text-red-600 hover:text-red-700"
                          >
                            删除
                          </button>
                        </div>

                        {/* 规则值 */}
                        {['pattern', 'minLength', 'maxLength', 'min', 'max'].includes(
                          rule.type,
                        ) && (
                          <div className="mb-2">
                            <input
                              type="text"
                              value={rule.value || ''}
                              onChange={(e) => updateValidationRule(index, 'value', e.target.value)}
                              placeholder="请输入规则值"
                              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                            />
                          </div>
                        )}

                        {/* 错误消息 */}
                        <div>
                          <input
                            type="text"
                            value={rule.message}
                            onChange={(e) => updateValidationRule(index, 'message', e.target.value)}
                            placeholder="错误提示消息"
                            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* 底部按钮 */}
          <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
            <button
              onClick={handleCancel}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            >
              取消
            </button>
            <button
              onClick={handleSave}
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
            >
              保存
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
