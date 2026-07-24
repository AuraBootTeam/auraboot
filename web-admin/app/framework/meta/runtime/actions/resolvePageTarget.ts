/**
 * Shared DSL page-target resolution.
 *
 * A DSL author writes one kind of navigation target — a pageKey
 * (`crm_account_common_list`), a cross-designer reference (`dashboard:sales`),
 * or an absolute path (`/p/c/quote_console`) — and every consumer must turn it
 * into the same route. Keeping the rules in one place is what stops the
 * `navigate` action and the form back link from drifting apart.
 */

/**
 * Resolve a DSL navigation target to a route path.
 *
 * @param target pageKey, cross-designer reference, or absolute path
 * @param record optional record used to interpolate `{placeholder}` segments
 * @param recordPid optional public record id backing `{pid}` / `{id}`
 * @returns the route path, or '' when the target is empty
 */
export function resolvePageTargetPath(
  target: string | undefined,
  record?: Record<string, any>,
  recordPid?: string | null,
): string {
  if (!target) {
    return '';
  }

  // A target that begins with a {placeholder} carries its whole route in a record
  // field — e.g. a card-grid row with to: "{target}" where record.target =
  // "/ai/providers". Resolve the placeholder(s) from the record first; if the result
  // is a concrete absolute path, use it. This lets one card-grid cardAction navigate
  // each card to its own distinct path (the action is shared across cards, the target
  // is per-row). Purely additive: no existing target begins with "{" — they start with
  // "/", a known "prefix:" scheme, or a "modelCode_pageType" pageKey.
  if (record && target.startsWith('{')) {
    const resolved = target.replace(/\{(\w+)\}/g, (_, key) => {
      if (key in record) return String(record[key] ?? '');
      if (key === 'pid' || key === 'id') return String(recordPid ?? '');
      return '';
    });
    if (resolved.startsWith('/')) {
      return resolved;
    }
  }

  // Absolute path with template variables — OCP compliant
  // DSL can write navigateTo: "/dashboard-designer/{pid}" or "/bpmn-designer?pid={pid}"
  if (target.startsWith('/')) {
    return target.replace(/\{(\w+)\}/g, (_, key) => {
      if (record && key in record) return encodeURIComponent(String(record[key] ?? ''));
      if (key === 'pid' || key === 'id') return encodeURIComponent(String(recordPid ?? ''));
      return '';
    });
  }

  // Cross-designer navigation: dashboard:{code}
  if (target.startsWith('dashboard:')) {
    const code = target.substring('dashboard:'.length);
    return `/dashboards/view/${code}`;
  }

  // Cross-designer navigation: bpmn-status:{processKey}
  if (target.startsWith('bpmn-status:')) {
    const processKey = target.substring('bpmn-status:'.length);
    const params = new URLSearchParams({ processKey });
    if (recordPid) {
      params.set('businessKey', String(recordPid));
    }
    return `/bpm/process-status?${params.toString()}`;
  }

  // Cross-designer navigation: automation:{pid}
  if (target.startsWith('automation:')) {
    const pid = target.substring('automation:'.length);
    return `/automation/${pid}`;
  }

  // Cross-designer navigation: bpmn-designer:{pid}
  if (target.startsWith('bpmn-designer:')) {
    const pid = target.substring('bpmn-designer:'.length);
    return pid ? `/bpmn-designer?pid=${pid}` : '/bpmn-designer';
  }

  // Legacy format: "{modelCode}_{pageType}"
  // Parse pageKey: last segment is the page type (list/form/detail)
  const lastUnderscoreIdx = target.lastIndexOf('_');
  const suffix = target.substring(lastUnderscoreIdx + 1);
  const modelCodePart = target.substring(0, lastUnderscoreIdx);

  // Keep model code as-is (underscores) to match page schema keys

  switch (suffix) {
    case 'form':
      // Route pattern: /p/:pageKey/edit/:recordPid (see routes.ts)
      return recordPid ? `/p/${modelCodePart}/edit/${recordPid}` : `/p/${modelCodePart}/new`;
    case 'detail':
    case 'view':
      return `/p/${modelCodePart}/view/${recordPid}`;
    case 'list':
      return `/p/${modelCodePart}`;
    default:
      // Fallback: treat as list page
      return `/p/${modelCodePart}`;
  }
}
