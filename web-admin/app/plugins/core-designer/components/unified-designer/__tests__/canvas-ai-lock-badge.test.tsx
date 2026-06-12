/**
 * canvas-ai-lock-badge.test.tsx
 *
 * A field marked props.aiLocked in the unified designer must show a visible
 * lock badge on its canvas card (D5) so the author can see which fields an AI
 * fill will skip. Fields that are not locked must not show the badge.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import type { PageSchemaV3 } from '../types';

const doc: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'form',
  id: 'lock_form',
  title: { en: 'Lock Form', 'zh-CN': '锁定表单' },
  modelCode: 'customer',
  blocks: [
    {
      id: 'form_root',
      blockType: 'form',
      layout: { span: 12 },
      blocks: [
        {
          id: 'sec',
          blockType: 'form-section',
          layout: { span: 12 },
          blocks: [
            {
              id: 'field_locked',
              blockType: 'field',
              field: 'reason',
              layout: { span: 6 },
              props: { label: 'Reason', component: 'input', aiLocked: true },
            },
            {
              id: 'field_plain',
              blockType: 'field',
              field: 'type',
              layout: { span: 6 },
              props: { label: 'Type', component: 'input' },
            },
          ],
        },
      ],
    },
  ],
};

describe('Canvas AI lock badge', () => {
  it('shows a lock badge on an aiLocked field and not on a plain field', () => {
    render(<UnifiedDesignerWorkbench initialDocument={doc} modelFieldsByModel={{}} />);
    expect(screen.getByTestId('ai-lock-badge-field_locked')).toBeTruthy();
    expect(screen.queryByTestId('ai-lock-badge-field_plain')).toBeNull();
  });
});
