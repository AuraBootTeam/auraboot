/**
 * ChartSpec → print-safe SVG renderer (B2c).
 *
 * DDR-2026-06-18 / backlog 2026-06-18 §B2c "SVG print renderer adapter".
 *
 * Renders a renderer-agnostic ChartSpec to a STATIC, dependency-free SVG string
 * for the report/PDF (svg-print) target: no <script>, no animation, no
 * interactivity — safe for openhtmltopdf / Chromium-headless print. It consumes
 * the B2a ChartSpec + validateChartSpecForTarget; when a spec cannot be safely
 * drawn on print (unsupported type / unbounded dataset / drilldown-required) it
 * degrades to a labeled TABLE fallback rather than emitting a wrong chart.
 *
 * This module is renderer-SPECIFIC by definition (it IS the SVG renderer), so it
 * legitimately flattens theme tokens to a static print palette — exactly the
 * `theme: 'degrade'` behavior the CAPABILITY_MATRIX declares for svg-print.
 */
import type { ChartSpec } from './chart-spec';
import {
  type ChartFallback,
  type ChartSpecDegradation,
  validateChartSpecForTarget,
} from './chart-spec-validation';

export interface ChartSvgOptions {
  width?: number;
  height?: number;
}

export interface ChartSvgResult {
  svg: string;
  /** Non-null when the chart was degraded to a fallback (always a print table). */
  fallback: ChartFallback | null;
  degradations: ChartSpecDegradation[];
}

interface Datum {
  label: string;
  value: number;
}

// Static print palette — the theme-token flatten for the svg-print target
// (CAPABILITY_MATRIX theme: 'degrade'). Renderer-local concrete colors are
// required for print output; this is not UI chrome.
const PALETTE = [
  '#3b82f6',
  '#ef4444',
  '#22c55e',
  '#f59e0b',
  '#8b5cf6',
  '#ec4899',
  '#06b6d4',
  '#f97316',
];
const AXIS = '#64748b';
const TEXT = '#1e293b';

function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** Round to 1 decimal for deterministic, compact SVG (stable snapshots). */
function r1(n: number): number {
  return Math.round(n * 10) / 10;
}

function dimField(spec: ChartSpec, role: 'category' | 'name'): string | undefined {
  return (
    spec.dimensions.find((d) => d.role === role)?.field ?? spec.dimensions[0]?.field
  );
}

function toSeries(spec: ChartSpec, rows: Record<string, unknown>[]): Datum[] {
  const labelField = dimField(spec, spec.type === 'pie' ? 'name' : 'category');
  const valueField = spec.measures[0]?.field;
  return rows.map((row) => ({
    label: labelField ? String(row[labelField] ?? '') : '',
    value: valueField ? Number(row[valueField] ?? 0) || 0 : 0,
  }));
}

function titleText(spec: ChartSpec, width: number): string {
  if (!spec.title) return '';
  const t = typeof spec.title === 'string' ? spec.title : Object.values(spec.title)[0] ?? '';
  return `<text x="${r1(width / 2)}" y="16" text-anchor="middle" font-size="13" font-weight="bold" fill="${TEXT}">${esc(t)}</text>`;
}

function wrap(inner: string, width: number, height: number): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" ` +
    `viewBox="0 0 ${width} ${height}" font-family="sans-serif">${inner}</svg>`
  );
}

function renderBars(data: Datum[], width: number, height: number, horizontal: boolean): string {
  const n = data.length || 1;
  const max = Math.max(...data.map((d) => d.value), 1);
  const top = 24;
  const parts: string[] = [`<g data-orientation="${horizontal ? 'horizontal' : 'vertical'}">`];

  if (horizontal) {
    const left = 60;
    const right = 16;
    const plotW = width - left - right;
    const plotH = height - top - 16;
    const step = plotH / n;
    const barH = step * 0.7;
    data.forEach((d, i) => {
      const y = top + i * step + (step - barH) / 2;
      const w = (d.value / max) * plotW;
      parts.push(
        `<rect x="${left}" y="${r1(y)}" width="${r1(w)}" height="${r1(barH)}" fill="${PALETTE[0]}" />`,
      );
      parts.push(
        `<text x="${left - 4}" y="${r1(y + barH / 2 + 4)}" text-anchor="end" font-size="11" fill="${TEXT}">${esc(d.label)}</text>`,
      );
    });
  } else {
    const left = 16;
    const bottom = 28;
    const plotW = width - left - 16;
    const plotH = height - top - bottom;
    const step = plotW / n;
    const barW = step * 0.7;
    data.forEach((d, i) => {
      const x = left + i * step + (step - barW) / 2;
      const h = (d.value / max) * plotH;
      const y = top + (plotH - h);
      parts.push(
        `<rect x="${r1(x)}" y="${r1(y)}" width="${r1(barW)}" height="${r1(h)}" fill="${PALETTE[0]}" />`,
      );
      parts.push(
        `<text x="${r1(x + barW / 2)}" y="${height - 12}" text-anchor="middle" font-size="11" fill="${TEXT}">${esc(d.label)}</text>`,
      );
    });
  }
  parts.push('</g>');
  return parts.join('');
}

