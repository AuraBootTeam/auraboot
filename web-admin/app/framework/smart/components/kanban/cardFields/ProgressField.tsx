/**
 * ProgressField
 *
 * Renders an inline mini progress bar with a percentage label. Clamps the
 * displayed bar width to [0, 100]. Falls back to em-dash for non-numeric input.
 */

export interface ProgressFieldProps {
  value: number | string | null | undefined;
  /** Maximum value used to compute the percentage. Defaults to 100. */
  max?: number;
}

export function ProgressField({ value, max = 100 }: ProgressFieldProps) {
  const num = typeof value === 'string' ? Number(value) : value;
  if (num === null || num === undefined || Number.isNaN(num)) {
    return <span data-field-type="progress">—</span>;
  }
  const numeric = num as number;
  const safeMax = max && max > 0 ? max : 100;
  const pct = Math.min(100, Math.max(0, (numeric / safeMax) * 100));
  return (
    <span data-field-type="progress" className="inline-flex items-center gap-1">
      <span className="inline-block h-1.5 w-12 overflow-hidden rounded bg-gray-200">
        <span
          data-field-type-bar="progress"
          className="block h-full bg-blue-500"
          style={{ width: `${pct}%` }}
        />
      </span>
      <span className="text-xs">{Math.round(pct)}%</span>
    </span>
  );
}
