/**
 * Process-level metadata editing panel (shown when no node/edge is selected).
 */

import { useI18n } from '~/contexts/I18nContext';
import type { ProcessMetadataProps } from '../BPMNPropertyPanel';
import type {
  WithdrawPolicy,
  CcPolicy,
} from '~/plugins/core-designer/components/bpmn-designer/types';

const CATEGORY_OPTIONS = ['finance', 'HR', 'procurement', 'sales', 'general'];
const WITHDRAW_POLICY_OPTIONS: WithdrawPolicy[] = ['strict', 'loose', 'none'];
const CC_POLICY_OPTIONS: CcPolicy[] = ['initiator', 'assignee', 'all'];

export function ProcessMetadataPanel({ metadata }: { metadata: ProcessMetadataProps }) {
  const { t } = useI18n();
  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.process.name')}</label>
        <input
          type="text"
          value={metadata.name}
          onChange={(e) => metadata.onNameChange(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          placeholder={t('bpmn.prop.process.namePlaceholder')}
          data-testid="prop-panel-name"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.process.key')}</label>
        <input
          type="text"
          value={metadata.processKey}
          readOnly={metadata.isExisting}
          className={`w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm ${
            metadata.isExisting ? 'cursor-not-allowed bg-gray-50 text-gray-500' : ''
          }`}
          data-testid="prop-panel-key"
        />
        {metadata.isExisting && (
          <p className="mt-1 text-xs text-gray-400">{t('bpmn.prop.process.keyReadonlyHint')}</p>
        )}
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.common.description')}</label>
        <textarea
          value={metadata.description}
          onChange={(e) => metadata.onDescriptionChange(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
          rows={3}
          placeholder={t('bpmn.prop.process.descPlaceholder')}
          data-testid="prop-panel-description"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.process.category')}</label>
        <div className="relative">
          <input
            type="text"
            value={metadata.category}
            onChange={(e) => metadata.onCategoryChange(e.target.value)}
            list="category-options"
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            placeholder={t('bpmn.prop.process.categoryPlaceholder')}
            data-testid="prop-panel-category"
          />
          <datalist id="category-options">
            {CATEGORY_OPTIONS.map((cat) => (
              <option key={cat} value={cat} />
            ))}
          </datalist>
        </div>
      </div>

      {/* AuraBoot process policies — compiled into <smart:properties> at deploy time. */}
      <div className="mb-4 rounded-md border border-gray-200 p-3">
        <h3 className="mb-2 text-sm font-semibold text-gray-700">
          {t('bpmn.prop.process.auraSectionTitle')}
        </h3>

        <div className="mb-3">
          <label className="mb-1 block text-xs font-medium text-gray-600">
            {t('bpmn.prop.process.withdrawPolicy')}
          </label>
          <select
            value={metadata.withdrawPolicy ?? ''}
            onChange={(e) =>
              metadata.onWithdrawPolicyChange(
                e.target.value === '' ? undefined : (e.target.value as WithdrawPolicy),
              )
            }
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            data-testid="prop-panel-withdraw-policy"
          >
            <option value="">{t('bpmn.prop.process.policyDefault')}</option>
            {WITHDRAW_POLICY_OPTIONS.map((code) => (
              <option key={code} value={code}>
                {t(`bpmn.prop.process.withdrawPolicy.${code}`)}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-gray-400">
            {t('bpmn.prop.process.withdrawPolicyHint')}
          </p>
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-gray-600">
            {t('bpmn.prop.process.ccPolicy')}
          </label>
          <select
            value={metadata.ccPolicy ?? ''}
            onChange={(e) =>
              metadata.onCcPolicyChange(
                e.target.value === '' ? undefined : (e.target.value as CcPolicy),
              )
            }
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            data-testid="prop-panel-cc-policy"
          >
            <option value="">{t('bpmn.prop.process.policyDefault')}</option>
            {CC_POLICY_OPTIONS.map((code) => (
              <option key={code} value={code}>
                {t(`bpmn.prop.process.ccPolicy.${code}`)}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-gray-400">
            {t('bpmn.prop.process.ccPolicyHint')}
          </p>
        </div>
      </div>
    </>
  );
}
