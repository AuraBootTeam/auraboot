/**
 * Scroll to + focus a form field by its code, used on submit-validation failure
 * so the page jumps to the first invalid field (standard §4 mixed-timing
 * validation). Returns false when no matching field wrapper is in the DOM.
 *
 * Field wrappers carry `data-testid="form-field-<fieldCode>"` (see FormPageContent).
 */
const FOCUSABLE = 'input, select, textarea, [tabindex], [contenteditable="true"]';

export function scrollToFormField(fieldCode: string, doc: Document = document): boolean {
  if (!fieldCode || typeof doc === 'undefined') return false;
  const el = doc.querySelector<HTMLElement>(`[data-testid="form-field-${fieldCode}"]`);
  if (!el) return false;
  el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  el.querySelector<HTMLElement>(FOCUSABLE)?.focus({ preventScroll: true });
  return true;
}
