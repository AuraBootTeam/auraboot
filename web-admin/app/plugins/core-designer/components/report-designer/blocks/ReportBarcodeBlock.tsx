/**
 * ReportBarcodeBlock — renders barcode using JsBarcode
 * Supports CODE128, CODE39, EAN13, EAN8, UPC, ITF14 formats
 */

import React, { useRef, useEffect } from 'react';
import JsBarcode from 'jsbarcode';
import type { BarcodeBlock } from '../types';

interface ReportBarcodeBlockProps {
  block: BarcodeBlock;
  mode: 'design' | 'runtime';
  data?: Record<string, unknown>[];
}

const SAMPLE_VALUES: Record<string, string> = {
  CODE128: 'ABC-12345',
  CODE39: 'abc12345',
  EAN13: '5901234123457',
  EAN8: '96385074',
  UPC: '123456789012',
  ITF14: '10012345000017',
};

export const ReportBarcodeBlock: React.FC<ReportBarcodeBlockProps> = ({
  block,
  mode,
  data = [],
}) => {
  const svgRef = useRef<SVGSVGElement>(null);

  // Resolve the barcode value
  const resolveValue = (): string => {
    if (block.staticValue) return block.staticValue;
    if (block.dataSource && block.field && data.length > 0) {
      return String(data[0][block.field] ?? '');
    }
    return '';
  };

  const value = resolveValue();
  const displayValue =
    mode === 'design' && !value ? SAMPLE_VALUES[block.format] || 'ABC-12345' : value;

  useEffect(() => {
    if (!svgRef.current || !displayValue) return;
    try {
      JsBarcode(svgRef.current, displayValue, {
        format: block.format || 'code128',
        width: block.width || 2,
        height: block.height || 60,
        displayValue: block.displayValue !== false,
        fontSize: block.fontSize || 14,
        margin: 10,
      });
    } catch {
      // Invalid value for format — show error state handled by the fallback below
      if (svgRef.current) {
        svgRef.current.innerHTML = '';
      }
    }
  }, [displayValue, block.format, block.width, block.height, block.displayValue, block.fontSize]);

  if (!displayValue) {
    return (
      <div className="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-400">
        <div className="mb-1 font-medium">{block.title || 'Barcode'}</div>
        <div>Set a static value or bind to a data source field</div>
      </div>
    );
  }

  return (
    <div>
      {block.title && <div className="mb-2 text-sm font-semibold text-gray-800">{block.title}</div>}
      <div className="inline-block">
        <svg ref={svgRef} />
      </div>
      {mode === 'design' && !value && (
        <div className="mt-1 text-center text-xs text-gray-400">Sample preview</div>
      )}
    </div>
  );
};
