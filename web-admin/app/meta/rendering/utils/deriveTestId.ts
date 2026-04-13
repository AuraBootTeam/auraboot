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

// ---------------------------------------------------------------------------
// Convenience helpers for common DSL page elements
// ---------------------------------------------------------------------------

/** Normalize modelCode: replace hyphens with underscores */
function normalize(modelCode: string): string {
  return modelCode;
}

export function listTestId(modelCode: string, element?: string): string {
  return deriveTestId('list', normalize(modelCode), element || 'container');
}

export function formTestId(modelCode: string, element?: string): string {
  return deriveTestId('form', normalize(modelCode), element || 'container');
}

export function fieldTestId(surface: string, modelCode: string, fieldCode: string): string {
  return deriveTestId(surface, normalize(modelCode), 'field', fieldCode);
}

export function buttonTestId(surface: string, modelCode: string, commandCode: string): string {
  return deriveTestId(surface, normalize(modelCode), 'btn', commandCode);
}

export function tabTestId(modelCode: string, tabCode: string): string {
  return deriveTestId('detail', normalize(modelCode), 'tab', tabCode);
}

export function detailTestId(modelCode: string, element?: string): string {
  return deriveTestId('detail', normalize(modelCode), element || 'container');
}

export function rowTestId(modelCode: string, recordId: string): string {
  return deriveTestId('list', normalize(modelCode), 'row', recordId);
}

export function colTestId(modelCode: string, fieldCode: string): string {
  return deriveTestId('list', normalize(modelCode), 'col', fieldCode);
}

export function actionTestId(surface: string, modelCode: string, actionCode: string): string {
  return deriveTestId(surface, normalize(modelCode), 'action', actionCode);
}
