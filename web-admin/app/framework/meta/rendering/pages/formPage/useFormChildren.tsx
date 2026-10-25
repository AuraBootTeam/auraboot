import { useState, useMemo, useEffect } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { SubTableColumn } from '~/framework/meta/components/types';
import { resolveExtensionDisplayName } from '~/framework/meta/utils/i18nResolver';
import {
  mergeRefTarget,
  resolveComponentByFieldMeta,
  mapDataTypeToColumnType,
} from './formFieldMetadata';
import { shouldLoadMainRecordForForm } from './formActions';
import type { useFormPageState } from './useFormPageState';
import type { useFormPageRuntime } from './useFormPageRuntime';
import type { useFormRecord } from './useFormRecord';

type UseFormChildrenArgs = Pick<
  ReturnType<typeof useFormPageState>,
  'isEditMode' | 'setFormData' | 'recordPid' | 'schema' | 'initialValues' | 'token'
> &
  Pick<
    ReturnType<typeof useFormPageRuntime>,
    'schemaDefaultValues' | 'urlDefaultValues' | 'locale'
  > &
  Pick<ReturnType<typeof useFormRecord>, 'loadMainRecord'>;

export function useFormChildren({
  isEditMode,
  schemaDefaultValues,
  urlDefaultValues,
  setFormData,
  recordPid,
  schema,
  loadMainRecord,
  initialValues,
  token,
  locale,
}: UseFormChildrenArgs) {
  // Create mode: apply field-config defaults then URL default values
  // (dv.fieldCode=value) to form. Precedence: user input > URL > field default.
  useEffect(() => {
    if (isEditMode) return;
    const base = { ...schemaDefaultValues, ...urlDefaultValues };
    if (Object.keys(base).length === 0) return;
    setFormData((prev) => ({ ...base, ...prev }));
  }, [isEditMode, schemaDefaultValues, urlDefaultValues, setFormData]);

  // Edit mode: fetch existing record data to populate form
  useEffect(() => {
    if (!shouldLoadMainRecordForForm(recordPid, schema)) return;
    void loadMainRecord({ preserveDirty: true });
  }, [recordPid, schema, loadMainRecord]);

  // L1 SDK: merge external initialValues into form state (overlay on top of loaded data)
  useEffect(() => {
    if (!initialValues || Object.keys(initialValues).length === 0) return;
    setFormData((prev) => ({ ...prev, ...initialValues }));
  }, [initialValues, setFormData]);

  // Sub-table row data: { [blockKey]: Record<string,any>[] }
  const [subTableData, setSubTableData] = useState<Record<string, Record<string, any>[]>>({});
  // Track whether edit-mode data (child records) has been loaded
  const [editDataLoaded, setEditDataLoaded] = useState(!isEditMode);

  // Compute subTableBlocks early so we can use it in rendering and column derivation
  const subTableBlocks = useMemo(() => {
    if (!schema?.blocks) return [];
    return schema.blocks.filter((b: any) => b.blockType === 'sub-table');
  }, [schema]);

  // Build SubTableColumn[] directly from DSL columns (instant, no API needed)
  // Enhanced with field metadata when available from parent model fields fetch
  const subTableColumnsMap = useMemo(() => {
    const result: Record<string, SubTableColumn[]> = {};
    for (const block of subTableBlocks) {
      const childModel = block.subTable?.childModel || (block as any).childModel;
      if (!childModel) continue;
      const dslColumns: any[] = block.subTable?.columns || (block as any).columns || [];
      result[childModel] = dslColumns.map((col: any) => ({
        field: col.field,
        label: typeof col.label === 'string' ? col.label : col.field,
        type: 'text' as const,
        width: typeof col.width === 'number' ? col.width : undefined,
        required: false,
        editable: col.readOnly ? false : true,
      }));
    }
    return result;
  }, [subTableBlocks]);

  // Async enhancement: fetch field metadata to get proper types and labels
  const [enhancedColumns, setEnhancedColumns] = useState<Record<string, SubTableColumn[]>>({});
  useEffect(() => {
    if (subTableBlocks.length === 0) return;

    for (const block of subTableBlocks) {
      const childModel = block.subTable?.childModel || (block as any).childModel;
      if (!childModel || enhancedColumns[childModel]) continue;

      const normalizedModel = childModel;

      // Use /api/dynamic/{modelCode}/field-meta which requires model-level read permission
      fetchResult<
        Array<{
          code: string;
          dataType: string;
          dictCode?: string;
          refTarget?: Record<string, any> | null;
          referenceModelCode?: string;
          extension?: any;
          uiSchema?: { label?: string };
        }>
      >(`/api/dynamic/${normalizedModel}/field-meta`, {
        method: 'get',
        token: token || undefined,
      })
        .then((fieldsResp) => {
          if (!fieldsResp || !ResultHelper.isSuccess(fieldsResp) || !Array.isArray(fieldsResp.data))
            return;

          const fieldMap: Record<
            string,
            {
              dataType: string;
              displayName: string;
              dictCode?: string;
              component?: string;
              refTarget?: Record<string, any>;
              referenceModelCode?: string;
            }
          > = {};
          for (const f of fieldsResp.data) {
            fieldMap[f.code] = {
              dataType: f.dataType,
              displayName: resolveExtensionDisplayName(
                f.extension,
                f.code,
                locale,
                f.uiSchema?.label,
              ),
              dictCode: f.dictCode,
              refTarget: mergeRefTarget(f.refTarget, f.extension?.refTarget),
              referenceModelCode:
                f.referenceModelCode ||
                (f.extension?.extension as any)?.referenceModelCode ||
                (f.extension?.extension as any)?.refModelCode,
              component: resolveComponentByFieldMeta(f.dataType, f.extension),
            };
          }

          const dslColumns: any[] = block.subTable?.columns || (block as any).columns || [];
          const mapped: SubTableColumn[] = dslColumns.map((col: any) => {
            const meta = fieldMap[col.field];
            return {
              field: col.field,
              label: meta?.displayName || (typeof col.label === 'string' ? col.label : col.field),
              type: meta ? mapDataTypeToColumnType(meta.dataType) : 'text',
              width: typeof col.width === 'number' ? col.width : undefined,
              required: false,
              editable: col.readOnly ? false : true,
            };
          });

          setEnhancedColumns((prev) => ({ ...prev, [childModel]: mapped }));
        })
        .catch(() => {});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subTableBlocks.length, token, locale]);

  // Edit mode: fetch existing child records for sub-table blocks
  useEffect(() => {
    if (!recordPid) return; // Create mode -- no children to load
    if (!schema) return; // Wait for schema to load first

    if (subTableBlocks.length === 0) {
      setEditDataLoaded(true);
      return;
    }

    const loadChildren = async () => {
      const newData: Record<string, Record<string, any>[]> = {};

      for (const block of subTableBlocks) {
        const childModel = block.subTable?.childModel || (block as any).childModel;
        const parentField = block.subTable?.parentField || (block as any).parentField;
        if (!childModel || !parentField) continue;

        const blockKey = block.id || `sub-table-${subTableBlocks.indexOf(block)}`;

        try {
          const resp = await fetchResult<any>(`/api/dynamic/${childModel}/list`, {
            method: 'get',
            params: {
              pageNum: 1,
              pageSize: 200,
              filters: JSON.stringify([
                {
                  fieldName: parentField,
                  operator: 'EQ',
                  value: recordPid,
                },
              ]),
            },
            token: token || undefined,
          });

          const records = resp.data?.records ?? [];
          if (ResultHelper.isSuccess(resp) && Array.isArray(records) && records.length > 0) {
            newData[blockKey] = records.map((row: any, i: number) => ({
              ...row,
              _key: row.pid || `loaded-${i}`,
            }));
          }
        } catch {
          // Silently skip failed child loads
        }
      }

      setSubTableData(newData);
      setEditDataLoaded(true);
    };

    loadChildren();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordPid, schema, token]);
  return {
    subTableData,
    setSubTableData,
    editDataLoaded,
    subTableBlocks,
    subTableColumnsMap,
    enhancedColumns,
  };
}
