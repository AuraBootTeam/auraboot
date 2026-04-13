// web-admin/app/components/field-adapter/types.ts
import type { LocalizedText } from '~/utils/i18n';

/**
 * I18nText - 支持国际化的文本类型
 */
export type I18nText =
  | string
  | LocalizedText
  | { i18nKey: string; params?: Record<string, unknown> };

/**
 * FieldAdapter - 字段状态管理抽象接口
 * 统一 SmartForm 和 FlowDesigner 的字段状态管理
 */
export interface FieldAdapter<T = unknown> {
  /** 当前值 */
  value: T;
  /** 设置值 */
  setValue: (value: T) => void;
  /** 错误信息 */
  error?: string;
  /** 是否禁用 */
  disabled?: boolean;
  /** 是否必填 */
  required?: boolean;
  /** 是否只读 */
  readOnly?: boolean;
  /** 失焦回调 */
  onBlur?: () => void;
  /** 聚焦回调 */
  onFocus?: () => void;
}

/**
 * FieldAdapterProps - 创建 FieldAdapter 的通用 Props
 */
export interface FieldAdapterProps<T = unknown> {
  /** 字段名/键 */
  name: string;
  /** 初始值 */
  value?: T;
  /** 默认值 */
  defaultValue?: T;
  /** 是否必填 */
  required?: boolean;
  /** 是否禁用 */
  disabled?: boolean;
  /** 是否只读 */
  readOnly?: boolean;
  /** 值变更回调 */
  onChange?: (value: T) => void;
  /** 失焦回调 */
  onBlur?: () => void;
}

/**
 * BaseFieldProps - 基础字段组件通用 Props
 */
export interface BaseFieldProps {
  /** 字段适配器 */
  adapter: FieldAdapter<any>;
  /** 字段名 */
  name: string;
  /** 标签 */
  label?: string;
  /** 占位符 */
  placeholder?: string;
  /** 帮助文本 */
  helpText?: string;
  /** 自定义类名 */
  className?: string;
}
