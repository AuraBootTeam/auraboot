/**
 * Widget Registry — auto-registration of all built-in form widgets
 *
 * Call `registerAllWidgets()` once at app bootstrap (e.g. in StudioProvider or
 * the Page Designer entry point) to populate the WidgetRegistry singleton with
 * all 11 built-in widget definitions.
 *
 * @since 4.3.0
 */

import { WidgetRegistry } from '../widget-registry';

import { textWidget } from './text';
import { textareaWidget } from './textarea';
import { numberWidget } from './number';
import { selectWidget } from './select';
import { dateWidget } from './date';
import { datetimeWidget } from './datetime';
import { checkboxWidget } from './checkbox';
import { switchWidget } from './switch';
import { radioWidget } from './radio';
import { fileWidget } from './file';
import { referenceWidget } from './reference';

/**
 * Register all 11 built-in form widget definitions into the WidgetRegistry.
 * Safe to call multiple times — subsequent calls overwrite existing entries
 * (idempotent by component key).
 */
export function registerAllWidgets(): void {
  WidgetRegistry.register(textWidget);
  WidgetRegistry.register(textareaWidget);
  WidgetRegistry.register(numberWidget);
  WidgetRegistry.register(selectWidget);
  WidgetRegistry.register(dateWidget);
  WidgetRegistry.register(datetimeWidget);
  WidgetRegistry.register(checkboxWidget);
  WidgetRegistry.register(switchWidget);
  WidgetRegistry.register(radioWidget);
  WidgetRegistry.register(fileWidget);
  WidgetRegistry.register(referenceWidget);
}

// Named re-exports for consumers that want direct access to individual definitions
export {
  textWidget,
  textareaWidget,
  numberWidget,
  selectWidget,
  dateWidget,
  datetimeWidget,
  checkboxWidget,
  switchWidget,
  radioWidget,
  fileWidget,
  referenceWidget,
};
