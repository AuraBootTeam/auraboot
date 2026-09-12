import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReportDsl } from '../types';
import { fetchReportData } from './fetchReportData';

type Parameters = Record<string, string>;
type Result = {
  report: ReportDsl;
  parameters: Parameters;
  data: Record<string, Record<string, unknown>[]>;
};

/** Commit the parameter snapshot and its data together; ignore superseded responses. */
export function useReportQuery(report: ReportDsl | null, enabled = true) {
  const [draft, setDraft] = useState<Parameters>({});
  const [result, setResult] = useState<Result | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const generation = useRef(0);
  const run = useCallback(async (definition: ReportDsl, parameters: Parameters) => {
    const request = ++generation.current;
    const snapshot = { ...parameters };
    setLoading(true);
    setFailed(false);
    try {
      const data = await fetchReportData(definition, snapshot);
      if (request === generation.current)
        setResult({ report: definition, parameters: snapshot, data });
    } catch {
      if (request === generation.current) setFailed(true);
    } finally {
      if (request === generation.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!report || !enabled) return;
    const defaults: Parameters = {};
    for (const parameter of report.parameters ?? []) {
      if (parameter.type !== 'date-range' && parameter.defaultValue !== undefined)
        defaults[parameter.name] = parameter.defaultValue;
    }
    setDraft(defaults);
    void run(report, defaults);
    return () => {
      generation.current++;
    };
  }, [report, enabled, run]);

  const apply = useCallback(() => {
    if (report && enabled) void run(report, draft);
  }, [report, enabled, draft, run]);
  const current = result?.report === report ? result : null;
  return {
    draft,
    setDraft,
    apply,
    loading,
    failed,
    dataSets: current?.data ?? {},
    appliedParameters: current?.parameters ?? {},
    hasResult: !!current,
    canExport: !!current && !loading,
  };
}

export type ReportQuery = ReturnType<typeof useReportQuery>;
