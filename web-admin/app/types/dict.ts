import type { components } from '~/generated/internal-api/schema';
import type { InternalRequestInput, JsonValue } from '~/shared/services/http-client/internal-api';
import type { VersionStatus } from './status';

export type Present<T, K extends keyof T> = T & { [P in K]-?: NonNullable<T[P]> };

/** UI projection: these identity/display fields are checked at the service boundary. */
export type DictDTO = Omit<
  Present<
    components['schemas']['DictDTO'],
    'pid' | 'code' | 'name' | 'dictType' | 'status' | 'updatedAt'
  >,
  'status'
> & { status: VersionStatus };
export type DictCreateRequest = InternalRequestInput<'post', '/api/meta/dict'>['body'];
export type DictUpdateRequest = InternalRequestInput<'put', '/api/meta/dict/{pid}'>['body'];
export type DictQueryRequest = NonNullable<InternalRequestInput<'get', '/api/meta/dict'>['query']>;
export type DictPageResult<T> = Present<
  Omit<components['schemas']['PageResultDictDTO'], 'records'>,
  'total' | 'size' | 'current' | 'pages'
> & { records: T[] };

/** Editor state adds ordering/disabled/tree properties to the generated data item. */
export type DictItemData = Present<
  Omit<components['schemas']['DictItemData'], 'extension'>,
  'value' | 'label'
> & {
  order?: number;
  disabled?: boolean;
  extension?: { [key: string]: JsonValue };
  children?: DictItemData[];
};
export type DictDataResult = Omit<components['schemas']['DictDataResult'], 'items'> & {
  items: DictItemData[];
};
export type DictStatistics = components['schemas']['DictStatistics'];
export type DictValidationResult = components['schemas']['DictValidationResult'];
export type DictTreeNode = Present<
  Omit<components['schemas']['DictTreeNode'], 'children'>,
  'value' | 'label'
> & {
  children?: DictTreeNode[];
};
