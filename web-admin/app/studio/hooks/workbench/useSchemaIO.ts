import { useCallback } from 'react';
import type { FormSchema } from '~/studio/domain/schema/types';
import { applyImportedSchema, buildSchemaExport } from '~/studio/services/workflow/schemaIO';

interface UseSchemaIOOptions {
  pageId: string;
  onSchemaImported?: (schema: FormSchema) => void;
}

export function useSchemaIO({ pageId, onSchemaImported }: UseSchemaIOOptions) {
  const exportSchema = useCallback(async () => {
    const { payload, filename } = await buildSchemaExport(pageId);
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }, [pageId]);

  const importSchema = useCallback(
    async (file: File) => {
      const text = await file.text();
      const importData = JSON.parse(text);
      const schema = await applyImportedSchema(importData);
      if (schema) {
        onSchemaImported?.(schema);
      }
      return schema;
    },
    [onSchemaImported],
  );

  return {
    exportSchema,
    importSchema,
  };
}
