// OSS slot stub — real implementation lives in auraboot-enterprise ent-identity plugin.
// Enterprise build replaces this file via web-admin-ext overlay.

export interface DictTreeNode {
  value: string;
  label: string;
  code?: string;
  children?: DictTreeNode[];
}

/**
 * Returns a dictionary tree for the given code. In OSS this is always empty;
 * callers should fall back to their `externalOptions` when `skip` is true.
 *
 * @param _dictCode dictionary code (e.g. 'user_status')
 * @param _skip if true, caller has provided options and wants the hook to skip fetching
 */
export function useDictTree(_dictCode: string | undefined, _skip?: boolean): DictTreeNode[] {
  return [];
}
