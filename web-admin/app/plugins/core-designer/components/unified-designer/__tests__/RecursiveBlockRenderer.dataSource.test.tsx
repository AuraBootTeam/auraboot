import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import type { ModelFieldDefinition, PageSchemaV3 } from '../types';

vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({ hasPermission: () => true }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (key: string) => key }),
}));

// Exercise the real shared context contract at the platform-control boundary.
// The existing wiring suite mocks field internals without requiring a manager.
vi.mock('~/framework/meta/rendering/ControlledFieldRenderer', async () => {
  const { useDataSourceManager } = await import('~/framework/meta/contexts/DataSourceContext');
  return {
    ControlledFieldRenderer: ({ value, onChange }: {
      value?: unknown;
      onChange?: (value: string) => void;
    }) => {
      useDataSourceManager();
      return (
        <select aria-label="Managed select" value={String(value ?? '')}
          onChange={event => onChange?.(event.target.value)}>
          <option value="">Choose</option>
          <option value="ready">Ready</option>
        </select>
      );
    },
  };
});

const schema: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'form',
  id: 'explicit_component_without_metadata',
  blocks: [{
    id: 'form', blockType: 'form', blocks: [{
      id: 'explicit_select', blockType: 'field', field: 'status',
      props: { component: 'select' },
    }],
  }],
};

describe('runtime platform-control data-source boundary', () => {
  it.each([undefined, []] as Array<ModelFieldDefinition[] | undefined>)(
    'provides the required manager for an explicit control with metadata %j', modelFields => {
      render(<RecursiveBlockRenderer schema={schema} modelFields={modelFields} />);
      const select = screen.getByRole('combobox', { name: 'Managed select' });
      fireEvent.change(select, { target: { value: 'ready' } });
      expect(select).toHaveValue('ready');
    },
  );
});
