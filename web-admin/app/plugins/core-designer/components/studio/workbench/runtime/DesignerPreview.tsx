import React, { useMemo } from 'react';
import type { NavigateFunction } from 'react-router';
import { createExpressionContext } from '~/meta/runtime/expression/context';
import { SchemaRendererWithContainer } from '~/meta/rendering/SchemaRenderer';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';
import {
  convertSchemaToUnified,
  usePageDataSources,
  useSchemaRuntime,
} from '~/plugins/core-designer/components/studio/services/runtime/SchemaRuntimeAdapter';

export interface DesignerPreviewProps {
  schema: FormSchema;
  locale?: string;
}

const fallbackNavigate: NavigateFunction = ((to: any) => {
  if (process.env.NODE_ENV !== 'production') {
    // eslint-disable-next-line no-console
    console.info('[DesignerPreview] navigate (stub):', to);
  }
  return to;
}) as NavigateFunction;

/**
 * DesignerPreview
 *
 * 使用 meta 运行时权限渲染设计器当前 Schema，保持运行期一致性。
 */
export const DesignerPreview: React.FC<DesignerPreviewProps> = ({ schema, locale = 'zh-CN' }) => {
  const unifiedSchema = useMemo(() => convertSchemaToUnified(schema), [schema]);

  const expressionContext = useMemo(
    () =>
      createExpressionContext({
        locale,
        global: {
          locale,
          theme: 'light',
          user: {
            id: 'designer',
            name: 'Designer Preview',
            email: 'designer@auraboot.dev',
            roles: ['designer'],
            permissions: ['*'],
          },
        },
        state: {},
        form: {},
        args: {},
      }),
    [locale],
  );

  const { manager: dataSourceManager } = usePageDataSources({
    context: expressionContext,
    schema: unifiedSchema,
  });

  const runtime = useSchemaRuntime({
    schema: unifiedSchema,
    dataSourceManager,
    navigate: fallbackNavigate,
    locale,
    t: (key) => key,
    disableAutoFetch: true,
  });

  if (!runtime) {
    return (
      <div className="flex h-full w-full items-center justify-center text-sm text-gray-500">
        初始化预览运行时...
      </div>
    );
  }

  return <SchemaRendererWithContainer schema={unifiedSchema} runtime={runtime} />;
};

export default DesignerPreview;
