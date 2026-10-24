import type { ModelFieldDefinition, PageSchemaV3 } from '../types';
import { DesignerPageModelCodeContext } from './platformTablePreview';
import type { RuntimeExecutionServices } from './runtimeExecution';
import { usePermissions } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import React from 'react';
import { RuntimeBlock } from './renderer/RuntimeBlockTree';
import {
  EMPTY_MODEL_FIELDS,
  EMPTY_PREVIEW_FORM_VALUES,
  RuntimeInitialFormValuesContext,
  RuntimeLocaleContext,
  RuntimeModelFieldsContext,
  RuntimePermissionContext,
  type RuntimePageContext,
} from './renderer/runtimeContexts';
import { getCurrentRouteQuery } from './renderer/runtimePresentation';





interface RecursiveBlockRendererProps {
  schema: PageSchemaV3;
  runtimeServices?: RuntimeExecutionServices;
  permissionEvaluator?: (permissionCode: string) => boolean;
  /** Disable every native control; used by governed structure-only previews. */
  interactionDisabled?: boolean;
  /** Ephemeral fixture values used only by the isolated synthetic-data preview. */
  previewInitialFormValues?: Record<string, unknown>;
  /**
   * Model field metadata for the page's primary model. When provided, form `field`
   * blocks render the real platform control (true WYSIWYG) via {@link RuntimePlatformField}
   * instead of the generic representative input. Absent (default) → legacy preview,
   * so existing callers/tests are unaffected.
   */
  modelFields?: ModelFieldDefinition[];
}

export function RecursiveBlockRenderer({
  schema,
  runtimeServices,
  permissionEvaluator,
  interactionDisabled = false,
  previewInitialFormValues,
  modelFields,
}: RecursiveBlockRendererProps) {
  const { hasPermission } = usePermissions();
  const { locale } = useI18n();
  const evaluatePermission = permissionEvaluator ?? hasPermission;
  const modelFieldList = modelFields ?? EMPTY_MODEL_FIELDS;
  // Explicit component configurations also render platform controls without model
  // metadata. Every preview therefore needs the same data-source context.
  const dataSourceManager = React.useMemo(
    () => new DataSourceManager(createExpressionContext({ locale })),
    [locale],
  );
  const pageContext: RuntimePageContext = {
    source: 'unified-designer-runtime-preview',
    pageId: schema.id,
    pageKind: schema.kind,
    schemaVersion: schema.schemaVersion,
    routeQuery: getCurrentRouteQuery(),
  };

  const tree = (
    <RuntimeLocaleContext.Provider value={locale}>
      <RuntimeInitialFormValuesContext.Provider
        value={previewInitialFormValues ?? EMPTY_PREVIEW_FORM_VALUES}
      >
        <RuntimeModelFieldsContext.Provider value={modelFieldList}>
          <DesignerPageModelCodeContext.Provider value={schema.modelCode}>
          <RuntimePermissionContext.Provider value={evaluatePermission}>
            <fieldset
              disabled={interactionDisabled}
              className="contents"
              data-interaction-disabled={interactionDisabled ? 'true' : undefined}
            >
              <div
                className="grid grid-cols-12 gap-4"
                data-testid={`runtime-page-${schema.id}`}
                data-schema-version={schema.schemaVersion}
              >
                {schema.blocks.map((block) => (
                  <RuntimeBlock
                    key={block.id}
                    block={block.blockType === 'form' ? { ...block, extension: schema.extension } : block}
                    runtimeServices={runtimeServices}
                    pageContext={pageContext}
                    blockPath={[block.id]}
                  />
                ))}
              </div>
            </fieldset>
          </RuntimePermissionContext.Provider>
          </DesignerPageModelCodeContext.Provider>
        </RuntimeModelFieldsContext.Provider>
      </RuntimeInitialFormValuesContext.Provider>
    </RuntimeLocaleContext.Provider>
  );

  return <DataSourceProvider manager={dataSourceManager}>{tree}</DataSourceProvider>;
}
