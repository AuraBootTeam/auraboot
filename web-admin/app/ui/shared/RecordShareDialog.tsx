// OSS slot stub — record sharing (share by user/org/link) lives in ent-org plugin.
// Enterprise overlay replaces this file.

import type React from 'react';
import { useContributionRegistry } from '~/framework/extensions/use-contribution';

export interface RecordShareDialogProps {
  open: boolean;
  onClose: () => void;
  resourceCode?: string;
  recordPid?: string;
}

export function RecordShareDialog(props: RecordShareDialogProps) {
  const registry = useContributionRegistry();
  const registration = registry.getRenderer('record-share-dialog');
  const Component = registration?.component as
    | React.ComponentType<RecordShareDialogProps>
    | undefined;
  return Component ? <Component {...props} /> : null;
}

export default RecordShareDialog;
