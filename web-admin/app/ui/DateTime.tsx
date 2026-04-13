import React from 'react';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useTimezone } from '~/contexts/TimezoneContext';

dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(relativeTime);

interface DateTimeProps {
  value: string | number | Date | null | undefined;
  type?: 'datetime' | 'date' | 'time' | 'relative';
  className?: string;
}

export function DateTime({ value, type = 'datetime', className }: DateTimeProps) {
  const { timezone: tz, formats } = useTimezone();

  if (!value) return <span className={className}>—</span>;

  const effectiveTz = tz || Intl.DateTimeFormat().resolvedOptions().timeZone;
  const d = dayjs.utc(value).tz(effectiveTz);

  let display: string;
  switch (type) {
    case 'date':
      display = d.format(formats.date);
      break;
    case 'time':
      display = d.format(formats.time);
      break;
    case 'relative':
      display = d.fromNow();
      break;
    default:
      display = d.format(formats.datetime);
  }

  return (
    <time
      dateTime={dayjs.utc(value).toISOString()}
      className={className}
      title={d.format(formats.datetime)}
    >
      {display}
    </time>
  );
}
