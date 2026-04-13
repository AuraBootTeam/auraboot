/**
 * Page Mode Service Module
 *
 * Three-mode page design system (Floor, Form, Grid).
 */

export { PageModeService, pageModeService, default } from './PageModeService';
export { PageModeSelector } from './PageModeSelector';
export {
  PAGE_MODES,
  getModeConfig,
  getAllModes,
  modeSupports,
  getModeByKind,
  FORM_COLUMN_PRESETS,
  LABEL_POSITIONS,
} from './modes';

export type {
  PageMode,
  PageModeConfig,
  ModeStructure,
  ModeCapabilities,
  ModeLayoutConfig,
  FormLayoutConfig,
  ModeSwitchEvent,
  DragItem,
  DropTarget,
  MigrationResult,
} from './types';
