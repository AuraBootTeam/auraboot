import React from 'react';
import {
  SchemaRenderer,
  SchemaRendererWithContainer,
  type SchemaRendererProps,
} from '~/framework/meta/rendering/SchemaRenderer';

export interface RenderSchemaOptions {
  container?: boolean;
  className?: string;
}

/**
 * 以函数方式渲染 Schema，方便在 SSR 或微前端中使用。
 */
export function renderSchema(props: SchemaRendererProps, options: RenderSchemaOptions = {}) {
  const RendererComponent = options.container ? SchemaRendererWithContainer : SchemaRenderer;
  const mergedClassName =
    [props.className, options.className].filter(Boolean).join(' ') || undefined;

  return <RendererComponent {...props} className={mergedClassName} />;
}

/**
 * 创建一个预配置的 SchemaRenderer 组件
 */
export function createSchemaRenderer(defaultOptions: RenderSchemaOptions = {}) {
  return function SchemaRendererSDK(props: SchemaRendererProps) {
    return renderSchema(props, defaultOptions);
  };
}
