/**
 * Registry initializer — call once at app bootstrap to populate
 * WidgetRegistry and BlockRegistry with all built-in definitions.
 *
 * Subsequent calls are no-ops (guarded by `initialized` flag).
 *
 * @since 4.3.0
 */

import { registerAllWidgets } from './widgets';
import { registerAllBlocks } from './blocks';

let initialized = false;

export function initRegistry(): void {
  if (initialized) return;
  registerAllWidgets();
  registerAllBlocks();
  initialized = true;
}
