/**
 * SmartKanban.e2eMode.test.tsx
 *
 * Pin the sensor swap contract:
 *   - When `window.__AURA_E2E_MODE__ === true`, the sensor registered with
 *     <DndContext sensors={...}> must originate from MouseSensor.
 *   - Otherwise it must originate from PointerSensor.
 *
 * We mock `useSensors` to capture the sensor descriptor the component wires
 * into DndContext, and tag each sensor descriptor by intercepting `useSensor`
 * so we can identify which of (Mouse|Pointer)Sensor produced it.
 */
import React from 'react';
import { render, cleanup, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Track every useSensor invocation so we can correlate the descriptor objects
// returned to it with the sensor class that was passed in.
const sensorRegistry = new WeakMap<object, unknown>();

let lastUseSensorsArgs: unknown[] | null = null;

vi.mock('@dnd-kit/core', async () => {
  const actual = await vi.importActual<typeof import('@dnd-kit/core')>('@dnd-kit/core');
  return {
    ...actual,
    useSensor: (sensor: unknown, options: unknown) => {
      const descriptor = actual.useSensor(sensor as never, options as never);
      if (descriptor && typeof descriptor === 'object') {
        sensorRegistry.set(descriptor as object, sensor);
      }
      return descriptor;
    },
    useSensors: (...descriptors: unknown[]) => {
      lastUseSensorsArgs = descriptors;
      return actual.useSensors(...(descriptors as Parameters<typeof actual.useSensors>));
    },
  };
});

vi.mock('~/framework/smart/hooks/useKanbanData', () => ({
  useKanbanData: () => ({
    columns: [
      {
        id: 'todo',
        title: 'Todo',
        count: 1,
        cards: [{ id: 'card-1', title: 'Demo Card', status: 'todo' }],
      },
    ],
    loading: false,
    error: null,
    moveCard: vi.fn(),
  }),
}));

vi.mock('~/framework/smart/hooks/useDictWithExtras', () => ({
  useDictWithExtras: () => ({ items: [], loading: false }),
}));

import { MouseSensor, PointerSensor } from '@dnd-kit/core';
import { SmartKanban } from '../SmartKanban';
import type { SmartKanbanProps } from '~/framework/smart/types/kanban';

const baseProps: SmartKanbanProps = {
  dataSource: {
    type: 'aggregate',
    modelCode: 'demo',
    groupByField: 'status',
    titleField: 'title',
  },
};

function activeSensorClass(): unknown {
  expect(lastUseSensorsArgs, 'useSensors was not called').not.toBeNull();
  expect(lastUseSensorsArgs!.length).toBeGreaterThan(0);
  const active = lastUseSensorsArgs![0] as object;
  const cls = sensorRegistry.get(active);
  expect(cls, 'active sensor descriptor must be tracked').toBeTruthy();
  return cls;
}

afterEach(() => {
  cleanup();
  lastUseSensorsArgs = null;
  delete (window as Window).__AURA_E2E_MODE__;
});

describe('SmartKanban sensor swap based on __AURA_E2E_MODE__', () => {
  beforeEach(() => {
    lastUseSensorsArgs = null;
  });

  it('selects MouseSensor when __AURA_E2E_MODE__ is true', () => {
    (window as Window).__AURA_E2E_MODE__ = true;

    render(<SmartKanban {...baseProps} />);

    expect(activeSensorClass()).toBe(MouseSensor);
  });

  it('selects PointerSensor when __AURA_E2E_MODE__ is undefined (production default)', () => {
    expect((window as Window).__AURA_E2E_MODE__).toBeUndefined();

    render(<SmartKanban {...baseProps} />);

    expect(activeSensorClass()).toBe(PointerSensor);
  });

  it('selects PointerSensor when __AURA_E2E_MODE__ is explicitly false', () => {
    (window as Window).__AURA_E2E_MODE__ = false;

    render(<SmartKanban {...baseProps} />);

    expect(activeSensorClass()).toBe(PointerSensor);
  });

  it('renders stable kanban board and card test hooks', () => {
    render(<SmartKanban {...baseProps} />);

    expect(screen.getByTestId('kanban-board')).toBeTruthy();
    expect(screen.getByTestId('kanban-card')).toHaveTextContent('Demo Card');
    expect(screen.getByTestId('kanban-column-header')).toHaveAttribute('data-column-id', 'todo');
  });
});
