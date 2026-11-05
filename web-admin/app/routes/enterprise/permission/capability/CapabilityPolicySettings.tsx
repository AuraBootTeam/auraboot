import { useEffect, useRef, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { getLocalizedText } from '~/utils/i18n';
import { permissionService } from '~/shared/services/permissionService';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '~/ui/ui/dialog';
import PolicyConfigDialog, { type PolicyConfigDialogProps } from '../PolicyConfigDialog';
import type { PermissionMatrixDTO, PermissionMatrixActionDTO } from '../types';
import type { Capability } from './types';

/** Rule authoring uses existing role grants; it never changes capability selection or record scope. */
export default function CapabilityPolicySettings({
  rolePid,
  capability,
  matrix,
  onClose,
  onRefresh,
  onReadFailure,
}: {
  rolePid: string;
  capability: Capability;
  matrix: PermissionMatrixDTO | null;
  onClose: () => void;
  onRefresh: () => Promise<PermissionMatrixDTO>;
  onReadFailure: () => void;
}) {
  const { t, locale } = useI18n();
  const { showErrorToast } = useToastContext();
  const text = (key: string, zh: string, en: string) =>
    t(key, undefined, locale?.startsWith('zh') ? zh : en);
  const alive = useRef(true);
  const request = useRef(0);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [target, setTarget] = useState<{
    permissionPid: string;
    label: string;
    schema: PolicyConfigDialogProps['schema'];
    values: Record<string, unknown>;
  } | null>(null);
  useEffect(() => {
    alive.current = true;
    setTarget(null);
    setLoading(false);
    setFailed(false);
    return () => {
      alive.current = false;
      request.current++;
    };
  }, [rolePid, capability.code]);
  const actions = (matrix?.modules ?? [])
    .flatMap((module) => module.resources.flatMap((resource) => resource.actions))
    .filter(
      (action) =>
        action.granted &&
        action.supported &&
        action.policySchema &&
        capability.includes.includes(action.code),
    );
  const label = (action: PermissionMatrixActionDTO) =>
    t(`permission.${action.code}`, undefined, action.label);
  const select = async (action: PermissionMatrixActionDTO) => {
    const current = ++request.current;
    setLoading(true);
    setFailed(false);
    setTarget(null);
    try {
      const schema: unknown = JSON.parse(action.policySchema!);
      if (!schema || typeof schema !== 'object' || Array.isArray(schema))
        throw new Error('Invalid policy schema');
      for (const field of Object.values(schema)) {
        if (
          !field ||
          typeof field !== 'object' ||
          Array.isArray(field) ||
          !['number', 'string', 'boolean', 'enum', 'enum[]', 'rule-center'].includes(field.type)
        )
          throw new Error('Invalid policy field');
      }
      const values = await permissionService.getPolicy(rolePid, action.permissionPid);
      if (!alive.current || current !== request.current) return;
      setTarget({
        permissionPid: action.permissionPid,
        label: label(action),
        schema: schema as PolicyConfigDialogProps['schema'],
        values: values ?? {},
      });
    } catch {
      if (alive.current && current === request.current) setFailed(true);
    } finally {
      if (alive.current && current === request.current) setLoading(false);
    }
  };
  const saved = async () => {
    try {
      await onRefresh();
    } catch {
      if (alive.current) {
        showErrorToast(
          text(
            'admin.permission.policy.readbackFailed',
            '规则已保存，但无法回读当前权限；请重新加载后继续。',
            'Rules were saved, but current permissions could not be read. Reload before continuing.',
          ),
        );
        onReadFailure();
      }
    }
    if (alive.current) onClose();
  };
  if (target)
    return (
      <PolicyConfigDialog
        open
        rolePid={rolePid}
        permissionPid={target.permissionPid}
        permissionLabel={target.label}
        schema={target.schema}
        initialValues={target.values}
        onClose={onClose}
        onSuccess={saved}
      />
    );
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent className="max-h-[calc(100vh-2rem)] max-w-xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {text('admin.permission.capability.rules', '业务规则', 'Business rules')}
            {' · '}
            {getLocalizedText(capability.localizedLabels, locale, t) || capability.label}
          </DialogTitle>
        </DialogHeader>
        <p className="text-text-2 text-sm">
          {text(
            'admin.permission.policy.chooseGrantedAction',
            '选择当前角色已授权的业务动作。其他角色的授权仍会影响成员的实际权限。',
            'Choose an action granted to this role. Other role grants still affect a member’s effective access.',
          )}
        </p>
        {loading && (
          <p role="status">
            {text('admin.permission.policy.loading', '正在读取当前规则…', 'Loading current rules…')}
          </p>
        )}
        {failed && (
          <p role="alert">
            {text(
              'admin.permission.policy.readFailed',
              '无法读取当前规则；请检查授权后重新选择动作重试。',
              'Current rules could not be loaded. Check authority and select the action again.',
            )}
          </p>
        )}
        <div className="flex flex-col gap-2">
          {actions.map((action) => (
            <button
              key={action.permissionPid}
              type="button"
              className="rounded-control border-border border p-3 text-left"
              disabled={loading}
              onClick={() => void select(action)}
            >
              {label(action)}
            </button>
          ))}
        </div>
        {actions.length === 0 && (
          <p>
            {text(
              'admin.permission.policy.noGrantedActions',
              '此能力没有已授权且可配置规则的动作。',
              'This capability has no granted actions with configurable rules.',
            )}
          </p>
        )}
      </DialogContent>
    </Dialog>
  );
}
