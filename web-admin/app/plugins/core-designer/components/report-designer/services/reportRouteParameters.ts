import type { ReportDsl } from '../types';

/** Only the definition can opt in to URL-bound inputs; unrelated keys are ignored. */
export function reportWithRouteParameters(report: ReportDsl | null, search: string): ReportDsl | null {
  if (!report || !report.parameters?.some(parameter => parameter.inputSource === 'route')) return report;
  const query = new URLSearchParams(search);
  return {
    ...report,
    parameters: report.parameters.map(parameter => parameter.inputSource === 'route'
      ? { ...parameter, defaultValue: query.getAll(parameter.name).length === 1 ? query.get(parameter.name)! : '' }
      : parameter),
  };
}
