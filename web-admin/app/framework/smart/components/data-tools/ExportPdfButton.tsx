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
        onclone: (clonedDocument, clonedTarget) => {
          injectPdfColorFallbackStyle(clonedDocument);
          sanitizeUnsupportedPdfColors(clonedDocument.documentElement);
          sanitizeUnsupportedPdfColors(clonedTarget as HTMLElement);
        },
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
      console.warn('PDF image export failed; falling back to text PDF:', err);
      try {
        await exportTextFallbackPdf(targetRef.current, fileName, orientation);
      } catch (fallbackErr) {
        console.error('PDF fallback export failed:', fallbackErr);
        showErrorToast('PDF export failed. Please try again.');
      }
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

async function exportTextFallbackPdf(
  target: HTMLElement,
  fileName: string,
  orientation: 'portrait' | 'landscape',
): Promise<void> {
  const { jsPDF } = await import('jspdf');
  const pdf = new jsPDF({
    orientation,
    unit: 'mm',
    format: 'a4',
  });
  const margin = 12;
  const pageWidth = pdf.internal.pageSize.getWidth();
  const pageHeight = pdf.internal.pageSize.getHeight();
  const maxWidth = pageWidth - margin * 2;
  let y = margin;

  pdf.setFont('helvetica', 'bold');
  pdf.setFontSize(14);
  pdf.text(fileName || 'Dashboard export', margin, y);
  y += 8;

  pdf.setFont('helvetica', 'normal');
  pdf.setFontSize(9);
  const text = normalizeExportText(target.innerText) || 'No visible dashboard content';
  const lines = pdf.splitTextToSize(text, maxWidth) as string[];
  for (const line of lines) {
    if (y > pageHeight - margin) {
      pdf.addPage();
      y = margin;
    }
    pdf.text(line, margin, y);
    y += 5;
  }
  pdf.save(`${fileName}.pdf`);
}

function normalizeExportText(value: string): string {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .join('\n')
    .slice(0, 8000);
}

function injectPdfColorFallbackStyle(clonedDocument: Document): void {
  const style = clonedDocument.createElement('style');
  style.textContent = `
    *, *::before, *::after {
      color: #111827 !important;
      background-color: #ffffff !important;
      border-color: #d1d5db !important;
      outline-color: #2563eb !important;
      text-decoration-color: #111827 !important;
      caret-color: #111827 !important;
      box-shadow: none !important;
      text-shadow: none !important;
      background-image: none !important;
      --tw-ring-color: rgba(37, 99, 235, 0.45) !important;
      --tw-shadow-color: transparent !important;
    }
    svg, svg * {
      color: #111827 !important;
      fill: #111827 !important;
      stroke: #111827 !important;
    }
  `;
  clonedDocument.head.appendChild(style);
}

function sanitizeUnsupportedPdfColors(root: HTMLElement): void {
  const view = root.ownerDocument.defaultView;
  if (!view) return;
  const nodes = [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))];
  for (const node of nodes) {
    const style = view.getComputedStyle(node);
    forceColor(node, 'color', '#111827');
    forceColor(node, 'background-color', '#ffffff');
    forceColor(node, 'border-top-color', '#d1d5db');
    forceColor(node, 'border-right-color', '#d1d5db');
    forceColor(node, 'border-bottom-color', '#d1d5db');
    forceColor(node, 'border-left-color', '#d1d5db');
    forceColor(node, 'outline-color', '#2563eb');
    forceColor(node, 'text-decoration-color', '#111827');
    forceColor(node, 'caret-color', '#111827');
    forceColor(node, 'fill', '#111827');
    forceColor(node, 'stroke', '#111827');
    if (node instanceof view.SVGElement) {
      node.setAttribute('color', '#111827');
      node.setAttribute('fill', '#111827');
      node.setAttribute('stroke', '#111827');
    }
    sanitizeComplexColor(node, style, 'box-shadow', 'none');
    sanitizeComplexColor(node, style, 'text-shadow', 'none');
    sanitizeComplexColor(node, style, 'background-image', 'none');
  }
}

function forceColor(
  node: HTMLElement,
  property: string,
  fallback: string,
): void {
  node.style.setProperty(property, fallback, 'important');
}

function sanitizeComplexColor(
  node: HTMLElement,
  style: CSSStyleDeclaration,
  property: string,
  fallback: string,
): void {
  const value = style.getPropertyValue(property);
  if (!usesUnsupportedColor(value)) return;
  node.style.setProperty(property, fallback, 'important');
}

function usesUnsupportedColor(value: string | null | undefined): boolean {
  return Boolean(value?.toLowerCase().includes('oklch('));
}

export default ExportPdfButton;
