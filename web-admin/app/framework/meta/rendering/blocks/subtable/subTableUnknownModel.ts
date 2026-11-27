/**
 * Whether a failed list request failed because the sub-table's model is not part of this
 * installation.
 *
 * A plugin may declare a sub-table for a model that an optional companion plugin brings. The
 * CRM account detail lists the requirement sets of the PCBA add-on; the quote summary listed
 * the converted contracts of the sales plugin. On an installation without that plugin the
 * request answers, on HTTP 400:
 *
 *   {"code":"40000","message":"Business error","data":null,"context":"Model not found: <code>"}
 *
 * and the table showed that message in red under a heading, although there was nothing the
 * user could do about it. A model that is not installed has no records: it is an empty state.
 */
export function isUnknownModelResult(result: unknown): boolean {
  if (!result || typeof result !== 'object') return false;
  const context = (result as { context?: unknown }).context;
  return typeof context === 'string' && /^Model not found\b/i.test(context.trim());
}
