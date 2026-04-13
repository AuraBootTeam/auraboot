import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';
import { getPageStateManager } from '~/plugins/core-designer/components/studio/services/state/PageStateManager';

export interface SchemaExportOptions {
  includeLocalStorageProps?: boolean;
  filename?: string;
}

export interface SchemaExportResult {
  filename: string;
  payload: any;
}

export async function buildSchemaExport(
  pageId: string,
  options: SchemaExportOptions = {},
): Promise<SchemaExportResult> {
  const stateManager = getPageStateManager();
  const originalExportData = await stateManager.exportState();
  let finalExportData = originalExportData;

  if (options.includeLocalStorageProps !== false && originalExportData?.pageSchema?.components) {
    const mergedComponents = await Promise.all(
      originalExportData.pageSchema.components.map(async (component: any) => {
        try {
          const storageKey = `component-properties-${component.id}`;
          const savedPropsStr =
            typeof window !== 'undefined' ? localStorage.getItem(storageKey) : null;

          if (!savedPropsStr) {
            return component;
          }

          const savedProps = JSON.parse(savedPropsStr);
          return {
            ...component,
            props: {
              ...component.props,
              ...savedProps,
            },
          };
        } catch (error) {
          console.error('[schemaIO] Failed to merge component props', error);
          return component;
        }
      }),
    );

    finalExportData = {
      ...originalExportData,
      pageSchema: {
        ...originalExportData.pageSchema,
        components: mergedComponents,
      },
    };
  }

  return {
    filename: options.filename ?? `page_${pageId}_${Date.now()}.json`,
    payload: finalExportData,
  };
}

export async function applyImportedSchema(importData: any): Promise<FormSchema | null> {
  const stateManager = getPageStateManager();
  await stateManager.importState(importData);

  if (importData?.schema) {
    return importData.schema as FormSchema;
  }

  if (importData?.pageSchema) {
    return importData.pageSchema as FormSchema;
  }

  return null;
}
