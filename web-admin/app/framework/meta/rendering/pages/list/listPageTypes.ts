// Dict data item type
export type DictItem = Pick<import('~/types/dict').DictItemData, 'value' | 'label' | 'extension'>;

export interface DynamicEntity {
  [key: string]: any;
  id?: string;
  pid?: string;
}
