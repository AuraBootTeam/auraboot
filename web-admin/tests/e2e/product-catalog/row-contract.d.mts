export type DynamicListRecord = Record<string, unknown> & { pid?: string };

export function dynamicListRecords(body: unknown, responseLabel: string): DynamicListRecord[];

export function assertUniqueListRecordPid(
  records: DynamicListRecord[],
  recordPid: string,
  recordLabel: string,
): void;
