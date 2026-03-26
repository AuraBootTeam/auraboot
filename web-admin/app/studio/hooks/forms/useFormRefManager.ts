/**
 * FormRef 管理器 Hook
 * 提供表单引用的 CRUD 操作和状态管理
 */

import { useState, useCallback, useRef, useEffect } from 'react';
import type {
  FormRef,
  FormRefManager,
  ValidationResult,
  FormRefComponentProps,
} from '~/studio/domain/form/types';

// 模拟的 FormRef 数据（实际项目中应该从后端 API 获取）
const mockFormRefs: FormRef[] = [
  {
    id: 'form-ref-1',
    name: 'user-profile-form',
    title: '用户资料表单',
    description: '用户基本信息编辑表单',
    version: '1.0.0',
    schema: {
      fields: [
        {
          name: 'name',
          type: 'input',
          label: '姓名',
          required: true,
          validation: [{ type: 'required', message: '请输入姓名' }],
        },
        {
          name: 'email',
          type: 'input',
          label: '邮箱',
          required: true,
          props: { type: 'email' },
          validation: [
            { type: 'required', message: '请输入邮箱' },
            { type: 'pattern', value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: '邮箱格式不正确' },
          ],
        },
        {
          name: 'phone',
          type: 'input',
          label: '手机号',
          props: { type: 'tel' },
          validation: [{ type: 'pattern', value: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }],
        },
      ],
      layout: {
        type: 'vertical',
        columns: 1,
        gap: 16,
        labelAlign: 'top',
      },
      actions: [
        {
          id: 'submit',
          type: 'submit',
          label: '保存',
          variant: 'primary',
        },
        {
          id: 'reset',
          type: 'reset',
          label: '重置',
          variant: 'outline',
        },
      ],
      validation: {
        mode: 'onChange',
      },
      data: {
        initialValues: {
          name: '',
          email: '',
          phone: '',
        },
      },
    },
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 'form-ref-2',
    name: 'product-form',
    title: '产品信息表单',
    description: '产品基本信息录入表单',
    version: '1.0.0',
    schema: {
      fields: [
        {
          name: 'name',
          type: 'input',
          label: '产品名称',
          required: true,
          validation: [{ type: 'required', message: '请输入产品名称' }],
        },
        {
          name: 'category',
          type: 'select',
          label: '产品分类',
          required: true,
          props: {
            options: [
              { label: '电子产品', value: 'electronics' },
              { label: '服装', value: 'clothing' },
              { label: '食品', value: 'food' },
            ],
          },
          validation: [{ type: 'required', message: '请选择产品分类' }],
        },
        {
          name: 'price',
          type: 'input',
          label: '价格',
          required: true,
          props: { type: 'number', min: 0 },
          validation: [
            { type: 'required', message: '请输入价格' },
            { type: 'min', value: 0, message: '价格不能小于0' },
          ],
        },
        {
          name: 'description',
          type: 'textarea',
          label: '产品描述',
          props: { rows: 4 },
        },
      ],
      layout: {
        type: 'vertical',
        columns: 1,
        gap: 16,
        labelAlign: 'top',
      },
      actions: [
        {
          id: 'submit',
          type: 'submit',
          label: '保存产品',
          variant: 'primary',
        },
        {
          id: 'cancel',
          type: 'cancel',
          label: '取消',
          variant: 'outline',
        },
      ],
      validation: {
        mode: 'onBlur',
      },
    },
    createdAt: '2024-01-02T00:00:00Z',
    updatedAt: '2024-01-02T00:00:00Z',
  },
];

class FormRefManagerImpl implements FormRefManager {
  private formRefs: Map<string, FormRef> = new Map();

  constructor() {
    // 初始化模拟数据
    mockFormRefs.forEach((formRef) => {
      this.formRefs.set(formRef.id, formRef);
    });
  }

  async getFormRef(id: string): Promise<FormRef | null> {
    // 模拟异步操作
    await new Promise((resolve) => setTimeout(resolve, 100));
    return this.formRefs.get(id) || null;
  }

  async getAllFormRefs(): Promise<FormRef[]> {
    // 模拟异步操作
    await new Promise((resolve) => setTimeout(resolve, 100));
    return Array.from(this.formRefs.values());
  }

  async createFormRef(formRef: Omit<FormRef, 'id' | 'createdAt' | 'updatedAt'>): Promise<FormRef> {
    // 模拟异步操作
    await new Promise((resolve) => setTimeout(resolve, 200));

    const newFormRef: FormRef = {
      ...formRef,
      id: `form-ref-${Date.now()}`,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };

    this.formRefs.set(newFormRef.id, newFormRef);
    return newFormRef;
  }

