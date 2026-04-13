/**
 * Export/Import panel for BPM process packages
 */

import { useState } from 'react';
import { Button } from '~/ui/ui/button';
import { useToastContext } from '~/contexts/ToastContext';

interface ExportImportPanelProps {
  processKey?: string;
}

export function ExportImportPanel({ processKey }: ExportImportPanelProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [importResult, setImportResult] = useState<string | null>(null);

  const handleExport = async () => {
    if (!processKey) return;
    try {
      const response = await fetch(`/api/bpm/export/${processKey}`);
      if (!response.ok) throw new Error('Export failed');
      const data = await response.json();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${processKey}-export.json`;
      a.click();
      URL.revokeObjectURL(url);
      showSuccessToast('Export completed');
    } catch (error) {
      console.error('Export failed:', error);
      showErrorToast('Export failed');
    }
  };

  return (
    <div className="space-y-4 p-4">
      <h3 className="text-lg font-semibold">Export / Import</h3>

      {processKey && (
        <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
          <p className="mb-2 text-sm text-blue-800">Export process: {processKey}</p>
          <Button onClick={handleExport}>Export Package</Button>
        </div>
      )}

      <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
        <p className="mb-2 text-sm text-gray-700">Import process package</p>
        <div className="rounded-lg border-2 border-dashed border-gray-300 p-8 text-center">
          <p className="text-sm text-gray-500">Drag and drop ZIP file here or click to upload</p>
        </div>
        {importResult && <p className="mt-2 text-sm text-green-600">{importResult}</p>}
      </div>
    </div>
  );
}
