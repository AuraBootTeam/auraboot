/** Keep infrastructure errors out of the business-facing chart surface. */
export function chartDataErrorLabel(error: Error): { zh: string; en: string } {
  return /access forbidden|access denied|forbidden|permission denied|403/i.test(error.message)
    ? {
        zh: '无权读取数据，请联系管理员确认源数据权限。',
        en: 'Data access denied. Ask an administrator to check source data permissions.',
      }
    : {
        zh: '数据读取失败，请重试；若持续失败，请联系管理员。',
        en: 'Unable to load data. Retry, or contact an administrator if the problem persists.',
      };
}
