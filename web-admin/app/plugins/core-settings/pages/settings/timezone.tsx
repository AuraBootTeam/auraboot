import { useState, useEffect, useCallback } from 'react';
import { fetchResult } from '~/services/http-client/HttpClient';
import { useToken as useAuthToken } from '~/contexts/AuthContext';
import { useTheme } from '~/contexts/ThemeContext';
import { ClockIcon, GlobeAltIcon, MagnifyingGlassIcon } from '@heroicons/react/24/outline';

interface TimezoneInfo {
  id: string;
  displayName: string;
  utcOffset: string;
  offsetSeconds: number;
}

export default function TimezonePage() {
  const token = useAuthToken();
  const { isDark } = useTheme();
  const [timezones, setTimezones] = useState<TimezoneInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [selectedTimezone, setSelectedTimezone] = useState<string>('');

  const fetchTimezones = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const result = await fetchResult<TimezoneInfo[]>('/api/admin/exchange-rates/timezones', {
        method: 'get',
        token: token ?? undefined,
      });
      if (result.code === '0' && result.data) {
        setTimezones(result.data);
      }
    } catch (err) {
      console.error('Failed to fetch timezones:', err);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    fetchTimezones();
    // Detect user's timezone
    const userTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    setSelectedTimezone(userTz);
  }, [fetchTimezones]);

  const filteredTimezones = timezones.filter(
    (tz) =>
      tz.id.toLowerCase().includes(search.toLowerCase()) ||
      tz.displayName.toLowerCase().includes(search.toLowerCase()) ||
      tz.utcOffset.includes(search),
  );

  // Group by offset region
  const groupedTimezones = filteredTimezones.reduce<Record<string, TimezoneInfo[]>>((acc, tz) => {
    const region = tz.id.split('/')[0] || 'Other';
    if (!acc[region]) acc[region] = [];
    acc[region].push(tz);
    return acc;
  }, {});

  const cardBg = isDark ? 'bg-gray-800' : 'bg-white';
  const borderColor = isDark ? 'border-gray-700' : 'border-gray-200';
  const textPrimary = isDark ? 'text-white' : 'text-gray-900';
  const textSecondary = isDark ? 'text-gray-400' : 'text-gray-500';
  const inputBg = isDark
    ? 'bg-gray-700 text-white border-gray-600'
    : 'bg-white text-gray-900 border-gray-300';

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <GlobeAltIcon className="h-8 w-8 text-indigo-500" />
        <div>
          <h1 className={`text-2xl font-bold ${textPrimary}`}>Timezone Settings</h1>
          <p className={textSecondary}>
            Configure your organization's timezone. All timestamps are stored in UTC and displayed
            in your local timezone.
          </p>
        </div>
      </div>

      {/* Current Timezone */}
      <div className={`${cardBg} rounded-lg border ${borderColor} p-6`}>
        <h2 className={`mb-4 text-lg font-semibold ${textPrimary}`}>
          <ClockIcon className="mr-2 inline h-5 w-5" />
          Current Timezone
        </h2>
        <div className="flex items-center gap-4">
          <div className={`font-mono text-xl ${textPrimary}`}>{selectedTimezone}</div>
          <div className={`text-sm ${textSecondary}`}>
            {new Date().toLocaleString('en-US', {
              timeZone: selectedTimezone,
              timeZoneName: 'long',
            })}
          </div>
        </div>
        <p className={`mt-2 text-sm ${textSecondary}`}>
          Detected from your browser. To change the tenant timezone, update it in tenant settings.
        </p>
      </div>

      {/* Timezone Search and List */}
      <div className={`${cardBg} rounded-lg border ${borderColor} overflow-hidden`}>
        <div
          className="flex items-center gap-4 border-b px-6 py-4"
          style={{ borderColor: isDark ? '#374151' : '#e5e7eb' }}
        >
          <h2 className={`text-lg font-semibold ${textPrimary}`}>Available Timezones</h2>
          <div className="relative max-w-md flex-1">
            <MagnifyingGlassIcon
              className={`absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 ${textSecondary}`}
            />
            <input
              type="text"
              placeholder="Search timezones (e.g. Asia/Shanghai, UTC+08:00)..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className={`w-full rounded-md py-2 pr-4 pl-10 ${inputBg}`}
            />
          </div>
          <span className={`text-sm ${textSecondary}`}>{filteredTimezones.length} results</span>
        </div>

        {loading ? (
          <div className="flex h-32 items-center justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-indigo-500" />
          </div>
        ) : (
          <div className="max-h-[500px] overflow-y-auto">
            {Object.entries(groupedTimezones)
              .sort()
              .map(([region, tzList]) => (
                <div key={region}>
                  <div
                    className={`sticky top-0 px-6 py-2 text-xs font-semibold tracking-wider uppercase ${
                      isDark ? 'bg-gray-700/80 text-gray-300' : 'bg-gray-100 text-gray-600'
                    }`}
                  >
                    {region} ({tzList.length})
                  </div>
                  {tzList.map((tz) => (
                    <div
                      key={tz.id}
                      className={`flex items-center justify-between border-b px-6 py-3 ${borderColor} ${
                        tz.id === selectedTimezone
                          ? isDark
                            ? 'bg-indigo-900/20'
                            : 'bg-indigo-50'
                          : isDark
                            ? 'hover:bg-gray-700/30'
                            : 'hover:bg-gray-50'
                      } cursor-pointer`}
                      onClick={() => setSelectedTimezone(tz.id)}
                    >
                      <div>
                        <span className={`font-medium ${textPrimary}`}>{tz.id}</span>
                        <span className={`ml-3 text-sm ${textSecondary}`}>{tz.displayName}</span>
                      </div>
                      <div className="flex items-center gap-3">
                        <span
                          className={`rounded px-2 py-0.5 font-mono text-sm ${
                            isDark ? 'bg-gray-700 text-gray-300' : 'bg-gray-100 text-gray-700'
                          }`}
                        >
                          {tz.utcOffset}
                        </span>
                        {tz.id === selectedTimezone && (
                          <span className="text-xs font-medium text-indigo-600 dark:text-indigo-400">
                            Selected
                          </span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              ))}
          </div>
        )}
      </div>
    </div>
  );
}
