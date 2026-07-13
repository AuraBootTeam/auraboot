/**
 * Web-form embed snippet builder.
 *
 * Extracted from the web-form editor route so the generated snippet is unit-testable:
 * the previous inline snippet shipped a 404 for every customer who pasted it, and the
 * E2E only asserted that a toast appeared, so nothing caught it.
 *
 * The contract is defined by the SDK template itself —
 * `platform/src/main/resources/static/crm/web-form-sdk.js`:
 *
 *   <div id="auraboot-form"></div>
 *   <script src="https://your-host/api/crm/forms/__FORM_PID__/sdk.js"></script>
 *
 * Three properties the snippet MUST satisfy (each was previously violated):
 *   1. Path      — the SDK is served by `GET /api/crm/forms/{formPid}/sdk.js`
 *                  (InboundFormController). There is no `/sdk/web-form.js` route.
 *   2. Identity  — the form pid is baked into the served script server-side by
 *                  substituting `__FORM_PID__`. The SDK reads NO `data-form-id`
 *                  attribute, so emitting one is misleading noise.
 *   3. Container — the SDK resolves `#auraboot-form` (or `[data-auraboot-form]`) and
 *                  bails out with a console warning if neither exists, so the snippet
 *                  must ship the container element.
 */

/** The container element the SDK looks up. Must precede the script tag. */
export const WEB_FORM_CONTAINER_ID = 'auraboot-form';

/** Path of the per-form SDK endpoint (InboundFormController). */
export function webFormSdkPath(formPid: string): string {
  return `/api/crm/forms/${formPid}/sdk.js`;
}

/**
 * Build the embeddable snippet for a web form.
 *
 * @param origin   absolute origin the customer's page will call, e.g. `https://app.example.com`
 * @param formPid  public id of the form
 */
export function buildWebFormEmbedSnippet(origin: string, formPid: string): string {
  const base = (origin || '').replace(/\/+$/, '');
  return [
    `<div id="${WEB_FORM_CONTAINER_ID}"></div>`,
    `<script src="${base}${webFormSdkPath(formPid)}"></script>`,
  ].join('\n');
}
