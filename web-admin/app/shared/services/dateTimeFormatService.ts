export interface DateTimeFormatPreferences {
  date: string;
  datetime: string;
  time: string;
}

export const DEFAULT_DATE_TIME_FORMATS: DateTimeFormatPreferences = {
  date: 'YYYY-MM-DD',
  datetime: 'YYYY-MM-DD HH:mm:ss',
  time: 'HH:mm:ss',
};
