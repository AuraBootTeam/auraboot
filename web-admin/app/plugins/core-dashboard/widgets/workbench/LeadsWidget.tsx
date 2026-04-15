/**
 * LeadsWidget — List of recent CRM leads.
 *
 * Data source: GET /crm_lead/list (dynamic controller)
 * Sorted by created_at desc, top N items.
 */

import React, { useEffect, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface LeadRecord {
  id: string;
  crm_lead_company?: string;
  crm_lead_contact_name?: string;
  crm_lead_contact_email?: string;
  crm_lead_status?: string;
  created_at?: string;
}

interface LeadListResponse {
  records: LeadRecord[];
  total: number;
}

interface LeadsWidgetProps {
  title?: string;
  maxItems?: number;
  className?: string;
}

const STATUS_STYLES: Record<string, { bg: string; text: string }> = {
  new: { bg: 'bg-blue-50', text: 'text-blue-700' },
  following_up: { bg: 'bg-amber-50', text: 'text-amber-700' },
  converted: { bg: 'bg-green-50', text: 'text-green-700' },
  disqualified: { bg: 'bg-gray-100', text: 'text-gray-500' },
};

function formatRelativeTime(dateStr: string, t: (key: string, params?: Record<string, unknown>, fallback?: string) => string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const hours = Math.floor(diff / 3600000);
  if (hours < 1) return t('workbench.leads.justNow', {}, 'Just now');
  if (hours < 24) return t('workbench.leads.hoursAgo', { hours }, `${hours}h ago`);
  const days = Math.floor(hours / 24);
  if (days < 7) return t('workbench.leads.daysAgo', { days }, `${days}d ago`);
  return date.toLocaleDateString();
}

export function LeadsWidget({ title, maxItems = 5, className = '' }: LeadsWidgetProps) {
  const { t } = useI18n();
  const [leads, setLeads] = useState<LeadRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  // crmUnavailable: true when the CRM module is not installed (API error, e.g. table does not exist)
  const [crmUnavailable, setCrmUnavailable] = useState(false);

  const resolvedTitle = title || t('workbench.leads.title', {}, 'New Leads');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const result = await get<LeadListResponse>(
          `/crm_lead/list?pageNum=1&pageSize=${maxItems}&sortField=created_at&sortOrder=desc`,
        );
        if (!cancelled && result.code === '0' && result.data) {
          setLeads(result.data.records || []);
          setTotal(result.data.total || 0);
        } else if (!cancelled) {
          setError(true);
        }
      } catch {
        if (!cancelled) {
          // Network/server error most likely means CRM table is missing (OSS without CRM module)
          setError(true);
          setCrmUnavailable(true);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [maxItems]);

  const handleRowClick = (lead: LeadRecord) => {
    window.location.href = `/crm_lead/${lead.id}`;
  };

  // --- Loading ---
  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="leads-skeleton">
        <div className="mb-3 flex items-center justify-between px-1">
          <div className="h-4 w-24 animate-pulse rounded bg-gray-100" />
          <div className="h-3 w-16 animate-pulse rounded bg-gray-100" />
        </div>
        <div className="flex-1 space-y-2">
          {Array.from({ length: maxItems }, (_, i) => (
            <div key={i} className="flex items-center gap-3 rounded-lg border border-gray-100 p-3">
              <div className="h-9 w-9 animate-pulse rounded-[10px] bg-gray-100" />
              <div className="flex-1 space-y-1.5">
                <div className="h-3.5 w-32 animate-pulse rounded bg-gray-100" />
                <div className="h-2.5 w-24 animate-pulse rounded bg-gray-100" />
              </div>
              <div className="h-5 w-14 animate-pulse rounded-full bg-gray-100" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // --- CRM unavailable (module not installed) ---
  if (crmUnavailable) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="leads-crm-unavailable">
        <div className="mb-3 flex items-center justify-between px-1">
          <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        </div>
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="mb-1 text-2xl">{'📦'}</span>
          <span className="text-sm font-medium text-gray-500">
            {t('workbench.leads.crmUnavailable', {}, 'CRM module not installed')}
          </span>
          <span className="mt-1 text-xs text-gray-400">
            {t('workbench.leads.crmUnavailableHint', {}, 'Install the CRM plugin to track leads')}
          </span>
        </div>
      </div>
    );
  }

  // --- Empty / Error ---
  if (error || leads.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="leads-empty">
        <div className="mb-3 flex items-center justify-between px-1">
          <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        </div>
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="mb-1 text-2xl">{'\uD83C\uDFAF'}</span>
          <span className="text-sm">
            {t('workbench.leads.empty', {}, 'No leads yet')}
          </span>
        </div>
      </div>
    );
  }

  // --- Data ---
  return (
    <div className={`flex h-full flex-col ${className}`} data-testid="leads-widget">
      {/* Header */}
      <div className="mb-3 flex items-center justify-between px-1">
        <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        {total > maxItems && (
          <a
            href="/crm_lead"
            className="text-[11px] text-blue-500 hover:text-blue-600"
          >
            {t('workbench.leads.viewAll', {}, 'View All')} &rarr;
          </a>
        )}
      </div>

      {/* Lead rows */}
      <div className="flex-1 space-y-2 overflow-y-auto">
        {leads.map((lead) => {
          const status = lead.crm_lead_status || 'new';
          const statusStyle = STATUS_STYLES[status] || STATUS_STYLES.new;
          const timeStr = lead.created_at
            ? formatRelativeTime(lead.created_at, t)
            : '';

          return (
            <button
              key={lead.id}
              type="button"
              onClick={() => handleRowClick(lead)}
              className="flex w-full cursor-pointer items-center gap-3 rounded-lg border border-gray-100 bg-white p-3 text-left transition-colors hover:border-blue-200 hover:bg-blue-50/30"
            >
              {/* Icon */}
              <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-[10px] bg-amber-50 text-base">
                {'\uD83C\uDFAF'}
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1">
                <div className="truncate text-[13px] font-medium text-gray-900">
                  {lead.crm_lead_company || lead.crm_lead_contact_name || t('workbench.leads.unnamed', {}, 'Unnamed Lead')}
                </div>
                <div className="mt-0.5 flex items-center gap-2 text-[11px] text-gray-400">
                  {lead.crm_lead_contact_name && lead.crm_lead_company && (
                    <span className="truncate">{lead.crm_lead_contact_name}</span>
                  )}
                  {timeStr && <span>{timeStr}</span>}
                </div>
              </div>

              {/* Status badge */}
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${statusStyle.bg} ${statusStyle.text}`}
              >
                {t(`workbench.leads.status.${status}`, {}, status)}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
