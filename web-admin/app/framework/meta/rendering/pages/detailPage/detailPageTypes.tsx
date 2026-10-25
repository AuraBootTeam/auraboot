export interface RecordData {
  [key: string]: any;
  id?: string;
  pid?: string;
}

export interface DetailListResult {
  records?: RecordData[];
}

export interface DetailRecordRefreshSnapshot {
  rawData: RecordData;
  recordData: RecordData;
}
