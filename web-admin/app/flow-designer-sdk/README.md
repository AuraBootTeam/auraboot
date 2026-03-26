# Flow Designer SDK

A reusable flow/workflow designer SDK based on @xyflow/react. Can be used for Automation, BPMN, and other flow-based editors.

## Installation

The SDK is part of the web-admin application. Import from:

```typescript
import { FlowDesigner, useFlowStore, nodeRegistry } from '~/flow-designer-sdk';
```

## Quick Start

### Basic Usage

```tsx
import { FlowDesigner, type FlowNodeDefinition } from '~/flow-designer-sdk';

// Define your nodes
const nodeDefinitions: FlowNodeDefinition[] = [
  {
    type: 'my-trigger',
    label: 'My Trigger',
    icon: '⚡',
    category: 'trigger',
    configSchema: [
      { key: 'name', label: 'Name', type: 'text', required: true },
    ],
  },
  {
    type: 'my-action',
    label: 'My Action',
    icon: '▶️',
    category: 'action',
    configSchema: [
      { key: 'value', label: 'Value', type: 'number' },
    ],
  },
];

function MyEditor() {
  return (
    <FlowDesigner
      config={{
        nodeDefinitions,
        categoryOrder: ['trigger', 'action'],
        showMinimap: true,
        showControls: true,
      }}
      title="My Flow Editor"
      onSave={async (data) => {
        console.log('Saving:', data);
        await api.saveFlow(data);
      }}
    />
  );
}
```

## Core Components

### FlowDesigner

Main component that combines all sub-components.

```tsx
<FlowDesigner
  config={FlowDesignerConfig}   // Node definitions and settings
  initialData={FlowData}         // Initial flow data
  title={string}                 // Toolbar title
  onSave={(data) => Promise}     // Save callback
  onChange={(data) => void}      // Change callback
  onValidate={() => void}        // Validation callback
  readOnly={boolean}             // Read-only mode
  className={string}             // Custom CSS class
/>
```

### FlowDesignerConfig

```typescript
interface FlowDesignerConfig {
  nodeDefinitions: FlowNodeDefinition[];  // Node definitions to register
  categoryOrder?: string[];               // Order of categories in palette
  showMinimap?: boolean;                  // Show minimap control
  showControls?: boolean;                 // Show zoom/fit controls
}
```

### Individual Components

You can use components individually for custom layouts:

```tsx
import {
  FlowToolbar,
  FlowPalette,
  FlowCanvas,
  FlowPropertyPanel,
  DefaultFlowNode,
} from '~/flow-designer-sdk';
```

## Types

### FlowNodeDefinition

```typescript
interface FlowNodeDefinition {
  type: string;                          // Unique node type identifier
  label: I18nText;                       // Display label (supports i18n)
  icon: string | React.ReactNode;        // Node icon (emoji or component)
  category: string;                      // Category for grouping
  description?: I18nText;                // Optional description
  configSchema?: PropertySchema[];       // Property panel schema
  defaultConfig?: Record<string, unknown>;  // Default values
  component?: React.ComponentType<any>;  // Custom render component
  validation?: NodeValidation;           // Validation rules
}
```

### PropertySchema

```typescript
interface PropertySchema {
  key: string;               // Field key in node config
  label: I18nText;           // Field label
  type: PropertyType;        // Field type
  required?: boolean;        // Is required
  options?: { label: I18nText; value: string }[];  // For select types
  placeholder?: I18nText;    // Placeholder text
  description?: I18nText;    // Help text
  defaultValue?: unknown;    // Default value
  dependsOn?: { field: string; value: unknown }; // Conditional display
}

type PropertyType =
  | 'text'
  | 'textarea'
  | 'number'
  | 'select'
  | 'multiselect'
  | 'boolean'
  | 'json'
  | 'expression'
  | 'model-select'
  | 'field-select';
```

### NodeValidation

