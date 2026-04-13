/**
 * ReportWatermarkBlock — renders a watermark overlay on the report page
 * Supports repeating text pattern with configurable rotation, opacity, and style
 */

import React from 'react';
import type { WatermarkBlock } from '../types';

interface ReportWatermarkBlockProps {
  block: WatermarkBlock;
  mode: 'design' | 'runtime';
}

export const ReportWatermarkBlock: React.FC<ReportWatermarkBlockProps> = ({ block, mode }) => {
  const text = block.text || 'watermark';
  const rotation = block.rotation ?? -30;
  const opacity = block.opacity ?? 0.1;
  const fontSize = block.fontSize ?? 16;
  const color = block.color ?? '#000000';
  const repeat = block.repeat !== false;

  if (!repeat) {
    // Single centered watermark
    return (
      <div
        style={{
          position: mode === 'design' ? 'relative' : 'fixed',
          top: 0,
          left: 0,
          width: '100%',
          height: mode === 'design' ? '120px' : '100%',
          pointerEvents: 'none',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 1000,
          overflow: 'hidden',
        }}
      >
        <span
          style={{
            transform: `rotate(${rotation}deg)`,
            opacity,
            fontSize: `${fontSize * 3}px`,
            color,
            fontWeight: 'bold',
            userSelect: 'none',
            whiteSpace: 'nowrap',
          }}
        >
          {text}
        </span>
      </div>
    );
  }

  // Repeating watermark grid
  const rows = mode === 'design' ? 3 : 8;
  const cols = 4;

  return (
    <div
      style={{
        position: mode === 'design' ? 'relative' : 'fixed',
        top: 0,
        left: 0,
        width: '100%',
        height: mode === 'design' ? '120px' : '100%',
        pointerEvents: 'none',
        overflow: 'hidden',
        zIndex: 1000,
      }}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: `repeat(${cols}, 1fr)`,
          gridTemplateRows: `repeat(${rows}, 1fr)`,
          width: '100%',
          height: '100%',
        }}
      >
        {Array.from({ length: rows * cols }).map((_, i) => (
          <div
            key={i}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <span
              style={{
                transform: `rotate(${rotation}deg)`,
                opacity,
                fontSize: `${fontSize}px`,
                color,
                fontWeight: 'bold',
                userSelect: 'none',
                whiteSpace: 'nowrap',
              }}
            >
              {text}
            </span>
          </div>
        ))}
      </div>
      {mode === 'design' && (
        <div className="absolute right-0 bottom-1 left-0 text-center text-xs text-gray-400">
          Watermark overlay preview
        </div>
      )}
    </div>
  );
};
