// OSS slot stub — org tree picker lives in ent-org plugin.
// Enterprise overlay replaces this file.

export interface OrgTreePickerProps {
  value?: string[];
  onChange?: (_value: string[]) => void;
  multiple?: boolean;
  placeholder?: string;
  disabled?: boolean;
  /** Principal IDs excluded from selection. */
  disabledPids?: string[];
}

export function OrgTreePicker(_props: OrgTreePickerProps): null {
  return null;
}

export default OrgTreePicker;
