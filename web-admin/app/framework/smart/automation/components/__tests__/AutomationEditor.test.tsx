import { render, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';

// ---------------------------------------------------------------------------
// ACP H.1 regression test.
//
// Bug: AutomationEditor used to pass its local `flowData` state back to
// FlowDesigner as the `initialData` prop. Every onChange from FlowDesigner
// would call `setFlowData(...)`, producing a brand-new prop reference on
// the next render. FlowDesigner's mount-effect (`useEffect(..., [initialData])`)
// then re-ran `importData()`, which clears `selectedNodeId` and unmounts
// the property panel — forcing the user (and the LLM-call E2E spec) to
// re-click the node before each successive field edit.
//
// Fix: AutomationEditor memoises a `flowDataInitial` derived strictly from
// the `initialData` prop. The reference passed to FlowDesigner is now stable
// across onChange-driven re-renders. This test guards the invariant.
// ---------------------------------------------------------------------------

// Capture every `initialData` reference handed to FlowDesigner across renders.
const receivedInitialData: Array<unknown> = [];
let lastOnChange: ((data: unknown) => void) | undefined;

vi.mock('~/plugins/core-designer/components/flow-designer-sdk', () => ({
  FlowDesigner: (props: any) => {
    receivedInitialData.push(props.initialData);
    lastOnChange = props.onChange;
    return <div data-testid="flow-designer-mock" />;
  },
}));

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string) => key,
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

vi.mock('../../debug', () => ({
  AutomationDebugger: () => <div data-testid="automation-debugger" />,
  useDebugSession: () => ({
    isDebugMode: false,
    startDebug: vi.fn(),
  }),
}));

vi.mock('~/shared/designer/DesignerToolbar', () => ({
  DesignerToolbar: ({ children, titleElement }: any) => (
    <div data-testid="designer-toolbar">
      {titleElement}
      {children}
    </div>
  ),
}));

// nodes module is imported by AutomationEditor; provide a minimal stub so
// vitest does not pull the entire automation node graph during this unit
// test.
vi.mock('../../nodes', () => ({
  automationNodes: [],
  automationCategoryOrder: [],
}));

// Import AFTER the mocks are wired up.
import { AutomationEditor } from '../AutomationEditor';
import type { FlowData } from '~/plugins/core-designer/components/flow-designer-sdk';

const buildFlowData = (suffix: string): FlowData => ({
  nodes: [
    {
      id: `node_${suffix}`,
      type: 'trigger.scheduled',
      position: { x: 0, y: 0 },
      data: { label: 'Trigger' },
    },
  ],
  edges: [],
});

describe('AutomationEditor — initialData stability (ACP H.1)', () => {
  beforeEach(() => {
    receivedInitialData.length = 0;
    lastOnChange = undefined;
  });

  it('keeps the initialData reference stable across FlowDesigner onChange events', () => {
    const initial = {
      name: 'Auto X',
      description: 'desc',
      flowData: buildFlowData('a'),
    };

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={initial}
        onSave={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect(receivedInitialData).toHaveLength(1);
    const firstRef = receivedInitialData[0];
    expect(firstRef).toBe(initial.flowData);

    // Simulate FlowDesigner reporting a content change (e.g. user typed in a
    // property field, store updated, onChange fired with new nodes/edges).
    act(() => {
      lastOnChange?.(buildFlowData('b'));
    });

    // The onChange handler bumps internal `flowData` state and `isDirty`,
    // forcing AutomationEditor to re-render. The CRITICAL invariant: the
    // reference passed to FlowDesigner as `initialData` MUST NOT change,
    // otherwise FlowDesigner's mount-effect re-runs importData() and resets
    // `selectedNodeId` to null.
    expect(receivedInitialData.length).toBeGreaterThan(1);
    for (const ref of receivedInitialData) {
      expect(ref).toBe(firstRef);
    }
  });

  it('updates initialData reference when the parent supplies a new prop', () => {
    const initialA = {
      name: 'Auto A',
      flowData: buildFlowData('a'),
    };
    const initialB = {
      name: 'Auto B',
      flowData: buildFlowData('b'),
    };

    const { rerender } = render(
      <AutomationEditor automationId="auto-1" initialData={initialA} />,
    );
    const refsAfterMount = [...receivedInitialData];
    expect(refsAfterMount[0]).toBe(initialA.flowData);

    rerender(
      <AutomationEditor automationId="auto-1" initialData={initialB} />,
    );

    // After the parent supplies a *new* initialData object (e.g. reload from
    // server), FlowDesigner SHOULD see the new reference so it imports the
    // refreshed schema.
    const lastRef = receivedInitialData[receivedInitialData.length - 1];
    expect(lastRef).toBe(initialB.flowData);
    expect(lastRef).not.toBe(initialA.flowData);
  });
});
