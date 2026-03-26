/**
 * Derive a stable data-testid from DSL schema context.
 *
 * Format: ab:{surface}:{entity}:{element}
 * With qualifier: ab:{surface}:{entity}:{element}:{qualifier}
 *
 * Uses colon separator per the unified TestId naming convention
 * (see docs/e2e/06-Selector-TestId-迁移计划.md).
 *
 * Examples:
 *   deriveTestId('list', 'crm_account', 'container')
 *     -> "ab:list:crm_account:container"
 *
 *   deriveTestId('form', 'crm_account', 'field', 'company_name')
 *     -> "ab:form:crm_account:field:company_name"
 *
 *   deriveTestId('detail', 'crm_account', 'tab', 'overview')
 *     -> "ab:detail:crm_account:tab:overview"
 *
 *   deriveTestId('dashboard', 'crm_dashboard', 'block', 'chart_pipeline')
 *     -> "ab:dashboard:crm_dashboard:block:chart_pipeline"
 */
export function deriveTestId(
  surface: string,
  entity: string,
  element: string,
  qualifier?: string,
): string {
  const base = `ab:${surface}:${entity}:${element}`;
  if (qualifier) {
    return `${base}:${qualifier}`.toLowerCase();
  }
  return base.toLowerCase();
}
