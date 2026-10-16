import { useRef, useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import { useConfirmDialog } from '~/contexts/ConfirmDialogContext';
import { useDashboardStore } from '../store/useDashboardStore';
import {
  DASHBOARD_DOCUMENT_MAX_BYTES,
  DashboardDocumentError,
  exportDashboardDocument,
  parseDashboardDocument,
} from '../utils/dashboardDocument';

/** Portable design JSON is separate from the PDF/XLSX presentation exports. */
export function DashboardDocumentActions() {
  const st = useSmartText();
  const { confirm } = useConfirmDialog();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const {
    dashboard,
    widgets,
    layoutConfig,
    importDocument,
    isSaving,
    isLoading,
  } = useDashboardStore();
  const input = useRef<HTMLInputElement>(null);
  const [reading, setReading] = useState(false);
  const disabled = isSaving || isLoading || reading;
  const buttonClass =
    'rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50';

  const exportDesign = () => {
    if (!dashboard) return;
    try {
      const document = exportDashboardDocument(
        dashboard,
        widgets,
        layoutConfig,
      );
      const blob = new Blob([JSON.stringify(document, null, 2)], {
        type: 'application/json',
      });
      const url = URL.createObjectURL(blob);
      const anchor = window.document.createElement('a');
      anchor.href = url;
      anchor.download = `dashboard-${dashboard.title.replace(/[\\/:*?"<>|]+/g, '-').slice(0, 80) || 'design'}.json`;
      window.document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      showSuccessToast(
        st({
          zh: '已导出当前设计，包含未保存的修改。',
          en: 'Current design exported, including unsaved changes.',
        }),
      );
    } catch {
      showErrorToast(
        st({
          zh: '当前设计无法导出，请检查组件和布局配置。',
          en: 'Cannot export this design. Check widget and layout settings.',
        }),
      );
    }
  };

  const importDesign = async (file: File) => {
    setReading(true);
    try {
      if (file.size > DASHBOARD_DOCUMENT_MAX_BYTES)
        throw new DashboardDocumentError('size');
      const text = await file.text();
      parseDashboardDocument(text);
      const accepted = await confirm({
        title: st({ zh: '导入仪表盘设计', en: 'Import dashboard design' }),
        content: st({
          zh: '当前画布将替换为导入的个人草稿副本，未保存修改会丢失。已保存的原仪表盘不会改变；保存后创建新仪表盘。数据源仍使用当前账号的权限。',
          en: 'Replace this canvas with a new personal draft? Unsaved changes will be lost. The saved original stays unchanged; saving creates a new dashboard. Data sources use your current account permissions.',
        }),
        confirmText: st({ zh: '导入为新草稿', en: 'Import as new draft' }),
      });
      if (!accepted) return;
      importDocument(text);
      showSuccessToast(
        st({
          zh: '已导入个人草稿。请检查数据源并保存。',
          en: 'Personal draft imported. Review data sources and save.',
        }),
      );
    } catch (error) {
      const reason =
        error instanceof DashboardDocumentError ? error.reason : 'invalid';
      const labels = {
        size: {
          zh: '设计文件不能超过 2 MB。',
          en: 'Design files must be smaller than 2 MB.',
        },
        version: {
          zh: '不支持此设计文件版本，请使用版本 1。',
          en: 'Unsupported design version. Use version 1.',
        },
        invalid: {
          zh: '设计文件无效。请选择 AuraBoot 导出的仪表盘 JSON，并检查组件和布局。',
          en: 'Invalid design file. Select an AuraBoot dashboard JSON export with valid widgets and layout.',
        },
      };
      showErrorToast(st(labels[reason]));
    } finally {
      if (input.current) input.current.value = '';
      setReading(false);
    }
  };

  return (
    <>
      <input
        ref={input}
        type="file"
        accept=".json,application/json"
        className="hidden"
        data-testid="dashboard-design-import-file"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void importDesign(file);
        }}
      />
      <button
        type="button"
        className={buttonClass}
        disabled={disabled}
        onClick={() => input.current?.click()}
        data-testid="dashboard-design-import"
      >
        {st({ zh: '导入设计', en: 'Import design' })}
      </button>
      <button
        type="button"
        className={buttonClass}
        disabled={disabled || !dashboard}
        onClick={exportDesign}
        data-testid="dashboard-design-export"
      >
        {st({ zh: '导出设计', en: 'Export design' })}
      </button>
    </>
  );
}
