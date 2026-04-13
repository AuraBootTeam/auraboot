// OSS slot stub — record sharing (share by user/org/link) lives in ent-org plugin.
// Enterprise overlay replaces this file.

export interface RecordShareDialogProps {
  open: boolean;
  onClose: () => void;
  resourceCode?: string;
  recordId?: string | number;
}

export function RecordShareDialog(_props: RecordShareDialogProps): null {
  return null;
}

export default RecordShareDialog;
