import { redirect } from 'react-router';

/** Legacy bookmark: Webhook management is owned by the existing administration DSL. */
export function loader() { return redirect('/p/webhook'); }
export default function LegacyWebhooksRedirect() { return null; }
