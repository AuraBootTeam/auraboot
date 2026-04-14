/**
 * Solutions tab — browse and install industry solutions (plugin bundles).
 *
 * Restored from the previously-removed /marketplace/solutions page and adapted
 * as an embedded tab under the unified /plugins page. The outer page chrome
 * (header, background, max-width container) is now supplied by the parent
 * PluginsPage; this component only renders the sidebar + grid body.
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import {
  MagnifyingGlassIcon,
  FunnelIcon,
  PuzzlePieceIcon,
  ArrowDownTrayIcon,
  CheckCircleIcon,
  StarIcon,
} from '@heroicons/react/24/outline';

interface Solution {
  pid: string;
  code: string;
  name: string;
  nameZh?: string;
  nameEn?: string;
  description: string;
  industry: string;
  pluginCodes: string[];
  iconUrl?: string;
  coverImageUrl?: string;
  priceType: string;
  price: number;
  status: string;
  installCount: number;
  averageRating: number;
  reviewCount: number;
  featured: boolean;
  tags: string[];
  publishedAt: string;
  installed: boolean;
  pluginCount: number;
}

const INDUSTRY_LABELS: Record<string, { zh: string; en: string }> = {
  manufacturing: { zh: '制造业', en: 'Manufacturing' },
  general: { zh: '通用', en: 'General' },
  retail: { zh: '零售', en: 'Retail' },
  healthcare: { zh: '医疗', en: 'Healthcare' },
  education: { zh: '教育', en: 'Education' },
  construction: { zh: '建筑', en: 'Construction' },
};

export default function SolutionsTab() {
  const navigate = useNavigate();
  const { showErrorToast } = useToastContext();
  const { locale } = useI18n();

  const [solutions, setSolutions] = useState<Solution[]>([]);
  const [industries, setIndustries] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [selectedIndustry, setSelectedIndustry] = useState('');
  const [sort, setSort] = useState('popular');

  const fetchSolutions = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (keyword) params.set('keyword', keyword);
      if (selectedIndustry) params.set('industry', selectedIndustry);
      params.set('sort', sort);
      const res = await fetch(`/api/marketplace/solutions?${params.toString()}`);
      if (res.ok) {
        const json = await res.json();
        setSolutions(json.data ?? []);
      }
    } catch {
      showErrorToast('Failed to load solutions');
    } finally {
      setLoading(false);
    }
  }, [keyword, selectedIndustry, sort, showErrorToast]);

  const fetchIndustries = useCallback(async () => {
    try {
      const res = await fetch('/api/marketplace/solutions/industries');
      if (res.ok) {
        const json = await res.json();
        setIndustries(json.data ?? []);
      }
    } catch {
      // ignore — empty sidebar is acceptable
    }
  }, []);

  useEffect(() => {
    fetchIndustries();
  }, [fetchIndustries]);

  useEffect(() => {
    setLoading(true);
    fetchSolutions();
  }, [fetchSolutions]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    fetchSolutions();
  };

  const getDisplayName = (sol: Solution) => {
    if (locale === 'zh-CN') return sol.nameZh || sol.name;
    return sol.nameEn || sol.name;
  };

  const getIndustryLabel = (industry: string) => {
    const label = INDUSTRY_LABELS[industry];
    if (label) return locale === 'zh-CN' ? label.zh : label.en;
    return industry;
  };

  return (
    <div data-testid="solutions-tab">
      {/* Search bar */}
      <form onSubmit={handleSearch} className="mb-5 flex items-center gap-2">
        <div className="relative flex-1 max-w-md">
          <MagnifyingGlassIcon className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            placeholder={locale === 'zh-CN' ? '搜索解决方案...' : 'Search solutions...'}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            className="w-full rounded-lg border border-gray-300 py-2 pr-4 pl-9 text-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500"
          />
        </div>
      </form>

      <div className="flex gap-6">
        {/* Industry Sidebar */}
        <div className="w-48 flex-shrink-0">
          <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-500 uppercase">
            {locale === 'zh-CN' ? '行业分类' : 'Industries'}
          </h3>
          <nav className="space-y-1" data-testid="solution-industries">
            <button
              onClick={() => setSelectedIndustry('')}
              className={`w-full rounded-md px-3 py-2 text-left text-sm ${
                !selectedIndustry
                  ? 'bg-indigo-50 font-medium text-indigo-700'
                  : 'text-gray-700 hover:bg-gray-100'
              }`}
            >
              {locale === 'zh-CN' ? '全部' : 'All'}
            </button>
            {industries.map((ind) => (
              <button
                key={ind}
                onClick={() => setSelectedIndustry(ind)}
                className={`w-full rounded-md px-3 py-2 text-left text-sm ${
                  selectedIndustry === ind
                    ? 'bg-indigo-50 font-medium text-indigo-700'
                    : 'text-gray-700 hover:bg-gray-100'
                }`}
              >
                {getIndustryLabel(ind)}
              </button>
            ))}
          </nav>
        </div>

        {/* Main Content */}
        <div className="flex-1">
          {/* Sort Bar */}
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-1">
              <FunnelIcon className="h-4 w-4 text-gray-400" />
              <span className="mr-2 text-sm text-gray-500">
                {locale === 'zh-CN' ? '排序:' : 'Sort:'}
              </span>
              {[
                { key: 'popular', label: locale === 'zh-CN' ? '最热' : 'Popular' },
                { key: 'newest', label: locale === 'zh-CN' ? '最新' : 'Newest' },
                { key: 'name', label: locale === 'zh-CN' ? '名称' : 'Name' },
              ].map((opt) => (
                <button
                  key={opt.key}
                  onClick={() => setSort(opt.key)}
                  className={`rounded-full px-3 py-1 text-xs font-medium ${
                    sort === opt.key
                      ? 'bg-indigo-100 text-indigo-700'
                      : 'text-gray-500 hover:bg-gray-100'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <span className="text-sm text-gray-400">
              {solutions.length} {locale === 'zh-CN' ? '个解决方案' : 'solutions'}
            </span>
          </div>

          {/* Solution Grid */}
          {loading ? (
            <div className="flex items-center justify-center py-20">
              <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-indigo-600" />
            </div>
          ) : solutions.length === 0 ? (
            <div className="py-20 text-center text-gray-500">
              {locale === 'zh-CN' ? '暂无解决方案' : 'No solutions found'}
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
              {solutions.map((sol) => (
                <div
                  key={sol.pid}
                  className="group cursor-pointer overflow-hidden rounded-lg border border-gray-200 bg-white transition-shadow hover:shadow-md"
                  onClick={() =>
                    navigate(`/plugins/solutions/${encodeURIComponent(sol.code)}`)
                  }
                  data-testid={`solution-card-${sol.code}`}
                >
                  {/* Cover */}
                  <div className="relative h-32 bg-gradient-to-br from-indigo-500 to-purple-600">
                    {sol.coverImageUrl && (
                      <img
                        src={sol.coverImageUrl}
                        alt=""
                        className="h-full w-full object-cover"
                      />
                    )}
                    {sol.featured && (
                      <div className="absolute top-2 right-2 flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">
                        <StarIcon className="h-3 w-3" />
                        Featured
                      </div>
                    )}
                    {sol.installed && (
                      <div className="absolute top-2 left-2 flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
                        <CheckCircleIcon className="h-3 w-3" />
                        {locale === 'zh-CN' ? '已安装' : 'Installed'}
                      </div>
                    )}
                    <div className="absolute right-0 bottom-0 left-0 h-16 bg-gradient-to-t from-black/50 to-transparent" />
                    <h3 className="absolute bottom-3 left-4 text-lg font-semibold text-white drop-shadow-md">
                      {getDisplayName(sol)}
                    </h3>
                  </div>

                  {/* Body */}
                  <div className="p-4">
                    <p className="mb-3 line-clamp-2 text-sm text-gray-600">{sol.description}</p>
                    <div className="mb-3 flex items-center gap-2">
                      <span className="inline-flex items-center rounded bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">
                        {getIndustryLabel(sol.industry)}
                      </span>
                      <span className="inline-flex items-center gap-1 text-xs text-gray-500">
                        <PuzzlePieceIcon className="h-3.5 w-3.5" />
                        {sol.pluginCount} {locale === 'zh-CN' ? '个插件' : 'plugins'}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-400">
                      <span className="flex items-center gap-1">
                        <ArrowDownTrayIcon className="h-3.5 w-3.5" />
                        {sol.installCount} {locale === 'zh-CN' ? '次安装' : 'installs'}
                      </span>
                      <span
                        className={`font-medium ${sol.priceType === 'free' ? 'text-green-600' : 'text-orange-600'}`}
                      >
                        {sol.priceType === 'free'
                          ? locale === 'zh-CN'
                            ? '免费'
                            : 'Free'
                          : `$${sol.price}`}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
