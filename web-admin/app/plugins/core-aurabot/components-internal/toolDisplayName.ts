/**
 * Turns an LLM-safe tool identifier into a user-facing business label.
 *
 * Runtime tool names may use a namespace separator (`crm:create_account`) or
 * transport-safe single/double underscores (`cmd_crm_create_account`,
 * `cmd__crm__create_account`). Keep this conversion shared by confirmation and
 * result cards so the raw command code never reappears after approval.
 */
export function formatToolDisplayName(name: string, isZh: boolean): string {
  const localizeWords = (value: string) => {
    const words = value.split(/[_\s]+/).filter(Boolean);
    if (!isZh) {
      return words.map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
    }
    const labels: Record<string, string> = {
      create: '新建',
      update: '更新',
      delete: '删除',
      list: '查询',
      get: '查看',
      account: '客户',
      customer: '客户',
      lead: '线索',
      contact: '联系人',
      opportunity: '商机',
      record: '记录',
    };
    return words.map((word) => labels[word.toLowerCase()] ?? word).join('');
  };

  if (name.includes(':')) {
    const normalized = name.replace(/^(cmd|nq|builtin):/, '');
    const [namespace, ...actionParts] = normalized.split(':');
    if (actionParts.length > 0) {
      return `${namespace.toUpperCase()} › ${localizeWords(actionParts.join('_'))}`;
    }
    return localizeWords(normalized);
  }

  const withoutPrefix = name
    .replace(/^(cmd__|nq__|builtin__)/, '')
    .replace(/^(cmd_|nq_|builtin_)/, '');
  if (withoutPrefix.includes('__')) {
    const [namespace, ...actionParts] = withoutPrefix.split('__');
    return `${namespace.toUpperCase()} › ${localizeWords(actionParts.join('_'))}`;
  }

  const separator = withoutPrefix.indexOf('_');
  if (separator <= 0 || separator >= withoutPrefix.length - 1) {
    return localizeWords(withoutPrefix);
  }
  return `${withoutPrefix.slice(0, separator).toUpperCase()} › ${localizeWords(withoutPrefix.slice(separator + 1))}`;
}
