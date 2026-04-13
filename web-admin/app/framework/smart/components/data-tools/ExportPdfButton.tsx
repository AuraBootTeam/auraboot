/**
 * ExportPdfButton Component
 *
 * Button that exports the current view or dashboard as a PDF file.
 * Uses html2canvas for screen capture and jspdf for PDF generation.
 */

import React, { useState, useCallback } from 'react';
import { cn } from '~/utils/cn';
import { useToastContext } from '~/contexts/ToastContext';

export interface ExportPdfButtonProps {
  /** Ref to the DOM element to capture */
  targetRef: React.RefObject<HTMLElement | null>;
  /** File name for the exported PDF */
  fileName?: string;
  /** PDF orientation */
  orientation?: 'portrait' | 'landscape';
  /** Custom CSS class */
  className?: string;
}

/**
 * ExportPdfButton - Export current view as PDF
 */
export const ExportPdfButton: React.FC<ExportPdfButtonProps> = ({
  targetRef,
  fileName = 'export',
  orientation = 'landscape',
  className,
}) => {
  const [exporting, setExporting] = useState(false);
  const { showErrorToast } = useToastContext();

  const handleExport = useCallback(async () => {
    if (!targetRef.current) return;

    setExporting(true);
    try {
      const html2canvas = (await import('html2canvas')).default;
      const { jsPDF } = await import('jspdf');

      const canvas = await html2canvas(targetRef.current, {
        scale: 2,
        useCORS: true,
        logging: false,
        backgroundColor: '#ffffff',
      });

      const imgData = canvas.toDataURL('image/jpeg', 0.95);
      const pdf = new jsPDF({
        orientation,
        unit: 'mm',
        format: 'a4',
      });

      const pageWidth = pdf.internal.pageSize.getWidth();
      const pageHeight = pdf.internal.pageSize.getHeight();
      const margin = 10;
      const availableWidth = pageWidth - margin * 2;
      const availableHeight = pageHeight - margin * 2;

      const imgWidth = canvas.width;
      const imgHeight = canvas.height;
      const ratio = Math.min(availableWidth / imgWidth, availableHeight / imgHeight);

      const scaledWidth = imgWidth * ratio;
      const scaledHeight = imgHeight * ratio;

      // Center the image
      const x = margin + (availableWidth - scaledWidth) / 2;
      const y = margin;

      pdf.addImage(imgData, 'jpeg', x, y, scaledWidth, scaledHeight);
      pdf.save(`${fileName}.pdf`);
    } catch (err) {
      console.error('PDF export failed:', err);
      showErrorToast('PDF export failed. Please try again.');
    } finally {
      setExporting(false);
    }
  }, [targetRef, fileName, orientation]);

  return (
    <button
      type="button"
      onClick={handleExport}
      disabled={exporting}
      data-testid="export-pdf-button"
      className={cn(
        'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium',
        'rounded-md border border-gray-300 bg-white text-gray-700 shadow-sm',
        'hover:bg-gray-50 focus:ring-2 focus:ring-blue-500 focus:outline-none',
        'disabled:cursor-not-allowed disabled:opacity-50',
        'transition-colors duration-150',
        className,
      )}
    >
      {exporting ? (
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-red-500" />
      ) : (
        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"
          />
        </svg>
      )}
      PDF
    </button>
  );
};

export default ExportPdfButton;