```typescript
interface NodeValidation {
  minInputs?: number;
  maxInputs?: number;
  minOutputs?: number;
  maxOutputs?: number;
  custom?: (node: any, context: any) => string[];
}
```

### FlowData

```typescript
interface FlowData {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

interface FlowNode {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: {
    label: string;
    config: Record<string, unknown>;
  };
}

interface FlowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  data?: {
    label?: string;
    condition?: string;
  };
}
```

### ValidationResult

```typescript
interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
}

interface ValidationError {
  nodeId?: string;
  edgeId?: string;
  fieldKey?: string;
  message: string;
  type: 'error' | 'warning';
}
```

## State Management

### useFlowStore

Zustand store for flow state management.

```typescript
import { useFlowStore } from '~/flow-designer-sdk';

function MyComponent() {
  const {
    // Data
    nodes,
    edges,
    selectedNodeId,

    // Status
    isDirty,
    validationResult,

    // Node operations
    addNode,
    updateNode,
    updateNodeConfig,
    deleteNode,
    selectNode,

    // Edge operations
    addEdge,
    updateEdge,
    deleteEdge,

    // Validation
    setValidationResult,

    // Import/Export
    importData,
    exportData,
    reset,
    setDirty,
  } = useFlowStore();
}
```

### Store Actions

| Action | Signature | Description |
|--------|-----------|-------------|
| `addNode` | `(node: Omit<FlowNode, 'id'>) => string` | Add a new node, returns ID |
| `updateNode` | `(id: string, updates: Partial<FlowNode>) => void` | Update node properties |
| `updateNodeConfig` | `(id: string, config: Record<string, unknown>) => void` | Update node config |
| `deleteNode` | `(id: string) => void` | Delete node and related edges |
| `selectNode` | `(id: string \| null) => void` | Select a node (null to deselect) |
| `addEdge` | `(edge: Omit<FlowEdge, 'id'>) => string` | Add a new edge, returns ID |
| `updateEdge` | `(id: string, updates: Partial<FlowEdge>) => void` | Update edge properties |
| `deleteEdge` | `(id: string) => void` | Delete an edge |
| `setValidationResult` | `(result: ValidationResult \| null) => void` | Set validation result |
| `importData` | `(data: FlowData) => void` | Import flow data |
| `exportData` | `() => FlowData` | Export current flow data |
| `reset` | `() => void` | Reset to empty state |
| `setDirty` | `(dirty: boolean) => void` | Set dirty flag |

## Node Registry

### NodeRegistry Class

```typescript
import { nodeRegistry, NodeRegistry } from '~/flow-designer-sdk';

// Using singleton instance
nodeRegistry.register(myNodeDefinition);
nodeRegistry.registerAll([node1, node2, node3]);

// Or create your own instance
const myRegistry = new NodeRegistry();
```

### Registry Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `register` | `(definition: FlowNodeDefinition) => void` | Register single node |
| `registerAll` | `(definitions: FlowNodeDefinition[]) => void` | Register multiple nodes |
| `get` | `(type: string) => FlowNodeDefinition \| undefined` | Get node definition by type |
| `getAll` | `() => FlowNodeDefinition[]` | Get all node definitions |
| `getByCategory` | `() => Record<string, FlowNodeDefinition[]>` | Get nodes grouped by category |
| `getCategories` | `() => string[]` | Get all category names |
| `has` | `(type: string) => boolean` | Check if type is registered |
| `clear` | `() => void` | Clear all registrations |

## Field Adapter

The SDK uses FieldAdapter pattern for form state management, bridging the FlowDesigner's Zustand store with field components.

### useFlowFieldAdapter

