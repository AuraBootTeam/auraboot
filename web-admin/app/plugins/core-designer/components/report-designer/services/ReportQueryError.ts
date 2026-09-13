export class ReportQueryError extends Error {
  constructor(
    public readonly kind: 'access' | 'parameters',
    message: string,
  ) {
    super(message);
    this.name = 'ReportQueryError';
  }
}

export function requireReportResponse(response: Response): void {
  if (response.status === 401 || response.status === 403)
    throw new ReportQueryError('access', 'Report data access denied');
  if (!response.ok) throw new Error(`Report query failed: ${response.status}`);
}
