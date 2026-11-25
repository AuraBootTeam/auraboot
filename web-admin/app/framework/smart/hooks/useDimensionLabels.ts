import { internalRequest } from '~/shared/services/http-client/internal-api';
import { dictionaryData } from '~/shared/services/dict-adapter';
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
/** raw value -> the colour its dictionary item declares; only items that declare one are listed */
type DictToneMap = Record<string, string>;
/** one dictionary, loaded once: the labels every chart needs and the colours status tables need */
interface DictMaps {
  labels: DictLabelMap;
  tones: DictToneMap;
}
/** dimension field -> (raw value -> display label) */
export type DimensionLabelMap = Record<string, DictLabelMap>;
/** dimension field -> (raw value -> declared colour) */
export type DimensionToneMap = Record<string, DictToneMap>;

const fieldDictCache = new Map<string, FieldDictMap>();
const fieldDictInFlight = new Map<string, Promise<FieldDictMap>>();
const dictCache = new Map<string, DictMaps>();
const dictInFlight = new Map<string, Promise<DictMaps>>();

interface FieldMetaRow {
  code?: string;
  dictCode?: string | null;
}

/** Reset the module caches. Test-only — a dict edited in one test must not leak into the next. */
export function __resetDimensionLabelCaches(): void {
  fieldDictCache.clear();
  fieldDictInFlight.clear();
  dictCache.clear();
  dictInFlight.clear();
}

/** field code -> dict code for a model, from field metadata. */
async function loadFieldDicts(modelCode: string): Promise<FieldDictMap> {
  const cached = fieldDictCache.get(modelCode);
  if (cached) return cached;

  const inFlight = fieldDictInFlight.get(modelCode);
  if (inFlight) return inFlight;

  const promise = (async () => {
    const result = await fetchResult<FieldMetaRow[]>(`/api/dynamic/${modelCode}/field-meta`, {
      method: 'get',
    });
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

/** labels and declared colours for one dict, fetched once and shared by every widget that asks. */
async function loadDict(dictCode: string): Promise<DictMaps> {
  const cached = dictCache.get(dictCode);
  if (cached) return cached;

  const inFlight = dictInFlight.get(dictCode);
  if (inFlight) return inFlight;

  const promise = (async () => {
    // `/by-code/{code}` returns the dict with `items: null`; the items live behind
    // `/data`, which is what the form/list renderers already use.
    const result = await internalRequest('get', '/api/meta/dict/by-code/{code}/data', {
      path: { code: dictCode },
    });
    const maps: DictMaps = { labels: {}, tones: {} };
    if (result.code === '0' && result.data) {
      for (const item of dictionaryData(result.data).items) {
        if (item.label) maps.labels[item.value] = item.label;
        // The same `extension.color` the list and detail cells read: a dictionary item that
        // declares a colour is a status, anything else stays plain text.
        const color = item.extension?.color;
        if (typeof color === 'string' && color.trim()) maps.tones[item.value] = color;
      }
    }
    dictCache.set(dictCode, maps);
    return maps;
  })().finally(() => dictInFlight.delete(dictCode));

  dictInFlight.set(dictCode, promise);
  return promise;
}

interface DimensionDicts {
  labels: DimensionLabelMap;
  tones: DimensionToneMap;
}

/**
 * Resolve, for the dict-coded dimensions of a data source, both the display labels and the
 * colours the dictionary items declare. Shared by `useDimensionLabels` and `useDimensionTones`.
 *
 * Returns empty maps until the lookups land, and on any failure — a missing dict degrades to
 * showing the raw code, which is what charts did before this existed.
 */
function useDimensionDicts(
  dataSource: ChartDataSource | undefined,
  dimensions: string[] | undefined,
): DimensionDicts {
  const EMPTY = useMemo<DimensionDicts>(() => ({ labels: {}, tones: {} }), []);
  const [resolved, setResolved] = useState<DimensionDicts>(EMPTY);
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
      setResolved(EMPTY);
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

      const next: DimensionDicts = { labels: {}, tones: {} };
      await Promise.all(
        Object.entries(dictByField).map(async ([field, dictCode]) => {
          const maps = await loadDict(dictCode);
          if (Object.keys(maps.labels).length > 0) next.labels[field] = maps.labels;
          if (Object.keys(maps.tones).length > 0) next.tones[field] = maps.tones;
        }),
      );

      if (!cancelled && mountedRef.current) setResolved(next);
    })().catch(() => {
      // A failed dict lookup is not a chart failure — fall back to raw codes.
      if (!cancelled && mountedRef.current) setResolved(EMPTY);
    });

    return () => {
      cancelled = true;
    };
  }, [modelCode, dimensionsKey, explicitDictsKey, EMPTY]);

  return resolved;
}

/** Display labels for the dict-coded dimensions of a data source (field -> value -> label). */
export function useDimensionLabels(
  dataSource: ChartDataSource | undefined,
  dimensions: string[] | undefined,
): DimensionLabelMap {
  return useDimensionDicts(dataSource, dimensions).labels;
}

/**
 * Colours declared by the dictionary items of the dict-coded dimensions (field -> value -> colour).
 * A value is listed only when its dictionary item sets `extension.color`, so a table can draw the
 * same 色点 + 文字 as list pages for statuses and leave categories as plain text.
 */
export function useDimensionTones(
  dataSource: ChartDataSource | undefined,
  dimensions: string[] | undefined,
): DimensionToneMap {
  return useDimensionDicts(dataSource, dimensions).tones;
}

export default useDimensionLabels;
