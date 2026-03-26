/**
 * Component Validator — validates field type / component compatibility
 *
 * Rules:
 * - BP_CUSTOM_NO_COMP: custom block must specify a component name
 * - COMP_UNKNOWN: field.component not found in known component registry
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ValidationMessage } from '../types';

/** Known smart components from the component registry */
const KNOWN_COMPONENTS = new Set([
  // Form components
  'SmartInput',
  'SmartTextArea',
  'SmartNumber',
  'SmartSelect',
  'SmartSwitch',
  'SmartCheckbox',
  'SmartRadio',
  'SmartDatePicker',
  'SmartTimePicker',
  'SmartDateRangePicker',
  'SmartColorPicker',
  'SmartRating',
  'SmartSlider',
  'SmartUpload',
  'SmartRichTextEditor',
  'SmartCodeEditor',
  'SmartCascader',
  'SmartTreeSelect',
  'SmartAutoComplete',
  // Display components
  'SmartTag',
  'SmartBadge',
  'SmartAvatar',
  'SmartProgress',
  'SmartStatistic',
  'SmartCountdown',
  // Layout components
  'SmartDivider',
  // Chart components
  'SmartBarChart',
  'SmartLineChart',
  'SmartPieChart',
  'SmartAreaChart',
  'SmartRadarChart',
  'SmartScatterChart',
  'SmartFunnelChart',
  'SmartGaugeChart',
  'SmartHeatmapChart',
  'SmartTreemapChart',
  'SmartMapChart',
  'SmartSPCChart',
  'SmartParetoChart',
  'SmartGanttChart',
  'SmartTableChart',
]);

export function validateComponents(schema: UnifiedSchema): ValidationMessage[] {
  const messages: ValidationMessage[] = [];

  if (!schema.areas) return messages;

  for (const [areaId, area] of Object.entries(schema.areas)) {
    for (const [blockIdx, block] of (area.blocks || []).entries()) {
      const bp = `areas.${areaId}.blocks[${blockIdx}]`;

      // Check custom block has component
      if (block.blockType === 'custom' && !block.component) {
        messages.push({
          code: 'bp_custom_no_comp',
          path: `${bp}.component`,
          message: 'Custom block must specify a component name',
          severity: 'error',
        });
      }

      // Check field components
      for (const [fi, field] of (block.fields || []).entries()) {
        if (field.component && !KNOWN_COMPONENTS.has(field.component)) {
          messages.push({
            code: 'comp_unknown',
            path: `${bp}.fields[${fi}].component`,
            message: `Unknown component "${field.component}" — not in component registry`,
            severity: 'warning',
            suggestion: `Available components: ${[...KNOWN_COMPONENTS]
              .filter((c) => c.startsWith('Smart'))
              .slice(0, 5)
              .join(', ')}...`,
          });
        }
      }
    }
  }

  return messages;
}
