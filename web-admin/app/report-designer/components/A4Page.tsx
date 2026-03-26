/**
 * A4Page — A4-sized container with margin reference lines
 * Scales A4 dimensions (210×297mm) to fit on screen
 */

import React from 'react';
import type { PageConfig } from '../types';

interface A4PageProps {
  page: PageConfig;
  children: React.ReactNode;
}

// A4 dimensions in mm
const PAGE_SIZES: Record<string, { w: number; h: number }> = {
  A4: { w: 210, h: 297 },
  A3: { w: 297, h: 420 },
  LETTER: { w: 216, h: 279 },
  LEGAL: { w: 216, h: 356 },
};

const SCALE = 3; // 1mm = 3px on screen

export const A4Page: React.FC<A4PageProps> = ({ page, children }) => {
  const dims = PAGE_SIZES[page.size] || PAGE_SIZES.A4;
  const w = page.orientation === 'landscape' ? dims.h : dims.w;
  const h = page.orientation === 'landscape' ? dims.w : dims.h;

  const widthPx = w * SCALE;
  const minHeightPx = h * SCALE;

  return (
    <div
      className="relative mx-auto bg-white shadow-lg"
      style={{
        width: `${widthPx}px`,
        minHeight: `${minHeightPx}px`,
        padding: `${page.margin.top * SCALE}px ${page.margin.right * SCALE}px ${page.margin.bottom * SCALE}px ${page.margin.left * SCALE}px`,
      }}
    >
      {/* Margin reference lines (design mode only) */}
      <div
        className="pointer-events-none absolute inset-0 border border-dashed border-blue-200"
        style={{
          top: `${page.margin.top * SCALE}px`,
          right: `${page.margin.right * SCALE}px`,
          bottom: `${page.margin.bottom * SCALE}px`,
          left: `${page.margin.left * SCALE}px`,
        }}
      />
      {children}
    </div>
  );
};
