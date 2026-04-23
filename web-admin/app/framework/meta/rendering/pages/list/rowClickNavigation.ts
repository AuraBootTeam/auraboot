export type ListRowClickMode = 'detail' | 'drawer' | 'none';

interface ResolveListRowClickModeInput {
  schemaDetailNavigation?: unknown;
  tableOnRowClick?: unknown;
  tableRowClickAction?: unknown;
}

function normalizeMode(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

export function resolveListRowClickMode(
  input: ResolveListRowClickModeInput,
): ListRowClickMode {
  const configured =
    normalizeMode(input.schemaDetailNavigation) ??
    normalizeMode(input.tableOnRowClick) ??
    normalizeMode(input.tableRowClickAction);

  if (configured === 'none') return 'none';
  if (configured === 'drawer') return 'drawer';
  if (configured === 'navigate' || configured === 'page' || configured === 'detail') {
    return 'detail';
  }

  // Platform default: navigate to the detail page. Drawer requires explicit opt-in.
  return 'detail';
}
