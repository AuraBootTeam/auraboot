/**
 * AvatarField
 *
 * Renders a small initial badge plus the full name. Falls back to em-dash for
 * null/empty inputs.
 */

export interface AvatarFieldProps {
  value: string | null | undefined;
}

export function AvatarField({ value }: AvatarFieldProps) {
  if (!value) {
    return <span data-field-type="avatar">—</span>;
  }
  const initial = value.slice(0, 1).toUpperCase();
  return (
    <span data-field-type="avatar" className="inline-flex items-center gap-1">
      <span className="flex h-5 w-5 items-center justify-center rounded-full bg-blue-100 text-xs text-blue-700">
        {initial}
      </span>
      <span>{value}</span>
    </span>
  );
}
