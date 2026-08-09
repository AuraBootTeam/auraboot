/**
 * Page kinds accepted by the published Web runtime.
 *
 * Keep route validation, schema validation and render-profile registration on
 * this single contract. Authoring must never be able to publish a page kind
 * that the runtime rejects at navigation time.
 */
export const RUNTIME_PAGE_KINDS = [
  'page',
  'list',
  'form',
  'detail',
  'kanban',
  'dashboard',
  'composite',
  'page_layout',
] as const;

export type RuntimePageKind = (typeof RUNTIME_PAGE_KINDS)[number];

export function isRuntimePageKind(value: unknown): value is RuntimePageKind {
  return typeof value === 'string' && (RUNTIME_PAGE_KINDS as readonly string[]).includes(value);
}
