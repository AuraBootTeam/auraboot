/**
 * City-friendly timezone name mapping.
 * Maps common IANA timezone identifiers to localized display names and search terms.
 */

export interface TimezoneEntry {
  iana: string;
  displayName: string;
  utcOffset: string;
  searchTerms: string[];
}

/** ~50 most commonly used timezones with Chinese and English search terms. */
export const TIMEZONE_ENTRIES: TimezoneEntry[] = [
  // China
  {
    iana: 'Asia/Shanghai',
    displayName: '北京/上海',
    utcOffset: 'UTC+8',
    searchTerms: [
      '北京',
      '上海',
      '重庆',
      '乌鲁木齐',
      'Beijing',
      'Shanghai',
      'Chongqing',
      'China',
      'cst',
    ],
  },
  {
    iana: 'Asia/Hong_Kong',
    displayName: '香港',
    utcOffset: 'UTC+8',
    searchTerms: ['香港', 'Hong Kong', 'hkt'],
  },
  {
    iana: 'Asia/Taipei',
    displayName: '台北',
    utcOffset: 'UTC+8',
    searchTerms: ['台北', 'Taipei', 'Taiwan', 'cst'],
  },
  {
    iana: 'Asia/Macau',
    displayName: '澳门',
    utcOffset: 'UTC+8',
    searchTerms: ['澳门', 'Macau', 'Macao', 'mut'],
  },
  // Japan
  {
    iana: 'Asia/Tokyo',
    displayName: '东京',
    utcOffset: 'UTC+9',
    searchTerms: ['东京', '大阪', 'Tokyo', 'Osaka', 'Japan', 'jst'],
  },
  // Korea
  {
    iana: 'Asia/Seoul',
    displayName: '首尔',
    utcOffset: 'UTC+9',
    searchTerms: ['首尔', '釜山', 'Seoul', 'Busan', 'Korea', 'kst'],
  },
  // Singapore / Malaysia
  {
    iana: 'Asia/Singapore',
    displayName: '新加坡',
    utcOffset: 'UTC+8',
    searchTerms: ['新加坡', '吉隆坡', 'Singapore', 'Kuala Lumpur', 'Malaysia', 'sgt', 'myt'],
  },
  // India
  {
    iana: 'Asia/Kolkata',
    displayName: '孟买/加尔各答',
    utcOffset: 'UTC+5:30',
    searchTerms: ['孟买', '加尔各答', '新德里', 'Mumbai', 'Kolkata', 'New Delhi', 'India', 'ist'],
  },
  // Pakistan
  {
    iana: 'Asia/Karachi',
    displayName: '卡拉奇',
    utcOffset: 'UTC+5',
    searchTerms: ['卡拉奇', 'Karachi', 'Pakistan', 'pkt'],
  },
  // Bangladesh
  {
    iana: 'Asia/Dhaka',
    displayName: '达卡',
    utcOffset: 'UTC+6',
    searchTerms: ['达卡', 'Dhaka', 'Bangladesh', 'bst'],
  },
  // Thailand / Vietnam
  {
    iana: 'Asia/Bangkok',
    displayName: '曼谷/河内',
    utcOffset: 'UTC+7',
    searchTerms: [
      '曼谷',
      '河内',
      '胡志明',
      'Bangkok',
      'Hanoi',
      'Ho Chi Minh',
      'Thailand',
      'Vietnam',
      'ict',
    ],
  },
  // Indonesia
  {
    iana: 'Asia/Jakarta',
    displayName: '雅加达',
    utcOffset: 'UTC+7',
    searchTerms: ['雅加达', 'Jakarta', 'Indonesia', 'wib'],
  },
  // Middle East
  {
    iana: 'Asia/Dubai',
    displayName: '迪拜',
    utcOffset: 'UTC+4',
    searchTerms: ['迪拜', '阿布扎比', 'Dubai', 'Abu Dhabi', 'uae', 'Gulf', 'gst'],
  },
  {
    iana: 'Asia/Riyadh',
    displayName: '利雅得',
    utcOffset: 'UTC+3',
    searchTerms: ['利雅得', 'Riyadh', 'Saudi Arabia', 'ast'],
  },
  {
    iana: 'Asia/Tehran',
    displayName: '德黑兰',
    utcOffset: 'UTC+3:30',
    searchTerms: ['德黑兰', 'Tehran', 'Iran', 'irst'],
  },
  {
    iana: 'Asia/Jerusalem',
    displayName: '耶路撒冷',
    utcOffset: 'UTC+2',
    searchTerms: ['耶路撒冷', 'Jerusalem', 'Israel', 'ist'],
  },
  // Europe
  {
    iana: 'Europe/London',
    displayName: '伦敦',
    utcOffset: 'GMT+0',
    searchTerms: ['伦敦', 'London', 'UK', 'England', 'Britain', 'gmt', 'bst'],
  },
  {
    iana: 'Europe/Paris',
    displayName: '巴黎/柏林',
    utcOffset: 'UTC+1',
    searchTerms: [
      '巴黎',
      '柏林',
      '罗马',
      '马德里',
      'Paris',
      'Berlin',
      'Rome',
      'Madrid',
      'cet',
      'cest',
    ],
  },
  {
    iana: 'Europe/Amsterdam',
    displayName: '阿姆斯特丹',
    utcOffset: 'UTC+1',
    searchTerms: ['阿姆斯特丹', 'Amsterdam', 'Netherlands', 'Holland', 'cet'],
  },
  {
    iana: 'Europe/Stockholm',
    displayName: '斯德哥尔摩',
    utcOffset: 'UTC+1',
    searchTerms: ['斯德哥尔摩', 'Stockholm', 'Sweden', 'Norway', 'Denmark', 'cet'],
  },
  {
    iana: 'Europe/Helsinki',
    displayName: '赫尔辛基',
    utcOffset: 'UTC+2',
    searchTerms: ['赫尔辛基', '雅典', 'Helsinki', 'Athens', 'Finland', 'Greece', 'eet', 'eest'],
  },
  {
    iana: 'Europe/Istanbul',
    displayName: '伊斯坦布尔',
    utcOffset: 'UTC+3',
    searchTerms: ['伊斯坦布尔', '安卡拉', 'Istanbul', 'Ankara', 'Turkey', 'trt'],
  },
  {
    iana: 'Europe/Moscow',
    displayName: '莫斯科',
    utcOffset: 'UTC+3',
    searchTerms: ['莫斯科', '圣彼得堡', 'Moscow', 'St. Petersburg', 'Russia', 'msk'],
  },
  // Africa
  {
    iana: 'Africa/Cairo',
    displayName: '开罗',
    utcOffset: 'UTC+2',
    searchTerms: ['开罗', 'Cairo', 'Egypt', 'eet'],
  },
  {
    iana: 'Africa/Johannesburg',
    displayName: '约翰内斯堡',
    utcOffset: 'UTC+2',
    searchTerms: ['约翰内斯堡', '开普敦', 'Johannesburg', 'Cape Town', 'South Africa', 'sast'],
  },
  {
    iana: 'Africa/Lagos',
    displayName: '拉各斯',
    utcOffset: 'UTC+1',
    searchTerms: ['拉各斯', '阿克拉', 'Lagos', 'Accra', 'Nigeria', 'Ghana', 'wat'],
  },
  {
    iana: 'Africa/Nairobi',
    displayName: '内罗毕',
    utcOffset: 'UTC+3',
    searchTerms: ['内罗毕', 'Nairobi', 'Kenya', 'eat'],
  },
  // USA & Canada
  {
    iana: 'America/New_York',
    displayName: '纽约',
    utcOffset: 'UTC-5',
    searchTerms: [
      '纽约',
      '波士顿',
      '迈阿密',
      '华盛顿',
      'New York',
      'Boston',
      'Miami',
      'Washington',
      'DC',
      'est',
      'edt',
      'Eastern',
    ],
  },
  {
    iana: 'America/Chicago',
    displayName: '芝加哥',
    utcOffset: 'UTC-6',
    searchTerms: [
      '芝加哥',
      '休斯顿',
      '达拉斯',
      'Chicago',
      'Houston',
      'Dallas',
      'cst',
      'cdt',
      'Central',
    ],
  },
  {
    iana: 'America/Denver',
    displayName: '丹佛',
    utcOffset: 'UTC-7',
    searchTerms: ['丹佛', 'Denver', 'Phoenix', 'mst', 'mdt', 'Mountain'],
  },
  {
    iana: 'America/Los_Angeles',
    displayName: '洛杉矶',
    utcOffset: 'UTC-8',
    searchTerms: [
      '洛杉矶',
      '旧金山',
      '西雅图',
      '拉斯维加斯',
      'Los Angeles',
      'San Francisco',
      'Seattle',
      'Las Vegas',
      'pst',
      'pdt',
      'Pacific',
    ],
  },
  {
    iana: 'America/Anchorage',
    displayName: '安克雷奇',
    utcOffset: 'UTC-9',
    searchTerms: ['安克雷奇', '阿拉斯加', 'Anchorage', 'Alaska', 'akst', 'akdt'],
  },
  {
    iana: 'Pacific/Honolulu',
    displayName: '檀香山',
    utcOffset: 'UTC-10',
    searchTerms: ['檀香山', '夏威夷', 'Honolulu', 'Hawaii', 'hst'],
  },
  {
    iana: 'America/Toronto',
    displayName: '多伦多',
    utcOffset: 'UTC-5',
    searchTerms: ['多伦多', '渥太华', 'Toronto', 'Ottawa', 'Canada', 'Eastern', 'est'],
  },
  {
    iana: 'America/Vancouver',
    displayName: '温哥华',
    utcOffset: 'UTC-8',
    searchTerms: ['温哥华', 'Vancouver', 'Canada', 'pst'],
  },
  // Central & South America
  {
    iana: 'America/Mexico_City',
    displayName: '墨西哥城',
    utcOffset: 'UTC-6',
    searchTerms: ['墨西哥城', 'Mexico City', 'Mexico', 'cst'],
  },
  {
    iana: 'America/Bogota',
    displayName: '波哥大',
    utcOffset: 'UTC-5',
    searchTerms: ['波哥大', 'Bogota', 'Colombia', 'cot'],
  },
  {
    iana: 'America/Sao_Paulo',
    displayName: '圣保罗',
    utcOffset: 'UTC-3',
    searchTerms: ['圣保罗', '里约热内卢', 'Sao Paulo', 'Rio de Janeiro', 'Brazil', 'brt'],
  },
  {
    iana: 'America/Argentina/Buenos_Aires',
    displayName: '布宜诺斯艾利斯',
    utcOffset: 'UTC-3',
    searchTerms: ['布宜诺斯艾利斯', 'Buenos Aires', 'Argentina', 'art'],
  },
  // Australia & Pacific
  {
    iana: 'Australia/Perth',
    displayName: '珀斯',
    utcOffset: 'UTC+8',
    searchTerms: ['珀斯', 'Perth', 'Australia', 'awst'],
  },
  {
    iana: 'Australia/Adelaide',
    displayName: '阿德莱德',
    utcOffset: 'UTC+9:30',
    searchTerms: ['阿德莱德', 'Adelaide', 'Australia', 'acst', 'acdt'],
  },
  {
    iana: 'Australia/Sydney',
    displayName: '悉尼',
    utcOffset: 'UTC+10',
    searchTerms: [
      '悉尼',
      '墨尔本',
      '堪培拉',
      'Sydney',
      'Melbourne',
      'Canberra',
      'Australia',
      'aedt',
      'aest',
    ],
  },
  {
    iana: 'Pacific/Auckland',
    displayName: '奥克兰',
    utcOffset: 'UTC+12',
    searchTerms: ['奥克兰', '惠灵顿', 'Auckland', 'Wellington', 'New Zealand', 'nzst', 'nzdt'],
  },
  // UTC
  {
    iana: 'utc',
    displayName: 'UTC (协调世界时)',
    utcOffset: 'UTC+0',
    searchTerms: ['utc', 'gmt', '世界时', 'Universal', 'Coordinated'],
  },
];

