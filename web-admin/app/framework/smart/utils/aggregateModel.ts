import type { ChartDataSource } from '../types/chart';

type ModelIdentity = Pick<ChartDataSource, 'modelCode' | 'semanticModelCode'>;

// Presence marks semantic mode, including an incomplete picker selection.
// Never fall back to a retained raw model when semantic selection is empty.
export function aggregateModelField(source: ModelIdentity): 'modelCode' | 'semanticModelCode' {
  return source.semanticModelCode !== undefined ? 'semanticModelCode' : 'modelCode';
}

export function hasAggregateModel(source: ModelIdentity): boolean {
  const code = source[aggregateModelField(source)];
  return typeof code === 'string' && code.trim().length > 0;
}
