import type { AuthoringSyntheticPreview } from '~/framework/meta/authoring/types';
import type { DslBlockV3, PageSchemaV3 } from '../types';
import {
  RuntimeExecutionError,
  type RuntimeExecutionServices,
} from '../runtime/runtimeExecution';
import { sanitizeRoleStructurePreviewDocument } from './roleStructurePreview';

/** Replace every embedded record-shaped value with the server-generated isolated fixture. */
export function applySyntheticPreviewToDocument(
  document: PageSchemaV3,
  preview: AuthoringSyntheticPreview,
): PageSchemaV3 {
  const sanitized = sanitizeRoleStructurePreviewDocument(document);
  return {
    ...sanitized,
    blocks: sanitized.blocks.map((block) => applySyntheticBlock(block, preview)),
  };
}

/** Runtime seam that can only return in-memory fixture data and never executes a business action. */
export function createSyntheticPreviewRuntimeServices(
  preview: AuthoringSyntheticPreview,
): RuntimeExecutionServices {
  return {
    loadWidgetData: async (block) => {
      const widget = preview.widgets[block.id];
      return widget
        ? {
            source: widget.source,
            value: widget.value,
            series: widget.series,
          }
        : {
            source: preview.source,
            emptyText: 'Synthetic fixture',
          };
    },
    loadPickerOptions: async (_block, context) => {
      const keyword = context?.pickerSearch?.trim().toLowerCase();
      return preview.records
        .map((record, index) => ({
          label: syntheticRecordLabel(record, index),
          value: String(record.pid ?? `synthetic-${index + 1}`),
          record,
        }))
        .filter((option) => !keyword || option.label.toLowerCase().includes(keyword));
    },
    loadHelperBlockData: async () => ({
      source: preview.source,
      description: 'Synthetic fixture only',
      feedback: 'No tenant business record was queried',
    }),
    executeAction: async () => {
      throw new RuntimeExecutionError({
        kind: 'permission',
        code: 'SYNTHETIC_PREVIEW_ACTION_DISABLED',
        message: '合成数据预览不执行业务动作',
        hint: '退出合成数据预览后再执行操作',
      });
    },
  };
}

function applySyntheticBlock(
  block: DslBlockV3,
  preview: AuthoringSyntheticPreview,
): DslBlockV3 {
  const props = { ...(block.props ?? {}) };
  if (
    block.blockType === 'table' ||
    block.blockType === 'sub-table' ||
    block.blockType === 'repeater' ||
    block.blockType === 'subform'
  ) {
    props.rows = preview.records.map((record) => ({ ...record }));
  }
  const widget = preview.widgets[block.id];
  if (block.blockType === 'widget' && widget) {
    props.value = widget.value;
    props.series = widget.series.map((point) => ({ ...point }));
    props.source = widget.source;
  }
  return {
    ...block,
    props,
    blocks: block.blocks?.map((child) => applySyntheticBlock(child, preview)),
  };
}

function syntheticRecordLabel(record: Record<string, unknown>, index: number): string {
  const preferred = ['name', 'title', 'code', 'label']
    .map((key) => record[key])
    .find((value) => typeof value === 'string' && value.trim());
  return typeof preferred === 'string' ? preferred : `Synthetic record ${index + 1}`;
}