/** Map from IANA to entry for O(1) lookup. */
const TIMEZONE_MAP = new Map<string, TimezoneEntry>(TIMEZONE_ENTRIES.map((e) => [e.iana, e]));

/**
 * Get display label for a timezone.
 * Returns "北京/上海 (UTC+8)" for known timezones,
 * falls back to "Asia/Shanghai (GMT+8)" using Intl for unknown ones.
 */
export function getTimezoneLabel(iana: string): string {
  const entry = TIMEZONE_MAP.get(iana);
  if (entry) return `${entry.displayName} (${entry.utcOffset})`;
  // Fallback: compute offset dynamically via Intl
  try {
    const offset =
      new Intl.DateTimeFormat('en', { timeZone: iana, timeZoneName: 'shortOffset' })
        .formatToParts(new Date())
        .find((p) => p.type === 'timeZoneName')?.value ?? '';
    return offset ? `${iana} (${offset})` : iana;
  } catch {
    return iana;
  }
}

/**
 * Search known timezones by query string.
 * Matches against IANA code, display name, and search terms (case-insensitive).
 * Returns all entries when query is empty.
 */
export function searchTimezones(query: string): TimezoneEntry[] {
  if (!query.trim()) return TIMEZONE_ENTRIES;
  const q = query.toLowerCase();
  return TIMEZONE_ENTRIES.filter(
    (e) =>
      e.iana.toLowerCase().includes(q) ||
      e.displayName.toLowerCase().includes(q) ||
      e.searchTerms.some((t) => t.toLowerCase().includes(q)),
  );
}
