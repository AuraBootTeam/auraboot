import React, { useState, useCallback } from 'react';

export interface SparklineProps {
  points: number[];
  labels?: string[];
  width?: number;
  height?: number;
  stroke?: string;
  className?: string;
}

interface TooltipState {
  index: number;
  x: number;
  y: number;
}

export function Sparkline({
  points,
  labels,
  width = 60,
  height = 20,
  stroke = '#635bff',
  className,
}: SparklineProps) {
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);

  const handlePointEnter = useCallback(
    (index: number, cx: number, cy: number) => {
      setTooltip({ index, x: cx, y: cy });
    },
    [],
  );

  const handleLeave = useCallback(() => {
    setTooltip(null);
  }, []);

  if (!points || points.length < 2) {
    return null;
  }

  const min = Math.min(...points);
  const max = Math.max(...points);
  const range = max - min || 1;
  const step = width / (points.length - 1);

  const coordPairs = points.map((v, i) => {
    const cx = i * step;
    const cy = height - 2 - ((v - min) / range) * (height - 4);
    return { cx, cy };
  });

  const coordStr = coordPairs.map(({ cx, cy }) => `${cx.toFixed(2)},${cy.toFixed(2)}`).join(' ');

  // Tooltip dimensions for positioning
  const TIP_W = 36;
  const TIP_H = 16;
  const TIP_OFFSET_Y = 4;

  let tipX = 0;
  let tipY = 0;
  if (tooltip !== null) {
    tipX = Math.min(Math.max(tooltip.x - TIP_W / 2, 0), width - TIP_W);
    tipY = tooltip.y - TIP_H - TIP_OFFSET_Y;
    if (tipY < 0) tipY = tooltip.y + TIP_OFFSET_Y;
  }

  const activeValue = tooltip !== null ? points[tooltip.index] : null;
  const activeLabel = tooltip !== null && labels ? labels[tooltip.index] : null;
  const tooltipText =
    activeValue !== null
      ? activeLabel != null
        ? `${activeLabel}: ${activeValue}`
        : String(activeValue)
      : '';

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      aria-label={tooltip !== null ? tooltipText : undefined}
      onMouseLeave={handleLeave}
      style={{ overflow: 'visible' }}
    >
      <polyline
        points={coordStr}
        fill="none"
        stroke={stroke}
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />

      {/* Invisible hit-area strips per point for hover detection */}
      {coordPairs.map(({ cx, cy }, i) => (
        <circle
          key={i}
          cx={cx}
          cy={cy}
          r={4}
          fill="transparent"
          onMouseEnter={() => handlePointEnter(i, cx, cy)}
          style={{ cursor: 'default' }}
          aria-hidden="true"
        />
      ))}

      {/* Visible dot on active point */}
      {tooltip !== null && (
        <circle
          cx={tooltip.x}
          cy={tooltip.y}
          r={2.5}
          fill={stroke}
          pointerEvents="none"
          aria-hidden="true"
        />
      )}

      {/* Tooltip — uses design-system inverse surface (--color-inverse / --shadow-pop) */}
      {tooltip !== null && (
        <g
          transform={`translate(${tipX},${tipY})`}
          pointerEvents="none"
          aria-hidden="true"
          role="tooltip"
          data-testid="sparkline-tooltip"
        >
          <rect
            x={0}
            y={0}
            width={TIP_W}
            height={TIP_H}
            rx={3}
            ry={3}
            fill="var(--color-inverse, #1A1A22)"
            style={{ filter: 'drop-shadow(0 1px 2px rgba(16,18,23,.04)) drop-shadow(0 8px 24px -6px rgba(16,18,23,.14))' }}
          />
          <text
            x={TIP_W / 2}
            y={TIP_H / 2}
            textAnchor="middle"
            dominantBaseline="middle"
            fill="var(--color-inverse-text, #FFFFFF)"
            fontSize={9}
            fontFamily="var(--font-sans, system-ui, sans-serif)"
            data-testid="sparkline-tooltip-text"
          >
            {tooltipText}
          </text>
        </g>
      )}
    </svg>
  );
}
