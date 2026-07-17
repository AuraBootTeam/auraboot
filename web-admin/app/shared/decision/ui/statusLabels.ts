const DECISION_STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  FAILED_RETRYING: '失败重试中',
  RETRY_PENDING: '等待重试',
  DEAD_LETTER: '进入死信',
  NO_HANDLER: '处理器缺失',
  NOT_EXECUTED: '未执行',
  NOT_RUN: '未运行',
  MATCHED: '命中',
  NOT_MATCHED: '未命中',
  ERROR: '错误',
  SKIPPED: '跳过',
  UNKNOWN: '未知',
};

export function decisionStatusLabel(status?: string | null): string {
  if (!status) return '-';
  const normalized = status.trim().toUpperCase();
  return DECISION_STATUS_LABELS[normalized] ?? status;
}
