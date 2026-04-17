/**
 * bpm-panel block definition — detail-page BPM workbench panel.
 *
 * Renders up to 4 sections inside a record detail view:
 *   - status      (instance status badge + current nodes)
 *   - diagram     (runtime BPMN diagram with highlights)
 *   - operations  (approve / reject / withdraw / cc buttons)
 *   - history     (audit trail timeline)
 *
 * Runtime component: ~/framework/meta/rendering/blocks/BpmPanelBlock.tsx
 *
 * @since 2026-04-17 (OSS BPM Closure Spec 1, Task 15)
 */

import type { BlockDefinition } from '../../types';

export const bpmPanelBlock: BlockDefinition = {
  type: 'bpm-panel',
  name: 'BPM Approval Panel',
  icon: '⚙',
  description: 'Workflow status / diagram / operations / history for a record',
  category: 'display',
  defaultColSpan: 12,
  schema: [
    // ── Data Source ──────────────────────────────────────────────
    {
      key: 'processDefinitionKey',
      label: 'Process Definition',
      type: 'process-select',
      group: 'Data Source',
      required: true,
      description: 'BPMN process definition linked to records on this page',
    },
    {
      key: 'businessKeyField',
      label: 'Business Key Field',
      type: 'field-select',
      group: 'Data Source',
      placeholder: 'Defaults to record.pid',
      description: 'Record field resolved to the process businessKey (defaults to pid)',
    },

    // ── Sections ────────────────────────────────────────────────
    {
      key: 'sections',
      label: 'Enabled Sections',
      type: 'multiselect',
      group: 'Sections',
      defaultValue: ['status', 'diagram', 'operations', 'history'],
      options: [
        { label: 'Status', value: 'status' },
        { label: 'Diagram', value: 'diagram' },
        { label: 'Operations', value: 'operations' },
        { label: 'History', value: 'history' },
      ],
      description: 'Which panel sections to render (all enabled by default)',
    },

    // ── Conditions ──────────────────────────────────────────────
    {
      key: 'visibleWhen',
      label: 'Visible when',
      type: 'expression',
      group: 'Conditions',
      description: 'Condition expression to control block visibility',
    },
  ],
};
