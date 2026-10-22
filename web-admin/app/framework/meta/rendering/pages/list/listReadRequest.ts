/** Complete a new list query without changing the identity of a failed API read. */
export function completeListReadRequest<T extends { queryParams: Record<string, unknown> }>(
  request: T,
  options: { keyword: string; queryCode?: string | null; apiDatasource: boolean; retry: boolean },
): T {
  if (options.retry) return request;
  const queryParams = { ...request.queryParams };
  const keyword = options.keyword.trim();
  if (keyword) queryParams.keyword = keyword;
  else delete queryParams.keyword;
  if (!options.apiDatasource && options.queryCode) queryParams.queryCode = options.queryCode;
  return { ...request, queryParams };
}
