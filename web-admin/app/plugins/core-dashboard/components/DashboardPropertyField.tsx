/**
 * DashboardPropertyField - Thin wrapper around shared PropertyFieldRenderer.
 * Bridges Dashboard's Zustand store (via DashboardFieldAdapter) with the
 * unified renderer.
 */

import React from 'react';
import { useDashboardFieldAdapter } from '../adapters/DashboardFieldAdapter';
import { PropertyFieldRenderer } from '~/shared/designer';
import type { PropertySchema } from '../types';

export interface DashboardPropertyFieldProps {
  schema: PropertySchema;
  widgetId: string;
}

export function DashboardPropertyField({ schema, widgetId }: DashboardPropertyFieldProps) {
  const adapter = useDashboardFieldAdapter({
    fieldKey: schema.key,
    widgetId,
    required: schema.required,
  });

  return <PropertyFieldRenderer schema={schema} adapter={adapter} />;
}

export default DashboardPropertyField;
