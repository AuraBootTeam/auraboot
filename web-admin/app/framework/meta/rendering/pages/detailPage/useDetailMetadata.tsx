import { useCallback, useMemo } from 'react';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { useToastContext } from '~/contexts/ToastContext';
import type { FieldConfig } from '~/framework/meta/schemas/types';
import { enrichDetailField, collectDetailDictCodes } from './detailFieldDisplay';
import type { useDetailPageState } from './useDetailPageState';

type UseDetailMetadataArgs = Pick<
  ReturnType<typeof useDetailPageState>,
  'modelFieldMap' | 'schema' | 'token'
>;

export function useDetailMetadata({ modelFieldMap, schema, token }: UseDetailMetadataArgs) {
  // Enrich a page-schema field with model field metadata (dictCode, component, dataType)
  const enrichField = useCallback(
    (field: FieldConfig): FieldConfig => {
      const meta = modelFieldMap.get(field.field);
      return enrichDetailField(field, meta);
    },
    [modelFieldMap],
  );

  // Collect all dictCodes from schema fields (including inside tabs → blocks → form-section → fields)
  // Also check modelFieldMap for dictCodes not declared in the page schema
  const allDictCodes = useMemo(() => {
    return collectDetailDictCodes(schema as any, modelFieldMap);
  }, [schema, modelFieldMap]);

  const { getDictItems } = useDictCache({
    dictCodes: allDictCodes,
    token: token || undefined,
  });

  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );
  return { enrichField, getDictItems, showToast };
}
