export type BootstrapStatus = {
  initialized: boolean;
  inProgress: boolean;
  mode?: string;
  missingParts: string[];
  reason?: string;
};

const BFF_URL = process.env.BFF_INTERNAL_URL || 'http://127.0.0.1:6443';

export async function fetchBootstrapStatus(): Promise<BootstrapStatus | null> {
  try {
    const res = await fetch(`${BFF_URL}/api/bootstrap/status`);
    if (!res.ok) return null;
    const json = await res.json();
    if (json?.code !== '0' || !json?.data) return null;
    return {
      initialized: Boolean(json.data.initialized),
      inProgress: Boolean(json.data.inProgress),
      mode: json.data.mode,
      missingParts: Array.isArray(json.data.missingParts) ? json.data.missingParts : [],
      reason: json.data.reason,
    };
  } catch {
    return null;
  }
}

export const MISSING_PART_LABELS: Record<string, string> = {
  admin_user: 'Admin account',
  default_tenant: 'Default tenant',
  system_config: 'System config flag',
};

export function describeMissingParts(parts: string[]): string {
  if (!parts.length) return '';
  return parts.map((p) => MISSING_PART_LABELS[p] ?? p).join(', ');
}
