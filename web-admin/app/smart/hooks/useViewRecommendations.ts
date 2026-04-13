/**
 * useViewRecommendations — AI-style view recommendations based on model field types.
 *
 * Analyzes a model's fields and suggests appropriate view types (kanban, gantt, calendar, etc.)
 * with confidence levels and matched field codes.
 */

import { useMemo } from 'react';
import type { ViewType } from '~/smart/types/savedView';

export interface ViewRecommendation {
  viewType: ViewType;
  reason: string;
  confidence: 'high' | 'medium';
  matchedFields: string[];
}

interface FieldInfo {
  code: string;
  dataType: string;
  dictCode?: string;
  referenceModelCode?: string;
}

export function useViewRecommendations(
  modelCode: string,
  fields: FieldInfo[],
): ViewRecommendation[] {
  return useMemo(() => {
    const recs: ViewRecommendation[] = [];

    const dateFields = fields.filter((f) =>
      ['date', 'datetime', 'timestamp'].includes((f.dataType || '').toLowerCase()),
    );
    const dictFields = fields.filter(
      (f) => f.dictCode || ['dict', 'enum'].includes((f.dataType || '').toLowerCase()),
    );
    const imageFields = fields.filter(
      (f) =>
        ['image', 'file'].includes((f.dataType || '').toLowerCase()) ||
        /image|photo|avatar|cover|thumbnail/i.test(f.code),
    );
    const selfRefFields = fields.filter(
      (f) => (f.dataType || '').toLowerCase() === 'reference' && f.referenceModelCode === modelCode,
    );

    if (dateFields.length >= 2) {
      recs.push({
        viewType: 'gantt',
        reason: 'Has start/end date fields — visualize as Gantt chart',
        confidence: 'high',
        matchedFields: dateFields.slice(0, 2).map((f) => f.code),
      });
      recs.push({
        viewType: 'timeline',
        reason: 'Has date range fields — visualize resource allocation',
        confidence: 'medium',
        matchedFields: dateFields.slice(0, 2).map((f) => f.code),
      });
    }
    if (dateFields.length >= 1) {
      recs.push({
        viewType: 'calendar',
        reason: 'Has date field — display events on calendar',
        confidence: 'high',
        matchedFields: [dateFields[0].code],
      });
    }
    if (dictFields.length >= 1) {
      recs.push({
        viewType: 'kanban',
        reason: 'Has status/category field — organize as kanban board',
        confidence: 'high',
        matchedFields: [dictFields[0].code],
      });
    }
    if (imageFields.length >= 1) {
      recs.push({
        viewType: 'gallery',
        reason: 'Has image field — display as visual gallery',
        confidence: 'medium',
        matchedFields: [imageFields[0].code],
      });
    }
    if (selfRefFields.length >= 1) {
      recs.push({
        viewType: 'tree',
        reason: 'Has self-referencing field — display as tree hierarchy',
        confidence: 'high',
        matchedFields: [selfRefFields[0].code],
      });
    }

    return recs;
  }, [modelCode, fields]);
}
