import { useEffect, useState, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { CapabilityGroup } from './types';
import { capabilityService } from './capabilityService';
import { grantedCapabilityCodes, toggleCapability, isDirty } from './capabilityHelpers';
import CapabilityChecklist from './CapabilityChecklist';

interface CapabilityRoleEditorProps {
  roleId: string;
}

/**
 * Permission v2 role editor (capability layer): loads a role's capability view, seeds the selection
 * from what's already granted, lets the user toggle capabilities, and saves the selection back
 * (grant/revoke within the capability universe). The raw matrix stays available as an advanced
 * escape hatch elsewhere in the page; this is the primary, business-language surface.
 */
export default function CapabilityRoleEditor({ roleId }: CapabilityRoleEditorProps) {
  const { t } = useI18n();
  const [groups, setGroups] = useState<CapabilityGroup[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const fetched = await capabilityService.getForRole(roleId);
      setGroups(fetched);
      setSelected(grantedCapabilityCodes(fetched));
    } finally {
      setLoading(false);
    }
  }, [roleId]);

  useEffect(() => {
    void load();
  }, [load]);

  const onToggle = useCallback((code: string) => {
    setSelected((current) => toggleCapability(current, code));
  }, []);

  const save = useCallback(async () => {
    setSaving(true);
    try {
      const refreshed = await capabilityService.applySelection(roleId, selected);
      setGroups(refreshed);
      setSelected(grantedCapabilityCodes(refreshed));
    } finally {
      setSaving(false);
    }
  }, [roleId, selected]);

  if (loading) {
    return <div data-testid="capability-editor-loading">{t('common.loading', undefined, '加载中…')}</div>;
  }

  const dirty = isDirty(groups, selected);

  return (
    <div data-testid="capability-role-editor" className="flex flex-col gap-3">
      <CapabilityChecklist groups={groups} selected={selected} onToggle={onToggle} />
      <div className="flex justify-end">
        <button
          type="button"
          data-testid="capability-save"
          disabled={!dirty || saving}
          onClick={() => void save()}
          className="px-3 h-8 rounded-md bg-blue-600 text-white text-sm disabled:opacity-50"
        >
          {saving ? t('common.saving', undefined, '保存中…') : t('common.save', undefined, '保存')}
        </button>
      </div>
    </div>
  );
}
