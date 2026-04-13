/**
 * FieldRenderer - 统一字段渲染入口
 *
 * 当前运行时模式下直接委托给 RuntimeFieldRenderer，后续可在此聚合
 * 不同渲染模式（受控/运行时）或注入额外的上下文。
 */

import React from 'react';
import {
  RuntimeFieldRenderer,
  type RuntimeFieldRendererProps,
} from '~/framework/meta/rendering/RuntimeFieldRenderer';

export type FieldRendererProps = RuntimeFieldRendererProps;

export const FieldRenderer: React.FC<FieldRendererProps> = (props) => {
  return <RuntimeFieldRenderer {...props} />;
};

export default FieldRenderer;
