/**
 * PrintButton - Triggers browser print dialog for the current page.
 *
 * Hidden during print via `print-hide` / `data-print="hide"`.
 * Shows a printer icon button in the detail page header toolbar.
 */

import { PrinterIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';

interface PrintButtonProps {
  /** Page title — used only as tooltip context */
  title?: string;
}

export function PrintButton({ title }: PrintButtonProps) {
  const { t } = useI18n();
  const label = t('action.print') || 'Print';

  return (
    <button
      type="button"
      onClick={() => window.print()}
      className="print-hide inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 focus:outline-none"
      data-print="hide"
      title={title ? `${label}: ${title}` : label}
    >
      <PrinterIcon className="h-4 w-4" />
      <span className="hidden sm:inline">{label}</span>
    </button>
  );
}
