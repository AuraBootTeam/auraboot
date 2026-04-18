/**
 * Widget Registry — auto-registration of all built-in form widgets
 *
 * Call `registerAllWidgets()` once at app bootstrap (e.g. in StudioProvider or
 * the Page Designer entry point) to populate the WidgetRegistry singleton with
 * all 29 built-in widget definitions.
 *
 * @since 4.3.0
 */

import { WidgetRegistry } from '../widget-registry';

// Phase 4.3 — core widgets (11)
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

// Phase 4.4 — Phase W widgets (18)
import { multiselectWidget } from './multiselect';
import { progressWidget } from './progress';
import { ratingWidget } from './rating';
import { colorpickerWidget } from './colorpicker';
import { moneyinputWidget } from './moneyinput';
import { timepickerWidget } from './timepicker';
import { daterangeWidget } from './daterange';
import { timerangepickerWidget } from './timerangepicker';
import { cascadeselectWidget } from './cascadeselect';
import { treeselectWidget } from './treeselect';
import { userselectWidget } from './userselect';
import { memberpickerWidget } from './memberpicker';
import { organizationselectWidget } from './organizationselect';
import { coordinatespickerWidget } from './coordinatespicker';
import { aifieldWidget } from './aifield';
import { addressfieldWidget } from './addressfield';
import { richtextWidget } from './richtext';
import { fileattachmentWidget } from './fileattachment';

/**
 * Register all 29 built-in form widget definitions into the WidgetRegistry.
 * Safe to call multiple times — subsequent calls overwrite existing entries
 * (idempotent by component key).
 */
export function registerAllWidgets(): void {
  // Phase 4.3 core widgets
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

  // Phase 4.4 — Phase W widgets
  WidgetRegistry.register(multiselectWidget);
  WidgetRegistry.register(progressWidget);
  WidgetRegistry.register(ratingWidget);
  WidgetRegistry.register(colorpickerWidget);
  WidgetRegistry.register(moneyinputWidget);
  WidgetRegistry.register(timepickerWidget);
  WidgetRegistry.register(daterangeWidget);
  WidgetRegistry.register(timerangepickerWidget);
  WidgetRegistry.register(cascadeselectWidget);
  WidgetRegistry.register(treeselectWidget);
  WidgetRegistry.register(userselectWidget);
  WidgetRegistry.register(memberpickerWidget);
  WidgetRegistry.register(organizationselectWidget);
  WidgetRegistry.register(coordinatespickerWidget);
  WidgetRegistry.register(aifieldWidget);
  WidgetRegistry.register(addressfieldWidget);
  WidgetRegistry.register(richtextWidget);
  WidgetRegistry.register(fileattachmentWidget);
}

// Named re-exports for consumers that want direct access to individual definitions

// Phase 4.3 core widgets
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

// Phase 4.4 — Phase W widgets
export {
  multiselectWidget,
  progressWidget,
  ratingWidget,
  colorpickerWidget,
  moneyinputWidget,
  timepickerWidget,
  daterangeWidget,
  timerangepickerWidget,
  cascadeselectWidget,
  treeselectWidget,
  userselectWidget,
  memberpickerWidget,
  organizationselectWidget,
  coordinatespickerWidget,
  aifieldWidget,
  addressfieldWidget,
  richtextWidget,
  fileattachmentWidget,
};