```typescript
import { useFlowFieldAdapter } from '~/flow-designer-sdk';

interface FlowFieldAdapterProps<T = unknown> {
  fieldKey: string;        // Field key in node.data.config
  nodeId?: string;         // Node ID (defaults to selected node)
  required?: boolean;      // Whether the field is required
  disabled?: boolean;      // Whether the field is disabled
  readOnly?: boolean;      // Whether the field is read-only
}

function CustomPropertyField({ fieldKey }: { fieldKey: string }) {
  const adapter = useFlowFieldAdapter<string>({ fieldKey, required: true });

  return (
    <input
      value={adapter.value || ''}
      onChange={(e) => adapter.setValue(e.target.value)}
      disabled={adapter.disabled}
    />
  );
}
```

### FieldAdapter Interface

```typescript
interface FieldAdapter<T> {
  value: T;
  setValue: (value: T) => void;
  error?: string;
  disabled?: boolean;
  required?: boolean;
  readOnly?: boolean;
}
```

## Custom Node Component

Create custom node rendering:

```tsx
import { Handle, Position } from '@xyflow/react';
import type { FlowNodeDefinition } from '~/flow-designer-sdk';

function MyCustomNode({ data, selected }: { data: any; selected: boolean }) {
  return (
    <div className={`my-node ${selected ? 'selected' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="node-content">
        <span className="icon">{data.icon}</span>
        <span className="label">{data.label}</span>
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const myNode: FlowNodeDefinition = {
  type: 'custom-node',
  label: 'Custom Node',
  icon: '🎨',
  category: 'action',
  component: MyCustomNode, // Use custom component
  configSchema: [
    { key: 'name', label: 'Name', type: 'text', required: true },
  ],
};
```

## Internationalization

Labels support three formats:

```typescript
// 1. Simple string
label: 'My Label'

// 2. i18n key (resolved by your i18n system)
label: '$i18n:myModule.myKey'

// 3. LocalizedText object
label: { 'zh-CN': '我的标签', 'en-US': 'My Label' }
```

The `I18nText` type is:
```typescript
type I18nText = string | Record<string, string>;
```

## Example: Automation Editor

See the Automation implementation for a complete example:

- Node definitions: `~/smart/automation/nodes/`
  - `triggerNodes.ts` - Trigger node definitions
  - `actionNodes.ts` - Action node definitions
  - `controlNodes.ts` - Control flow node definitions
  - `index.ts` - Combined exports
- Editor component: `~/smart/automation/components/AutomationEditor.tsx`
- Service layer: `~/smart/automation/services/automationService.ts`
- i18n translations: `~/smart/automation/i18n/`

## Directory Structure

```
flow-designer-sdk/
├── core/                      # UI Components
│   ├── FlowDesigner.tsx       # Main component
│   ├── FlowCanvas.tsx         # React Flow canvas
│   ├── FlowPalette.tsx        # Component library
│   ├── FlowPropertyPanel.tsx  # Property editor
│   ├── FlowToolbar.tsx        # Toolbar
│   ├── PropertyField.tsx      # Dynamic property field
│   ├── DefaultFlowNode.tsx    # Default node render
│   └── index.ts               # Core exports
├── store/                     # State management
│   ├── types.ts               # Type definitions
│   ├── useFlowStore.ts        # Zustand store
│   └── index.ts               # Store exports
├── nodes/                     # Node registry
│   ├── types.ts               # Node type definitions
│   ├── NodeRegistry.ts        # Registry class
│   └── index.ts               # Node exports
├── adapters/                  # Field adapters
│   ├── FlowFieldAdapter.ts    # Flow field adapter
│   └── index.ts               # Adapter exports
├── types/                     # Shared types
│   └── index.ts               # Re-exports all types
├── __tests__/                 # Unit tests
│   ├── NodeRegistry.test.ts
│   └── useFlowStore.test.ts
├── index.ts                   # SDK entry point
└── README.md                  # This file
```

## Testing

### Unit Tests

```bash
cd web-admin
npm test -- --grep "flow-designer-sdk"
```

### E2E Tests

See `~/smart/automation/__tests__/` for E2E test examples using Playwright.

## License

Internal use only. Part of AuraBoot platform.
