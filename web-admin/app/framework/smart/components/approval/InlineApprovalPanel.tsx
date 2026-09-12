import { LazyContributedComponent } from '~/framework/extensions/contributed-components';

export interface InlineApprovalPanelProps {
  recordPid: string;
  className?: string;
}

/** Optional workflow surface supplied by an installed workflow product. */
export function InlineApprovalPanel(props: InlineApprovalPanelProps) {
  return <LazyContributedComponent contributionId="workflow-inline-approval" {...props} />;
}
