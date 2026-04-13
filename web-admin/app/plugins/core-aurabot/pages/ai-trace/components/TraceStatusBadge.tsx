interface Props {
  status: string;
}

const STATUS_STYLES: Record<string, string> = {
  success: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
  ERROR: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
  in_progress: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  pending: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  cancelled: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
};

const STATUS_ICONS: Record<string, string> = {
  success: '\u2713',
  ERROR: '\u2717',
  in_progress: '\u25CF',
  pending: '\u25CB',
  cancelled: '\u2014',
};

export function TraceStatusBadge({ status }: Props) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[status] || 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300'}`}
    >
      <span>{STATUS_ICONS[status] || '\u25CF'}</span>
      {status}
    </span>
  );
}
