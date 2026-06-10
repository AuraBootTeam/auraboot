import { redirect } from 'react-router'
import type { LoaderFunctionArgs } from 'react-router'

/**
 * Legacy DecisionOps console route.
 *
 * DecisionOps production workflows are DSL-first under /p/decisionops_* and
 * reuse platform pages for connectors/webhooks/permissions. Keep this route
 * only as a backwards-compatible redirect for old links and bookmarks.
 */
export const loader = async (_args: LoaderFunctionArgs) => {
  return redirect('/p/decisionops_rollouts')
}

export default function DecisionOpsConsolePage() {
  return null
}
