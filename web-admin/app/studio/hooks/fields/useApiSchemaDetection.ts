import { useState, useCallback } from 'react';

export interface ApiDetectedField {
  key: string;
  sampleValue: unknown;
  inferredType: 'string' | 'number' | 'boolean' | 'datetime' | 'object' | 'array' | 'unknown';
}

export interface ApiSchemaDetectionResult {
  fields: ApiDetectedField[];
  loading: boolean;
  error: string | null;
  connected: boolean;
  recordCount: number | null;
  detect: () => Promise<void>;
}

function inferType(value: unknown): ApiDetectedField['inferredType'] {
  if (value === null || value === undefined) return 'unknown';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'string') {
    if (/^\d{4}-\d{2}-\d{2}[T ]/.test(value)) return 'datetime';
    return 'string';
  }
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'object') return 'object';
  return 'unknown';
}

interface DataSourceConfig {
  type?: string;
  endpoint?: string;
  method?: string;
}

export function useApiSchemaDetection(dataSource?: DataSourceConfig): ApiSchemaDetectionResult {
  const [fields, setFields] = useState<ApiDetectedField[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState(false);
  const [recordCount, setRecordCount] = useState<number | null>(null);

  const detect = useCallback(async () => {
    if (!dataSource?.endpoint) {
      setError('No API endpoint configured');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const separator = dataSource.endpoint.includes('?') ? '&' : '?';
      const url = `${dataSource.endpoint}${separator}pageSize=1&pageNum=1`;

      const response = await fetch(url, {
        method: dataSource.method || 'get',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
      });

      if (!response.ok) {
        throw new Error(`API returned ${response.status}`);
      }

      const json = await response.json();

      let records: Record<string, unknown>[] = [];
      let total: number | null = null;

      if (json?.data?.records && Array.isArray(json.data.records)) {
        records = json.data.records;
        total = json.data.total ?? null;
      } else if (json?.records && Array.isArray(json.records)) {
        records = json.records;
        total = json.total ?? null;
      } else if (json?.data && Array.isArray(json.data)) {
        records = json.data;
      } else if (Array.isArray(json)) {
        records = json;
      }

      if (records.length === 0) {
        setFields([]);
        setConnected(true);
        setRecordCount(0);
        setError('No records returned. Add fields manually.');
        return;
      }

      const firstRecord = records[0];
      const detected: ApiDetectedField[] = Object.entries(firstRecord).map(([key, value]) => ({
        key,
        sampleValue: value,
        inferredType: inferType(value),
      }));

      setFields(detected);
      setConnected(true);
      setRecordCount(total ?? records.length);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to connect');
      setConnected(false);
    } finally {
      setLoading(false);
    }
  }, [dataSource?.endpoint, dataSource?.method]);

  return { fields, loading, error, connected, recordCount, detect };
}
