#!/usr/bin/env node
/**
 * Build-time script: exports ComponentConfigs.ts → component-schemas.json
 *
 * Usage:
 *   node scripts/export-component-schemas.mjs
 *
 * Output:
 *   platform/src/main/resources/component-schemas.json
 */

import { readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..');
const PLATFORM_RESOURCES = resolve(ROOT, '..', 'platform', 'src', 'main', 'resources');

// Resolve SHARED_PROPERTIES references used in propertySchema arrays
const SHARED_PROPS = {
  'SHARED_PROPERTIES.WIDTH_PROPERTY': {
    key: 'width',
    label: 'Width (Grid columns)',
    type: 'number',
    group: 'basic',
    min: 1,
    max: 12,
    description: 'Number of grid columns to occupy; 12 fills the full row'
  },
  'SHARED_PROPERTIES.HEIGHT_PROPERTY': {
    key: 'height',
    label: 'Height',
    type: 'string',
    group: 'basic',
    description: 'Height value, e.g.: auto, 100px, 50vh'
  },
  'SHARED_PROPERTIES.APPEARANCE_PROPERTIES.BACKGROUND_COLOR': {
    key: 'backgroundColor',
    label: 'Background Color',
    type: 'color',
    group: 'appearance'
  },
  'SHARED_PROPERTIES.APPEARANCE_PROPERTIES.BORDER_RADIUS': {
    key: 'borderRadius',
    label: 'Border Radius',
    type: 'number',
    group: 'appearance',
    min: 0,
    max: 50,
    description: 'Border radius in px'
  },
  'SHARED_PROPERTIES.APPEARANCE_PROPERTIES.BORDER': {
    key: 'border',
    label: 'Border',
    type: 'string',
    group: 'appearance',
    description: 'Border style, e.g.: 1px solid #ccc'
  },
  'SHARED_PROPERTIES.APPEARANCE_PROPERTIES.BOX_SHADOW': {
    key: 'boxShadow',
    label: 'Box Shadow',
    type: 'string',
    group: 'appearance',
    description: 'Shadow effect, e.g.: 0 2px 4px rgba(0,0,0,0.1)'
  },
  'SHARED_PROPERTIES.SPACING_PROPERTIES.PADDING': {
    key: 'padding',
    label: 'Padding',
    type: 'number',
    group: 'spacing',
    min: 0,
    max: 100,
    description: 'Padding in px'
  },
  'SHARED_PROPERTIES.SPACING_PROPERTIES.MARGIN': {
    key: 'margin',
    label: 'Margin',
    type: 'number',
    group: 'spacing',
    min: 0,
    max: 100,
    description: 'Margin in px'
  }
};

/**
 * Data-type compatibility mapping.
 * Maps each component type → list of compatible DataType codes.
 */
const COMPATIBLE_DATA_TYPES = {
  'input': ['STRING'],
  'textarea': ['TEXT', 'STRING'],
  'select': ['ENUM', 'STRING'],
  'checkbox': ['BOOLEAN'],
  'radio': ['ENUM', 'STRING'],
  'datepicker': ['DATE', 'DATETIME'],
  'formref': ['REFERENCE'],
  'timepicker': ['STRING'],
  'switch': ['BOOLEAN'],
  'numberinput': ['INTEGER', 'DECIMAL'],
  'upload': ['STRING', 'JSON'],
  'display': ['STRING', 'TEXT'],
  'image': ['STRING'],
  'table': ['JSON'],
  'button': [],
  'div': [],
  'form': [],
  'navigation': [],
  'container': [],
  'grid': [],
  'flex': [],
  'columns': [],
  'card': [],
  'date': ['DATE'],
  'datetime': ['DATETIME'],
  'smart-number-card': ['INTEGER', 'DECIMAL'],
  'smart-bar-chart': [],
  'smart-line-chart': [],
  'smart-pie-chart': [],
  'smart-kanban': [],
  'bar-chart': [],
  'line-chart': [],
  'pie-chart': [],
  'area-chart': []
};

/**
 * Hardcoded component list extracted from ComponentConfigs.ts.
 * We maintain this statically because the TS file uses non-trivial
 * runtime expressions (spread references to SHARED_PROPERTIES, etc.)
 * that cannot be reliably parsed without a full TS evaluator.
 */
function buildComponentSchemas() {
  const source = readFileSync(
    resolve(ROOT, 'app', 'meta', 'registry', 'components', 'ComponentConfigs.ts'),
    'utf-8'
  );

  // Quick-parse: extract component blocks via regex
  // Each component starts with `  {\n    type: '...',`
  const componentPattern = /\{\s*\n\s*type:\s*'([^']+)',\s*\n\s*name:\s*'([^']+)',\s*\n\s*category:\s*'([^']+)',\s*\n\s*icon:\s*'([^']*)',\s*\n\s*description:\s*'([^']*)'/g;

  const components = {};
  let match;
  while ((match = componentPattern.exec(source)) !== null) {
    const [, type, name, category, icon, description] = match;
    components[type] = {
      name,
      category,
      icon,
      description,
      compatibleDataTypes: COMPATIBLE_DATA_TYPES[type] || [],
      properties: {}
    };
  }

  // For each component, extract propertySchema entries
  // Pattern: { key: 'xxx', label: 'yyy', type: 'zzz', ...rest }
  const propPattern = /\{\s*key:\s*'([^']+)',\s*label:\s*'([^']+)',\s*type:\s*'([^']+)'([^}]*)\}/g;

  // We need to associate properties with the right component.
  // Strategy: find each `propertySchema: [` and parse until `]`
  const psPattern = /type:\s*'([^']+)',[\s\S]*?propertySchema:\s*\[([\s\S]*?)\]\s*,?\s*\n\s*(tags|validation|dependencies)/g;
  let psMatch;

  while ((psMatch = psPattern.exec(source)) !== null) {
    const compType = psMatch[1];
    const propsBlock = psMatch[2];

    if (!components[compType]) continue;

    // Parse inline property objects
    const inlinePropPattern = /\{\s*key:\s*'([^']+)',\s*label:\s*'([^']+)',\s*type:\s*'([^']+)'([^}]*)\}/g;
    let pm;
    while ((pm = inlinePropPattern.exec(propsBlock)) !== null) {
      const [, key, label, propType, rest] = pm;
      const prop = { type: propType, description: label };

      // Extract optional fields
      const requiredMatch = rest.match(/required:\s*(true|false)/);
      if (requiredMatch) prop.required = requiredMatch[1] === 'true';

      const groupMatch = rest.match(/group:\s*'([^']+)'/);
      if (groupMatch) prop.group = groupMatch[1];

      const minMatch = rest.match(/min:\s*([\d.]+)/);
      if (minMatch) prop.min = parseFloat(minMatch[1]);

      const maxMatch = rest.match(/max:\s*([\d.]+)/);
      if (maxMatch) prop.max = parseFloat(maxMatch[1]);

      const descMatch = rest.match(/description:\s*'([^']*)'/);
      if (descMatch) prop.description = descMatch[1];

      components[compType].properties[key] = prop;
    }

    // Handle SHARED_PROPERTIES references
    for (const [ref, resolved] of Object.entries(SHARED_PROPS)) {
      if (propsBlock.includes(ref)) {
        components[compType].properties[resolved.key] = {
          type: resolved.type,
          description: resolved.description || resolved.label,
          ...(resolved.group && { group: resolved.group }),
          ...(resolved.min !== undefined && { min: resolved.min }),
          ...(resolved.max !== undefined && { max: resolved.max })
        };
      }
    }
  }

  return components;
}

const components = buildComponentSchemas();
const output = {
  version: '1.0',
  generatedAt: new Date().toISOString(),
  totalComponents: Object.keys(components).length,
  components
};

const outPath = resolve(PLATFORM_RESOURCES, 'component-schemas.json');
writeFileSync(outPath, JSON.stringify(output, null, 2), 'utf-8');

console.log(`Exported ${output.totalComponents} component schemas to ${outPath}`);
