# Frontend Plugin Guide

This guide covers building frontend plugins for AuraBoot using React and Module Federation. Frontend plugins let you add custom UI components -- block renderers, dashboard widgets, and field renderers -- that get loaded dynamically at runtime.

---

## When You Need a Frontend Plugin

The DSL page system handles most UI needs through configuration. You need a frontend plugin when:

- **Custom block renderers**: Specialized visualizations for the Page Designer (map view, Gantt chart, org chart, timeline)
- **Custom dashboard widgets**: Chart types or visualizations not available in the built-in widget library
- **Custom field renderers**: Specialized input components for forms (code editor, drawing canvas, signature pad)
- **Complex interactive pages**: Full custom pages that cannot be expressed through DSL blocks

---

## Architecture

Frontend plugins use **Module Federation** to load remote React modules at runtime:

```
+-----------------------+       +----------------------------+
|   AuraBoot Platform   |       |     Your Plugin (remote)   |
|   (host application)  |       |                            |
|                       |  <--  |  remoteEntry.js            |
|   PluginRegistry      |       |    +-- MapViewBlock        |
|   BlockRenderer       |       |    +-- GanttWidget         |
|   WidgetRenderer      |       |    +-- CodeEditorField     |
+-----------------------+       +----------------------------+
```

The platform (host) discovers and loads your remote module at runtime. Your components render inside the platform's layout, with access to the page context, record data, and theme.

---

## Project Setup

### Initialize the Project

```bash
mkdir my-frontend-plugin
cd my-frontend-plugin
npm init -y
npm install react react-dom
npm install -D vite @vitejs/plugin-react @originjs/vite-plugin-federation typescript
```

### Directory Structure

```
my-frontend-plugin/
+-- src/
|   +-- index.ts                 # Module Federation exports
|   +-- blocks/
|   |   +-- MapViewBlock.tsx     # Custom block renderer
|   +-- widgets/
|   |   +-- HeatmapWidget.tsx    # Custom dashboard widget
|   +-- fields/
|       +-- CodeEditorField.tsx  # Custom field renderer
+-- vite.config.ts
+-- tsconfig.json
+-- package.json
```

### Vite Configuration

**`vite.config.ts`**:

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import federation from '@originjs/vite-plugin-federation';

export default defineConfig({
  plugins: [
    react(),
    federation({
      name: 'myPlugin',
      filename: 'remoteEntry.js',
      exposes: {
        // Block renderers
        './MapViewBlock': './src/blocks/MapViewBlock.tsx',
        // Dashboard widgets
        './HeatmapWidget': './src/widgets/HeatmapWidget.tsx',
        // Field renderers
        './CodeEditorField': './src/fields/CodeEditorField.tsx',
      },
      shared: {
        react: { singleton: true, requiredVersion: '^18.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^18.0.0' },
      },
    }),
  ],
  build: {
    target: 'esnext',
    minify: true,
    cssCodeSplit: false,
  },
});
```

**Key settings:**
- `name`: Unique identifier for your remote module
- `filename`: Entry point file name (always `remoteEntry.js`)
- `exposes`: Map of module paths to source files. Each exposed module is a component the platform can load.
- `shared`: React must be shared as a singleton to avoid duplicate React instances

### TypeScript Configuration

**`tsconfig.json`**:

```json
{
  "compilerOptions": {
    "target": "ESNext",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "declaration": true
  },
  "include": ["src"]
}
```

---

## Custom Block Renderers

Block renderers provide custom visualization for pages built with the Page Designer. They receive the block configuration and record data as props.

### Block Renderer Interface

```typescript
interface BlockRendererProps {
  block: {
    id: string;
    blockType: string;
    // Custom config defined in your block schema
    [key: string]: any;
  };
  records: Array<Record<string, any>>;
  modelCode: string;
  pageContext: {
    pageKey: string;
    kind: string;  // 'list' | 'form' | 'detail' | 'dashboard'
    recordId?: string;
  };
  onRecordClick?: (recordId: string) => void;
}
```

### Complete Example: Map View Block

**`src/blocks/MapViewBlock.tsx`**:

```tsx
import React, { useEffect, useRef, useState } from 'react';

