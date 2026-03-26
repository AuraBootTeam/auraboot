/**
 * Resource Select Service
 *
 * Provides fetch helpers for cross-designer resource selection.
 * Each function returns a list of ResourceOption objects for use
 * with BaseResourceSelect.
 */

import { fetchResult } from '~/services/http-client';

interface ResourceOption {
  label: string;
  value: string;
  description?: string;
}

interface PageRecord {
  records: Array<{
    title?: string;
    name?: string;
    pageType: string;
    code?: string;
    pid: string;
  }>;
}

interface DashboardRecord {
  records: Array<{
    title: string;
    code?: string;
    pid: string;
    status: string;
  }>;
}

interface ProcessRecord {
  records: Array<{
    name: string;
    key: string;
    version: number;
  }>;
}

interface AutomationRecord {
  records: Array<{
    name: string;
    pid: string;
    status: string;
  }>;
}

interface CommandRecord {
  records: Array<{
    displayName?: string;
    code: string;
    type: string;
  }>;
}

export async function fetchPageOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<PageRecord>('/api/pages?size=100');
    return (result?.data?.records || []).map((p) => ({
      label: `${p.title || p.name} (${p.pageType})`,
      value: p.code || p.pid,
      description: p.pageType,
    }));
  } catch {
    return [];
  }
}

export async function fetchDashboardOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<DashboardRecord>('/api/dashboards?size=100');
    return (result?.data?.records || []).map((d) => ({
      label: `${d.title} (${d.code || d.pid})`,
      value: d.code || d.pid,
      description: d.status,
    }));
  } catch {
    return [];
  }
}

export async function fetchProcessOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<ProcessRecord>('/api/bpm/process-definitions?size=100');
    return (result?.data?.records || []).map((p) => ({
      label: `${p.name} (${p.key})`,
      value: p.key,
      description: `v${p.version}`,
    }));
  } catch {
    return [];
  }
}

export async function fetchAutomationOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<AutomationRecord>('/api/automations?size=100');
    return (result?.data?.records || []).map((a) => ({
      label: a.name,
      value: a.pid,
      description: a.status,
    }));
  } catch {
    return [];
  }
}

export async function fetchCommandOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<CommandRecord>('/api/meta/commands?size=200');
    return (result?.data?.records || []).map((c) => ({
      label: `${c.displayName || c.code} (${c.code})`,
      value: c.code,
      description: c.type,
    }));
  } catch {
    return [];
  }
}
