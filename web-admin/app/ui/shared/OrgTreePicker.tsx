// OSS slot stub — the real org tree picker lives in the ent-org plugin and the
// enterprise overlay replaces this file. In OSS the department tree isn't
// available, so instead of rendering nothing (a blank, confusing tab) we show a
// graceful empty state that points the user at the working "Member List" tab.

import { BuildingOffice2Icon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';

export interface OrgTreePickerProps {
  value?: string[];
  onChange?: (_value: string[]) => void;
  multiple?: boolean;
  placeholder?: string;
  disabled?: boolean;
  /** Principal IDs excluded from selection. */
  disabledPids?: string[];
}

export function OrgTreePicker(_props: OrgTreePickerProps) {
  const { t } = useI18n();
  return (
    <div
      data-testid="org-tree-picker-empty"
      className="flex flex-col items-center justify-center rounded-md border border-dashed border-gray-300 py-16 text-center dark:border-gray-600"
    >
      <BuildingOffice2Icon className="mb-3 h-10 w-10 text-gray-300" />
      <p className="text-sm text-gray-500 dark:text-gray-400">
        {t(
          'admin.permission.members.orgPickerUnavailable',
          undefined,
          'Organization structure requires the org-management plugin.',
        )}
      </p>
      <p className="mt-1 text-xs text-gray-400">
        {t(
          'admin.permission.members.orgPickerUseList',
          undefined,
          'Switch to the "Member List" tab to add members.',
        )}
      </p>
    </div>
  );
}

export default OrgTreePicker;