  async updateFormRef(id: string, updates: Partial<FormRef>): Promise<FormRef> {
    // 模拟异步操作
    await new Promise((resolve) => setTimeout(resolve, 200));

    const existingFormRef = this.formRefs.get(id);
    if (!existingFormRef) {
      throw new Error(`FormRef with id ${id} not found`);
    }

    const updatedFormRef: FormRef = {
      ...existingFormRef,
      ...updates,
      id, // 确保 ID 不被覆盖
      updatedAt: new Date().toISOString(),
    };

    this.formRefs.set(id, updatedFormRef);
    return updatedFormRef;
  }

  async deleteFormRef(id: string): Promise<void> {
    // 模拟异步操作
    await new Promise((resolve) => setTimeout(resolve, 100));

    if (!this.formRefs.has(id)) {
      throw new Error(`FormRef with id ${id} not found`);
    }

    this.formRefs.delete(id);
  }

  async validateFormRef(formRef: FormRef): Promise<ValidationResult> {
    // 模拟异步操作
    await new Promise((resolve) => setTimeout(resolve, 100));

    const errors: any[] = [];
    const warnings: any[] = [];

    // 基本验证
    if (!formRef.name) {
      errors.push({ message: 'FormRef name is required', code: 'missing_name' });
    }

    if (!formRef.title) {
      errors.push({ message: 'FormRef title is required', code: 'missing_title' });
    }

    if (!formRef.schema.fields || formRef.schema.fields.length === 0) {
      errors.push({ message: 'FormRef must have at least one field', code: 'no_fields' });
    }

    // 字段验证
    formRef.schema.fields.forEach((field, index) => {
      if (!field.name) {
        errors.push({
          field: `fields[${index}]`,
          message: 'Field name is required',
          code: 'missing_field_name',
        });
      }

      if (!field.type) {
        errors.push({
          field: `fields[${index}]`,
          message: 'Field type is required',
          code: 'missing_field_type',
        });
      }

      if (!field.label) {
        warnings.push({
          field: `fields[${index}]`,
          message: 'Field label is recommended',
          code: 'missing_field_label',
        });
      }
    });

    return {
      valid: errors.length === 0,
      errors,
      warnings,
    };
  }

  renderFormRef(formRefId: string, props?: Partial<FormRefComponentProps>): React.ReactElement {
    // 这里应该返回 SmartFormRef 组件
    // 由于这是在 Hook 中，实际实现会在组件中处理
    throw new Error('renderFormRef should be called from a React component');
  }
}

// 单例实例
const formRefManagerInstance = new FormRefManagerImpl();

export const useFormRefManager = () => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const managerRef = useRef<FormRefManager>(formRefManagerInstance);

  // 包装异步操作以处理加载状态和错误
  const withLoadingAndError = useCallback(
    async <T>(operation: () => Promise<T>): Promise<T | null> => {
      setLoading(true);
      setError(null);

      try {
        const result = await operation();
        return result;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : 'Unknown error';
        setError(errorMessage);
        console.error('FormRefManager operation failed:', err);
        return null;
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  // 获取表单引用
  const getFormRef = useCallback(
    async (id: string) => {
      return withLoadingAndError(() => managerRef.current.getFormRef(id));
    },
    [withLoadingAndError],
  );

  // 获取所有表单引用
  const getAllFormRefs = useCallback(async () => {
    return withLoadingAndError(() => managerRef.current.getAllFormRefs());
  }, [withLoadingAndError]);

  // 创建表单引用
  const createFormRef = useCallback(
    async (formRef: Omit<FormRef, 'id' | 'createdAt' | 'updatedAt'>) => {
      return withLoadingAndError(() => managerRef.current.createFormRef(formRef));
    },
    [withLoadingAndError],
  );

  // 更新表单引用
  const updateFormRef = useCallback(
    async (id: string, updates: Partial<FormRef>) => {
      return withLoadingAndError(() => managerRef.current.updateFormRef(id, updates));
    },
    [withLoadingAndError],
  );

  // 删除表单引用
  const deleteFormRef = useCallback(
    async (id: string) => {
      return withLoadingAndError(() => managerRef.current.deleteFormRef(id));
    },
    [withLoadingAndError],
  );

  // 验证表单引用
  const validateFormRef = useCallback(
    async (formRef: FormRef) => {
      return withLoadingAndError(() => managerRef.current.validateFormRef(formRef));
    },
    [withLoadingAndError],
  );

  // 清除错误
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  return {
    // 状态
    loading,
    error,

    // 方法
    getFormRef,
    getAllFormRefs,
    createFormRef,
    updateFormRef,
    deleteFormRef,
    validateFormRef,
    clearError,

    // 原始管理器实例（用于高级用法）
    manager: managerRef.current,
  };
};

export default useFormRefManager;
