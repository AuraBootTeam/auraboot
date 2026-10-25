import { resolveRecordParams } from '~/framework/meta/rendering/blocks/ChartBlockRenderer';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { RecordData } from './detailPageTypes';
import { getByDataPath, readDetailRecordField } from './detailRecordRequests';

/**
 * Extract a nested array for a block's `dataPath` from the raw detail API payload
 * (e.g. `priceComponents` / `quotaTemplates` siblings of the master record in the DTO).
 * Returns [] when the path is missing or not an array.
 */
export function extractBlockDataRows(rawData: any, dataPath?: string): any[] {
  if (rawData == null || !dataPath) return [];
  const val = getByDataPath(rawData, dataPath);
  return Array.isArray(val) ? val : [];
}

/**
 * Resolve `${record.*}` / `${recordPid}` templates in a chart block's namedQuery dataSource
 * params against the current record, returning a cloned block with resolved `parameters`.
 * The chart's own runtime context does not carry the detail record, so this must happen here
 * (where recordData/recordPid are available) before the block is dispatched to BlockRenderer.
 * Non-chart blocks and charts without templated params pass through unchanged.
 */
export function resolveChartBlockRecordParams(
  block: BlockConfig,
  recordData: RecordData,
  recordPid: string,
): BlockConfig {
  if (block.blockType !== 'chart') return block;
  const chartConfig = (block as any).chartConfig;
  const ds = chartConfig?.dataSource;
  const rawParams = ds?.params ?? ds?.parameters;
  if (!ds || !rawParams || typeof rawParams !== 'object') return block;
  const resolved = resolveRecordParams(rawParams, recordData as Record<string, unknown>, recordPid);
  return {
    ...block,
    chartConfig: {
      ...chartConfig,
      dataSource: { ...ds, parameters: resolved },
    },
  } as BlockConfig;
}

export function injectDetailRecordValueIntoCustomBlock(
  block: BlockConfig,
  recordData: RecordData,
): BlockConfig {
  if (block.blockType !== 'custom') return block;
  const props = ((block as any).props || {}) as Record<string, unknown>;
  const propsWithRecord = {
    ...props,
    record: props.record ?? recordData,
  };
  if (props.value !== undefined) {
    return {
      ...block,
      props: propsWithRecord,
    } as BlockConfig;
  }
  const valueField = typeof props.valueField === 'string' ? props.valueField.trim() : '';
  if (!valueField) {
    return {
      ...block,
      props: propsWithRecord,
    } as BlockConfig;
  }
  const value = readDetailRecordField(recordData, valueField);
  if (value === undefined) {
    return {
      ...block,
      props: propsWithRecord,
    } as BlockConfig;
  }
  return {
    ...block,
    props: {
      ...propsWithRecord,
      value,
    },
  } as BlockConfig;
}

export function prepareDetailRuntimeBlock(
  block: BlockConfig,
  recordData: RecordData,
  recordPid: string,
): BlockConfig {
  const valueBoundBlock = injectDetailRecordValueIntoCustomBlock(block, recordData);
  const recordBoundBlock =
    valueBoundBlock.blockType === 'stage-rail' || valueBoundBlock.blockType === 'toolbar'
      ? ({ ...valueBoundBlock, record: recordData, recordPid } as BlockConfig)
      : valueBoundBlock;
  return resolveChartBlockRecordParams(recordBoundBlock, recordData, recordPid);
}
