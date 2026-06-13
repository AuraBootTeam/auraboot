import type { LoaderFunctionArgs } from 'react-router';

export async function loader(_args: LoaderFunctionArgs) {
  return new Response(null, {
    status: 302,
    headers: { Location: '/p/decisionops_rollouts' },
  });
}

export default function DecisionOpsConsolePage() {
  return null;
}
