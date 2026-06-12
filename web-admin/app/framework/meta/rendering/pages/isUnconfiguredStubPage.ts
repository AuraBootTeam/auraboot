import type { UnifiedSchema } from '~/framework/meta/schemas/types';

/**
 * Detect an unconfigured platform stub page.
 *
 * The platform's {@code MetaModelServiceImpl.autoCreateDefaultPages} emits a
 * placeholder page on model publish when no user-defined page exists. It is
 * tagged {@code extension.auto_created=true} and carries only default blocks
 * (toolbar/filters/table) with no column config, so it renders a misleading
 * empty shell (raw-code title + zero-column table + "no data") with no error.
 *
 * This detector drives Item-3's fail-fast path: a stub page must surface an
 * explicit "page not configured" error instead of the silent empty shell. The
 * marker also catches the rename-missed-derived-pageKey case, where a renamed
 * model's {@code <model>_list} stub is auto-created and the old menu lands on it.
 *
 * Mirrors the Java guard {@code isAutoCreatedStubPage} which accepts the boolean
 * and coerced string form of the JSONB flag.
 */
export function isUnconfiguredStubPage(
  schema: UnifiedSchema | null | undefined,
): boolean {
  const flag = schema?.extension?.auto_created;
  return flag === true || flag === 'true';
}
