import type { components } from '~/generated/internal-api/schema';
import type {
  DictDTO,
  DictDataResult,
  DictItemData,
  DictPageResult,
  DictTreeNode,
  Present,
} from '~/types/dict';
import { STATUS_BADGE_CONFIG } from '~/types/status';
import type { VersionStatus } from '~/types/status';

export function requireFields<T extends object, K extends keyof T>(
  value: T,
  keys: readonly K[],
): Present<T, K> {
  for (const key of keys) {
    if (value[key] === null || value[key] === undefined)
      throw new Error(`Incomplete dictionary response: ${String(key)}`);
  }
  return value as Present<T, K>;
}

export function dictionaryView(value: components['schemas']['DictDTO']): DictDTO {
  const view = requireFields(value, ['pid', 'code', 'name', 'dictType', 'status', 'updatedAt']);
  const isDisplayStatus = (status: string): status is VersionStatus =>
    Object.hasOwn(STATUS_BADGE_CONFIG, status);
  if (!isDisplayStatus(view.status)) throw new Error('Unsupported dictionary display status');
  return { ...view, status: view.status };
}

export function dictionaryPage(
  value: components['schemas']['PageResultDictDTO'],
): DictPageResult<DictDTO> {
  const page = requireFields(value, ['records', 'total', 'size', 'current', 'pages']);
  return { ...page, records: page.records.map(dictionaryView) };
}

function dictionaryItem(value: components['schemas']['DictItemData']): DictItemData {
  const item = requireFields(value, ['value', 'label']);
  const { extension, ...fields } = item;
  if (extension != null && (typeof extension !== 'object' || Array.isArray(extension))) {
    throw new Error('Dictionary editor item extension must be a JSON object');
  }
  // HTTP decoding produced JSON values; only narrow the editor container here.
  return {
    ...fields,
    order: fields.sortOrder,
    disabled: fields.enabled === undefined ? undefined : !fields.enabled,
    extension:
      extension == null ? undefined : (extension as NonNullable<DictItemData['extension']>),
  };
}

export function dictionaryData(value: components['schemas']['DictDataResult']): DictDataResult {
  if (value.success === false)
    throw new Error(value.errorMessage || 'Dictionary data loading failed');
  const data = requireFields(value, ['items']);
  return { ...data, items: data.items.map(dictionaryItem) };
}

export function dictionaryTree(value: components['schemas']['DictTreeNode']): DictTreeNode {
  const node = requireFields(value, ['value', 'label']);
  return { ...node, children: node.children?.map(dictionaryTree) };
}

export function dictionaryItems(values: components['schemas']['DictItemData'][]): DictItemData[] {
  return values.map(dictionaryItem);
}