interface MapViewBlockProps {
  block: {
    id: string;
    blockType: string;
    latField: string;     // Field code for latitude
    lngField: string;     // Field code for longitude
    labelField: string;   // Field code for pin label
    zoom?: number;
    center?: { lat: number; lng: number };
  };
  records: Array<Record<string, any>>;
  modelCode: string;
  onRecordClick?: (recordId: string) => void;
}

const MapViewBlock: React.FC<MapViewBlockProps> = ({
  block,
  records,
  onRecordClick,
}) => {
  const mapRef = useRef<HTMLDivElement>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const { latField, lngField, labelField, zoom = 10 } = block;

  // Filter records that have valid coordinates
  const pins = records
    .filter(r => r[latField] != null && r[lngField] != null)
    .map(r => ({
      id: r.id || r.pid,
      lat: parseFloat(r[latField]),
      lng: parseFloat(r[lngField]),
      label: r[labelField] || 'Unknown',
    }));

  const handlePinClick = (id: string) => {
    setSelectedId(id);
    onRecordClick?.(id);
  };

  // In a real implementation, you would integrate with a map library
  // (Leaflet, Mapbox GL, Google Maps, etc.)
  return (
    <div
      ref={mapRef}
      data-testid={`block-${block.id}`}
      style={{
        width: '100%',
        height: 400,
        border: '1px solid #e8e8e8',
        borderRadius: 8,
        overflow: 'hidden',
        position: 'relative',
        backgroundColor: '#f0f2f5',
      }}
    >
      {/* Map container - replace with actual map library */}
      <div style={{ padding: 16 }}>
        <h3 style={{ margin: '0 0 12px 0', fontSize: 14, color: '#666' }}>
          Map View ({pins.length} locations)
        </h3>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {pins.map(pin => (
            <button
              key={pin.id}
              onClick={() => handlePinClick(pin.id)}
              style={{
                padding: '6px 12px',
                border: selectedId === pin.id ? '2px solid #1890ff' : '1px solid #d9d9d9',
                borderRadius: 4,
                backgroundColor: selectedId === pin.id ? '#e6f7ff' : '#fff',
                cursor: 'pointer',
                fontSize: 13,
              }}
            >
              {pin.label} ({pin.lat.toFixed(2)}, {pin.lng.toFixed(2)})
            </button>
          ))}
        </div>
        {pins.length === 0 && (
          <p style={{ color: '#999', fontSize: 13 }}>
            No records with location data. Configure {latField} and {lngField} fields.
          </p>
        )}
      </div>
    </div>
  );
};

export default MapViewBlock;
```

### Registering a Block Renderer

In your plugin's `plugin.json`, declare the frontend component mapping:

```json
{
  "frontend": {
    "remoteEntry": "frontend/remoteEntry.js",
    "modules": {
      "blocks": {
        "map-view": {
          "module": "./MapViewBlock",
          "name": "Map View",
          "description": "Displays records as pins on an interactive map",
          "configSchema": [
            { "key": "latField", "label": "Latitude Field", "type": "field-select", "required": true },
            { "key": "lngField", "label": "Longitude Field", "type": "field-select", "required": true },
            { "key": "labelField", "label": "Label Field", "type": "field-select", "required": true },
            { "key": "zoom", "label": "Default Zoom", "type": "number", "defaultValue": 10 }
          ]
        }
      }
    }
  }
}
```

Once registered, the `map-view` block type becomes available in the Page Designer. Users can drag it onto a page and configure which fields to use for coordinates and labels.

---

## Custom Dashboard Widgets

Dashboard widgets are specialized visualization components for the Dashboard Designer.

### Widget Interface

```typescript
interface WidgetProps {
  widget: {
    id: string;
    widgetType: string;
    title?: string;
    dataSource?: {
      modelCode: string;
      filters?: Array<{ field: string; operator: string; value: any }>;
    };
    // Custom config
    [key: string]: any;
  };
  data: Array<Record<string, any>>;
  width: number;
  height: number;
}
```

### Example: Heatmap Widget

**`src/widgets/HeatmapWidget.tsx`**:

```tsx
import React from 'react';

