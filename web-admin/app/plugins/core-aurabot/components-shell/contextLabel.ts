import type { PageContext } from '../hooks/usePageContext';

/** Keep routing identities in context while presenting a translated business name. */
export function contextLabel(
  context: PageContext,
  translate: (key: string) => string,
  fallback: string,
): string {
  const key = `model.${context.modelCode}._meta.label`;
  const translated = translate(key);
  const modelLabel = translated && translated !== key ? translated : fallback;
  const parts = context.breadcrumb
    ?.filter(Boolean)
    .map((part) => (part === context.modelCode || part === context.pageKey ? modelLabel : part));
  return parts?.length ? parts.join(' → ') : modelLabel;
}
