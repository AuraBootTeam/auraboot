import React from 'react';

export interface SparklineProps {
  points: number[];
  width?: number;
  height?: number;
  stroke?: string;
  className?: string;
}

export function Sparkline({
  points,
  width = 60,
  height = 20,
  stroke = '#635bff',
  className,
}: SparklineProps) {
  if (!points || points.length < 2) {
    return (
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true">
        <line
          x1={0}
          y1={height - 2}
          x2={width}
          y2={height - 2}
          stroke="#e3e8ee"
          strokeWidth={1.5}
        />
      </svg>
    );
  }

  const min = Math.min(...points);
  const max = Math.max(...points);
  const range = max - min || 1;
  const step = width / (points.length - 1);

  const coords = points
    .map((v, i) => {
      const x = (i * step).toFixed(2);
      const y = (height - 2 - ((v - min) / range) * (height - 4)).toFixed(2);
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true">
      <polyline
        points={coords}
        fill="none"
        stroke={stroke}
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
