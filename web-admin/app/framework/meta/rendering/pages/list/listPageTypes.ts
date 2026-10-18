// Dict data item type
export interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
}

export interface DynamicEntity {
  [key: string]: any;
  id?: string;
  pid?: string;
}
