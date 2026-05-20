import { fetchResult } from '~/shared/services/http-client';
import type { DslBlockV3, ModelFieldDefinition, ModelFieldsByModel, PageSchemaV3 } from '../types';

interface ModelByCodeResponse {
  pid?: string;
}

interface BackendModelFieldRecord {
  code?: string;
  name?: string;
  displayName?: string;
  dataType?: string;
  fieldType?: string;
  type?: string;
  dictCode?: string;
  required?: boolean;
  extension?: {
    displayName?: string;
  };
  uiSchema?: {
    component?: string;
    widget?: string;
    widgetType?: string;
    type?: string;
  };
}

interface BackendResolvedFieldRecord {
  code?: string;
  aliasCode?: string;
  displayName?: string;
  dataType?: string;
  returnType?: string;
  dictCode?: string;
  required?: boolean;
  uiHint?: {
    component?: string;
    widget?: string;
    widgetType?: string;
    type?: string;
  };
}

export type ModelFieldFetcher = <T>(url: string) => Promise<{ data?: T | null } | undefined>;

export function collectModelCodesFromDocument(document: PageSchemaV3): string[] {
  const modelCodes = new Set<string>();
  addModelCode(modelCodes, document.modelCode);
  collectModelCodesFromBlocks(document.blocks, modelCodes);
  return Array.from(modelCodes);
}

export async function loadModelFieldsForDocument(
  document: PageSchemaV3,
): Promise<ModelFieldsByModel> {
  return loadModelFieldsByModelCodes(collectModelCodesFromDocument(document));
}

export async function loadModelFieldsByModelCodes(
  modelCodes: string[],
  fetcher: ModelFieldFetcher = fetchResult,
): Promise<ModelFieldsByModel> {
  const uniqueModelCodes = Array.from(new Set(modelCodes.filter((code) => code.trim())));
  const entries = await Promise.all(
    uniqueModelCodes.map(async (modelCode) => [
      modelCode,
      await loadFieldsForModelCode(modelCode, fetcher),
    ]),
  );

  return Object.fromEntries(entries) as ModelFieldsByModel;
}

async function loadFieldsForModelCode(
  modelCode: string,
  fetcher: ModelFieldFetcher,
): Promise<ModelFieldDefinition[]> {
  const physicalFields = await loadPhysicalFieldsForModelCode(modelCode, fetcher);
  if (physicalFields.length > 0) {
    return physicalFields;
  }
  const viewModelFields = await loadResolvedFieldsForViewModelCode(modelCode, fetcher);
  if (viewModelFields.length > 0) {
    return viewModelFields;
  }
  return loadQueryBuilderFieldsForModelCode(modelCode, fetcher);
}

async function loadResolvedFieldsForViewModelCode(
  modelCode: string,
  fetcher: ModelFieldFetcher,
): Promise<ModelFieldDefinition[]> {
  try {
    const fieldsResult = await fetcher<BackendResolvedFieldRecord[]>(
      `/api/meta/view-models/${encodeURIComponent(modelCode)}/resolved-fields`,
    );
    return (fieldsResult?.data ?? [])
      .filter((field) => Boolean((field.aliasCode || field.code)?.trim()))
      .map((field) => mapResolvedField(modelCode, field));
  } catch {
    return [];
  }
}

async function loadPhysicalFieldsForModelCode(
  modelCode: string,
  fetcher: ModelFieldFetcher,
): Promise<ModelFieldDefinition[]> {
  try {
    const modelResult = await fetcher<ModelByCodeResponse>(
      `/api/meta/models/code/${encodeURIComponent(modelCode)}`,
    );
    const modelPid = modelResult?.data?.pid;
    if (!modelPid) return [];

    const fieldsResult = await fetcher<BackendModelFieldRecord[]>(
      `/api/meta/models/${encodeURIComponent(modelPid)}/fields`,
    );
    return (fieldsResult?.data ?? [])
      .filter((field) => Boolean(field.code?.trim()))
      .map((field) => mapBackendField(modelCode, field));
  } catch {
    return [];
  }
}

async function loadQueryBuilderFieldsForModelCode(
  modelCode: string,
  fetcher: ModelFieldFetcher,
): Promise<ModelFieldDefinition[]> {
  try {
    const fieldsResult = await fetcher<BackendModelFieldRecord[]>(
      `/api/query-builder/models/${encodeURIComponent(modelCode)}/fields`,
    );
    return (fieldsResult?.data ?? [])
      .filter((field) => Boolean(field.code?.trim()))
      .map((field) => mapBackendField(modelCode, field));
  } catch {
    return [];
  }
}

function collectModelCodesFromBlocks(blocks: DslBlockV3[], modelCodes: Set<string>) {
  blocks.forEach((block) => {
    addModelCode(modelCodes, block.dataSource?.model);
    if (block.blocks?.length) {
      collectModelCodesFromBlocks(block.blocks, modelCodes);
    }
  });
}

function addModelCode(modelCodes: Set<string>, value: unknown) {
  if (typeof value !== 'string') return;
  const modelCode = value.trim();
  if (modelCode) modelCodes.add(modelCode);
}

function mapBackendField(
  modelCode: string,
  field: BackendModelFieldRecord,
): ModelFieldDefinition {
  const code = field.code?.trim() ?? '';
  return {
    modelCode,
    code,
    label: field.displayName || field.extension?.displayName || field.name || code,
    type: field.dataType || field.fieldType || field.type,
    component: field.uiSchema?.component || field.uiSchema?.widget || field.uiSchema?.widgetType,
    dictCode: field.dictCode,
    required: Boolean(field.required),
  };
}

function mapResolvedField(
  modelCode: string,
  field: BackendResolvedFieldRecord,
): ModelFieldDefinition {
  const code = (field.aliasCode || field.code || '').trim();
  return {
    modelCode,
    code,
    label: field.displayName || code,
    type: field.dataType || field.returnType,
    component:
      field.uiHint?.component ||
      field.uiHint?.widget ||
      field.uiHint?.widgetType ||
      field.uiHint?.type,
    dictCode: field.dictCode,
    required: Boolean(field.required),
  };
}