interface HeatmapWidgetProps {
  widget: {
    id: string;
    widgetType: string;
    title?: string;
    xField: string;
    yField: string;
    valueField: string;
    colorScale?: string[];
  };
  data: Array<Record<string, any>>;
  width: number;
  height: number;
}

const HeatmapWidget: React.FC<HeatmapWidgetProps> = ({
  widget,
  data,
  width,
  height,
}) => {
  const { title, xField, yField, valueField } = widget;
  const colorScale = widget.colorScale || ['#f0f0f0', '#1890ff', '#003a8c'];

  // Build heatmap grid from data
  const xValues = [...new Set(data.map(d => String(d[xField])))];
  const yValues = [...new Set(data.map(d => String(d[yField])))];

  const maxValue = Math.max(...data.map(d => Number(d[valueField]) || 0), 1);

  const getColor = (value: number) => {
    const ratio = value / maxValue;
    const idx = Math.min(Math.floor(ratio * (colorScale.length - 1)), colorScale.length - 1);
    return colorScale[idx];
  };

  const cellSize = Math.min(
    (width - 100) / Math.max(xValues.length, 1),
    (height - 80) / Math.max(yValues.length, 1),
    40
  );

  return (
    <div data-testid={`widget-${widget.id}`} style={{ width, height, padding: 16 }}>
      {title && <h4 style={{ margin: '0 0 12px 0' }}>{title}</h4>}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {yValues.map(y => (
          <div key={y} style={{ display: 'flex', gap: 2, alignItems: 'center' }}>
            <span style={{ width: 60, fontSize: 11, textAlign: 'right', paddingRight: 8 }}>
              {y}
            </span>
            {xValues.map(x => {
              const record = data.find(d => String(d[xField]) === x && String(d[yField]) === y);
              const value = record ? Number(record[valueField]) || 0 : 0;
              return (
                <div
                  key={`${x}-${y}`}
                  title={`${x}, ${y}: ${value}`}
                  style={{
                    width: cellSize,
                    height: cellSize,
                    backgroundColor: getColor(value),
                    borderRadius: 2,
                  }}
                />
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
};

export default HeatmapWidget;
```

---

## Custom Field Renderers

Field renderers replace the default input component for a specific field type in forms.

### Field Renderer Interface

```typescript
interface FieldRendererProps {
  value: any;
  onChange: (value: any) => void;
  field: {
    code: string;
    dataType: string;
    label: string;
    required?: boolean;
    readOnly?: boolean;
    extension?: Record<string, any>;
  };
  disabled?: boolean;
  error?: string;
}
```

### Example: Code Editor Field

**`src/fields/CodeEditorField.tsx`**:

```tsx
import React, { useCallback } from 'react';

interface CodeEditorFieldProps {
  value: any;
  onChange: (value: any) => void;
  field: {
    code: string;
    label: string;
    required?: boolean;
    readOnly?: boolean;
    extension?: {
      language?: string;
      minHeight?: number;
    };
  };
  disabled?: boolean;
  error?: string;
}

const CodeEditorField: React.FC<CodeEditorFieldProps> = ({
  value,
  onChange,
  field,
  disabled,
  error,
}) => {
  const language = field.extension?.language || 'json';
  const minHeight = field.extension?.minHeight || 200;

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange(e.target.value);
    },
    [onChange]
  );

  return (
    <div data-testid={`field-${field.code}`}>
      <textarea
        value={value || ''}
        onChange={handleChange}
        disabled={disabled || field.readOnly}
        placeholder={`Enter ${language} code...`}
        style={{
          width: '100%',
          minHeight,
          fontFamily: 'monospace',
          fontSize: 13,
          lineHeight: '1.5',
          padding: 12,
          border: error ? '1px solid #ff4d4f' : '1px solid #d9d9d9',
          borderRadius: 6,
          backgroundColor: disabled ? '#f5f5f5' : '#fafafa',
          resize: 'vertical',
          tabSize: 2,
        }}
      />
      {error && (
        <span style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4, display: 'block' }}>
          {error}
        </span>
      )}
    </div>
  );
};

export default CodeEditorField;
```

---

## Build

Build the frontend plugin:

```bash
npx vite build
```

The build output is in `dist/`:

```
dist/
+-- assets/
|   +-- index-abc123.js
|   +-- index-abc123.css
+-- remoteEntry.js           # Module Federation entry point
```

---

## Registration and Discovery

### In a Unified Package

Place the build output in the `frontend/` directory of your plugin package:

```
my-plugin.zip
+-- plugin.json
+-- config/          # Optional: DSL config
+-- backend/         # Optional: PF4J JAR
+-- frontend/
    +-- remoteEntry.js
    +-- assets/
        +-- index-abc123.js
        +-- index-abc123.css
```

The platform reads the `frontend` section of `plugin.json` to register your components:

```json
{
  "pluginId": "com.example.map-plugin",
  "namespace": "map",
  "version": "1.0.0",
  "pluginType": "hybrid",
  "frontend": {
    "remoteEntry": "frontend/remoteEntry.js",
    "modules": {
      "blocks": {
        "map-view": {
          "module": "./MapViewBlock",
          "name": "Map View"
        }
      },
      "widgets": {
        "heatmap": {
          "module": "./HeatmapWidget",
          "name": "Heatmap"
        }
      },
      "fields": {
        "code-editor": {
          "module": "./CodeEditorField",
          "name": "Code Editor"
        }
      }
    }
  }
}
```

### Component Types

| Type | Where It Appears | Loaded By |
|------|-----------------|-----------|
| `blocks` | Page Designer, Dynamic pages | `BlockRenderer` |
| `widgets` | Dashboard Designer | `WidgetRenderer` |
| `fields` | Form pages, via `extension.renderComponent` | `FieldRenderer` |

### Using Custom Components in DSL

After registration, reference your components in page schemas:

**Custom block in a list page:**
```json
{
  "id": "location_map",
  "blockType": "map-view",
  "latField": "tt_latitude",
  "lngField": "tt_longitude",
  "labelField": "tt_name"
}
```

**Custom field renderer:**
```json
{
  "code": "tt_config_json",
  "dataType": "text",
  "extension": {
    "renderComponent": "code-editor",
    "language": "json",
    "minHeight": 300
  }
}
```

---

## Development Workflow

### Local Development

During development, run the plugin dev server alongside the platform:

```bash
# Terminal 1: AuraBoot platform
cd auraboot-enterprise/web-admin && pnpm dev:full

# Terminal 2: Plugin dev server
cd my-frontend-plugin && npx vite --port 3001
```

Configure the platform to load your remote module from the dev server by updating the plugin registry to point to `http://localhost:3001/remoteEntry.js`.

### Error Boundaries

The platform wraps all remote components in error boundaries. If your component throws, it shows a fallback UI instead of crashing the entire page. During development, check the browser console for error details.

### Shared Dependencies

React and ReactDOM are shared singletons. Do not bundle them in your plugin. If you need additional shared libraries (e.g., `antd`), add them to the `shared` config in `vite.config.ts`.

---

## Best Practices

1. **Keep bundles small**: Only include code specific to your plugin. Shared libraries are provided by the host.
2. **Use `data-testid` attributes**: Add test IDs to your components for E2E testing.
3. **Handle loading and error states**: Show skeleton/spinner while data loads, show error messages on failure.
4. **Respect the theme**: Use CSS custom properties from the platform's design system for colors, spacing, and typography.
5. **Support i18n**: Accept localized labels from props rather than hardcoding text.
6. **Test in isolation first**: Use Storybook or a standalone test page before integrating with the platform.

---

## Next Steps

- [Config-Only Plugin Tutorial](./config-only-plugin.md) -- for standard CRUD features
- [Backend Plugin Guide](./backend-plugin.md) -- for custom server-side logic
- [Full-Stack Plugin Guide](./full-stack-plugin.md) -- combining all three layers
- [Plugin Manifest Reference](./plugin-manifest-reference.md) -- complete plugin.json schema
