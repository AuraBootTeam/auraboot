/**
 * useDimensionLabels
 *
 * The aggregate/namedQuery response carries raw column values, so a chart grouped
 * by a dict-coded field renders the stored code as its category label — a pie of
 * `negotiation / qualification / closed_won` rather than 谈判 / 资质审查 / 赢单.
 * The dict holds the labels; nothing in the chart stack was reading it.
 *
 * This hook resolves `dimension field -> { value: label }` for the dimensions of a
 * data source:
 *
 *   - aggregate sources with a `modelCode` discover the binding automatically:
 *     field metadata carries `dictCode` on the field;
 *   - namedQuery / static sources declare it explicitly via
 *     `dataSource.dimensionDicts`, since their columns are SQL aliases with no
 *     field behind them.
 *
 * Raw values are never rewritten — only the displayed text is swapped — so linkage
 * filters, drill-down and kanban `columnOrder` keep matching on the code.
 *
 * Both lookups are cached and de-duplicated at module scope: a dashboard has many
 * widgets over the same few models and dicts, and without this a 24-widget board
 * would refetch the same field metadata and dict items on every mount.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import type { ChartDataSource } from '~/framework/smart/types/chart';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

/** field code -> dict code, for one model */
type FieldDictMap = Record<string, string>;
/** raw value -> display label, for one dict */
type DictLabelMap = Record<string, string>;
/** dimension field -> (raw value -> display label) */
export type DimensionLabelMap = Record<string, DictLabelMap>;

const fieldDictCache = new Map<string, FieldDictMap>();
const fieldDictInFlight = new Map<string, Promise<FieldDictMap>>();
const dictLabelCache = new Map<string, DictLabelMap>();
const dictLabelInFlight = new Map<string, Promise<DictLabelMap>>();

interface FieldMetaRow {
  code?: string;
  dictCode?: string | null;
}

interface DictItemRow {
  value?: string;
  label?: string;
  enabled?: boolean;
}

interface DictDataResponse {
  items?: DictItemRow[];
}

/** Reset the module caches. Test-only — a dict edited in one test must not leak into the next. */
export function __resetDimensionLabelCaches(): void {
  fieldDictCache.clear();
  fieldDictInFlight.clear();
  dictLabelCache.clear();
  dictLabelInFlight.clear();
}

/** field code -> dict code for a model, from field metadata. */
async function loadFieldDicts(modelCode: string): Promise<FieldDictMap> {
  const cached = fieldDictCache.get(modelCode);
  if (cached) return cached;

  const inFlight = fieldDictInFlight.get(modelCode);
  if (inFlight) return inFlight;

  const promise = (async () => {
    const result = await fetchResult<FieldMetaRow[]>(
      `/api/dynamic/${modelCode}/field-meta`,
      { method: 'get' },
    );
    const map: FieldDictMap = {};
    if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
      for (const field of result.data) {
        if (field?.code && field.dictCode) map[field.code] = field.dictCode;
      }
    }
    fieldDictCache.set(modelCode, map);
    return map;
  })().finally(() => fieldDictInFlight.delete(modelCode));

  fieldDictInFlight.set(modelCode, promise);
  return promise;
}

/** raw value -> label for one dict. */
async function loadDictLabels(dictCode: string): Promise<DictLabelMap> {
  const cached = dictLabelCache.get(dictCode);
  if (cached) return cached;

  const inFlight = dictLabelInFlight.get(dictCode);
  if (inFlight) return inFlight;

  const promise = (async () => {
    // `/by-code/{code}` returns the dict with `items: null`; the items live behind
    // `/data`, which is what the form/list renderers already use.
    const result = await fetchResult<DictDataResponse>(
      `/api/meta/dict/by-code/${dictCode}/data`,
      { method: 'get' },
    );
    const map: DictLabelMap = {};
    if (ResultHelper.isSuccess(result) && Array.isArray(result.data?.items)) {
      for (const item of result.data.items) {
        if (item?.value != null && item.label) map[String(item.value)] = item.label;
      }
    }
    dictLabelCache.set(dictCode, map);
    return map;
  })().finally(() => dictLabelInFlight.delete(dictCode));

  dictLabelInFlight.set(dictCode, promise);
  return promise;
}

/**
 * Resolve display labels for the dict-coded dimensions of a data source.
 *
 * Returns an empty map until the lookups land, and on any failure — a missing dict
 * degrades to showing the raw code, which is what charts did before this existed.
 */
export function useDimensionLabels(
  dataSource: ChartDataSource | undefined,
  dimensions: string[] | undefined,
): DimensionLabelMap {
  const [labels, setLabels] = useState<DimensionLabelMap>({});
  const mountedRef = useRef(true);

  const modelCode = dataSource?.type === 'aggregate' ? dataSource.modelCode : undefined;
  const explicitDicts = dataSource?.dimensionDicts;

  // Stable dependency keys — `dimensions` and `dimensionDicts` are fresh objects on
  // every render of a widget whose config is inlined in the dashboard document.
  const dimensionsKey = (dimensions ?? []).join(',');
  const explicitDictsKey = useMemo(
    () =>
      Object.entries(explicitDicts ?? {})
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([field, dict]) => `${field}:${dict}`)
        .join(','),
    [explicitDicts],
  );

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const explicit: FieldDictMap = Object.fromEntries(
      explicitDictsKey
        ? explicitDictsKey.split(',').map((entry) => {
            const [field, dict] = entry.split(':');
            return [field, dict];
          })
        : [],
    );

    // An explicitly-declared column counts even when the backend does not call it a
    // dimension: a namedQuery identity passthrough (the kanban's detail rows) reports
    // its columns as metrics, and adding a dimension to make it "look right" would
    // make the backend GROUP BY and collapse the very rows the widget needs.
    const fields = [
      ...new Set([...(dimensionsKey ? dimensionsKey.split(',') : []), ...Object.keys(explicit)]),
    ];
    if (fields.length === 0) {
      setLabels({});
      return;
    }

    let cancelled = false;

    (async () => {

      // Explicit mapping wins: a namedQuery aliasing a column to a different name
      // than the underlying field must be able to say which dict it means.
      const fromModel = modelCode ? await loadFieldDicts(modelCode) : {};
      const dictByField: FieldDictMap = {};
      for (const field of fields) {
        const dictCode = explicit[field] ?? fromModel[field];
        if (dictCode) dictByField[field] = dictCode;
      }

      const resolved: DimensionLabelMap = {};
      await Promise.all(
        Object.entries(dictByField).map(async ([field, dictCode]) => {
          const map = await loadDictLabels(dictCode);
          if (Object.keys(map).length > 0) resolved[field] = map;
        }),
      );

      if (!cancelled && mountedRef.current) setLabels(resolved);
    })().catch(() => {
      // A failed dict lookup is not a chart failure — fall back to raw codes.
      if (!cancelled && mountedRef.current) setLabels({});
    });

    return () => {
      cancelled = true;
    };
  }, [modelCode, dimensionsKey, explicitDictsKey]);

  return labels;
}

export default useDimensionLabels;