function renderPie(data: Datum[], width: number, height: number): string {
  const cx = width / 2;
  const cy = height / 2 + 8;
  const r = Math.min(width, height) / 2 - 24;
  const total = data.reduce((sum, d) => sum + d.value, 0);
  const parts: string[] = ['<g data-render="pie">'];
  let angle = -Math.PI / 2;
  data.forEach((d, i) => {
    const frac = total > 0 ? d.value / total : 1 / (data.length || 1);
    const next = angle + frac * Math.PI * 2;
    const x1 = cx + r * Math.cos(angle);
    const y1 = cy + r * Math.sin(angle);
    const x2 = cx + r * Math.cos(next);
    const y2 = cy + r * Math.sin(next);
    const largeArc = frac > 0.5 ? 1 : 0;
    parts.push(
      `<path d="M ${r1(cx)} ${r1(cy)} L ${r1(x1)} ${r1(y1)} A ${r1(r)} ${r1(r)} 0 ${largeArc} 1 ${r1(x2)} ${r1(y2)} Z" ` +
        `fill="${PALETTE[i % PALETTE.length]}" />`,
    );
    angle = next;
  });
  parts.push('</g>');
  return parts.join('');
}

function renderLine(data: Datum[], width: number, height: number): string {
  const n = data.length || 1;
  const max = Math.max(...data.map((d) => d.value), 1);
  const left = 16;
  const top = 24;
  const bottom = 28;
  const plotW = width - left - 16;
  const plotH = height - top - bottom;
  const step = n > 1 ? plotW / (n - 1) : 0;
  const points = data
    .map((d, i) => `${r1(left + i * step)},${r1(top + (plotH - (d.value / max) * plotH))}`)
    .join(' ');
  const labels = data
    .map(
      (d, i) =>
        `<text x="${r1(left + i * step)}" y="${height - 12}" text-anchor="middle" font-size="11" fill="${TEXT}">${esc(d.label)}</text>`,
    )
    .join('');
  return `<g data-render="line"><polyline fill="none" stroke="${PALETTE[0]}" stroke-width="2" points="${points}" />${labels}</g>`;
}

function renderTable(
  spec: ChartSpec,
  rows: Record<string, unknown>[],
  width: number,
  height: number,
  note?: string,
): string {
  const columns =
    rows.length > 0
      ? Object.keys(rows[0])
      : [...spec.dimensions.map((d) => d.field), ...spec.measures.map((m) => m.field)];
  const rowH = 18;
  const top = note ? 34 : 18;
  const colW = columns.length > 0 ? width / columns.length : width;
  const parts: string[] = ['<g data-render="table">'];
  if (note) {
    parts.push(
      `<text x="8" y="16" font-size="11" fill="${AXIS}">${esc(note)}</text>`,
    );
  }
  columns.forEach((col, c) => {
    parts.push(
      `<text x="${r1(c * colW + 4)}" y="${top}" font-size="11" font-weight="bold" fill="${TEXT}">${esc(col)}</text>`,
    );
  });
  rows.forEach((row, rIdx) => {
    const y = top + (rIdx + 1) * rowH;
    if (y > height) return;
    columns.forEach((col, c) => {
      parts.push(
        `<text x="${r1(c * colW + 4)}" y="${y}" font-size="11" fill="${TEXT}">${esc(row[col])}</text>`,
      );
    });
  });
  parts.push('</g>');
  return parts.join('');
}

export function renderChartSpecToSvg(
  spec: ChartSpec,
  rows: Record<string, unknown>[],
  opts?: ChartSvgOptions,
): ChartSvgResult {
  const width = opts?.width ?? 400;
  const height = opts?.height ?? 240;
  const validation = validateChartSpecForTarget(spec, 'svg-print');

  // Validation errors → always render the universal print-safe fallback (a table)
  // with the validation message, and report fallback:'table' (what we actually did).
  if (!validation.ok) {
    const note = validation.errors.map((e) => e.message).join(' ');
    const svg = wrap(titleText(spec, width) + renderTable(spec, rows, width, height, note), width, height);
    return { svg, fallback: 'table', degradations: validation.degradations };
  }

  if (spec.type === 'table') {
    const svg = wrap(titleText(spec, width) + renderTable(spec, rows, width, height), width, height);
    return { svg, fallback: null, degradations: validation.degradations };
  }

  const series = toSeries(spec, rows);
  let inner: string;
  switch (spec.type) {
    case 'bar':
      inner = renderBars(series, width, height, spec.visual?.orientation === 'horizontal');
      break;
    case 'pie':
      inner = renderPie(series, width, height);
      break;
    case 'line':
    case 'area':
      inner = renderLine(series, width, height);
      break;
    default:
      // Supported by the target's capability matrix but not yet drawn natively
      // here → explicit table fallback (never a silently wrong chart).
      return {
        svg: wrap(
          titleText(spec, width) +
            renderTable(spec, rows, width, height, `Chart type "${spec.type}" rendered as table.`),
          width,
          height,
        ),
        fallback: 'table',
        degradations: validation.degradations,
      };
  }

  return {
    svg: wrap(titleText(spec, width) + inner, width, height),
    fallback: null,
    degradations: validation.degradations,
  };
}
