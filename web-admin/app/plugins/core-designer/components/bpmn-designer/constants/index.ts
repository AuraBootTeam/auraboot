/**
 * BPMN designer constant definitions.
 *
 * NOTE: Palette label/description and DEFAULT_NODE_CONFIGS.name fields below
 * are English fallbacks only. Runtime labels are resolved through i18n in
 * BPMNPalette via PALETTE_ITEM_I18N. Do not hardcode localized text here.
 */

import { BPMNNodeType, type BPMNPaletteItem } from '~/plugins/core-designer/components/bpmn-designer/types';

// BPMN node style configuration
export const BPMN_NODE_STYLES = {
  [BPMNNodeType.START_EVENT]: {
    width: 36,
    height: 36,
    borderRadius: '50%',
    borderWidth: 2,
    borderColor: '#22c55e',
    backgroundColor: '#f0fdf4',
  },
  [BPMNNodeType.END_EVENT]: {
    width: 36,
    height: 36,
    borderRadius: '50%',
    borderWidth: 3,
    borderColor: '#ef4444',
    backgroundColor: '#fef2f2',
  },
  [BPMNNodeType.USER_TASK]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#3b82f6',
    backgroundColor: '#eff6ff',
  },
  [BPMNNodeType.SERVICE_TASK]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#8b5cf6',
    backgroundColor: '#f5f3ff',
  },
  [BPMNNodeType.RECEIVE_TASK]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#06b6d4',
    backgroundColor: '#ecfeff',
  },
  [BPMNNodeType.EXCLUSIVE_GATEWAY]: {
    width: 50,
    height: 50,
    borderRadius: 0,
    borderWidth: 2,
    borderColor: '#f59e0b',
    backgroundColor: '#fffbeb',
  },
  [BPMNNodeType.PARALLEL_GATEWAY]: {
    width: 50,
    height: 50,
    borderRadius: 0,
    borderWidth: 2,
    borderColor: '#3b82f6',
    backgroundColor: '#eff6ff',
  },
  [BPMNNodeType.INCLUSIVE_GATEWAY]: {
    width: 50,
    height: 50,
    borderRadius: 0,
    borderWidth: 2,
    borderColor: '#8b5cf6',
    backgroundColor: '#f5f3ff',
  },
  [BPMNNodeType.CALL_ACTIVITY]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 3,
    borderColor: '#059669',
    backgroundColor: '#ecfdf5',
  },
};

// Palette items (label/description are English fallbacks; UI resolves via i18n)
export const BPMN_PALETTE_ITEMS: BPMNPaletteItem[] = [
  {
    type: BPMNNodeType.START_EVENT,
    label: 'Start Event',
    icon: '▶',
    category: 'event',
    description: 'Start node of the process',
  },
  {
    type: BPMNNodeType.END_EVENT,
    label: 'End Event',
    icon: '⬛',
    category: 'event',
    description: 'End node of the process',
  },
  {
    type: BPMNNodeType.USER_TASK,
    label: 'User Task',
    icon: '👤',
    category: 'task',
    description: 'Task that requires human action',
  },
  {
    type: BPMNNodeType.SERVICE_TASK,
    label: 'Service Task',
    icon: '⚙',
    category: 'task',
    description: 'Automated service task',
  },
  {
    type: BPMNNodeType.RECEIVE_TASK,
    label: 'Receive Task',
    icon: '📨',
    category: 'task',
    description: 'Task that waits for an incoming message',
  },
  {
    type: BPMNNodeType.EXCLUSIVE_GATEWAY,
    label: 'Exclusive Gateway',
    icon: '◆',
    category: 'gateway',
    description: 'Conditional branch — only one path is taken',
  },
  {
    type: BPMNNodeType.PARALLEL_GATEWAY,
    label: 'Parallel Gateway',
    icon: '＋',
    category: 'gateway',
    description: 'Parallel branch — all paths run concurrently',
  },
  {
    type: BPMNNodeType.INCLUSIVE_GATEWAY,
    label: 'Inclusive Gateway',
    icon: '○',
    category: 'gateway',
    description: 'Conditional branch — every matching path runs',
  },
  {
    type: BPMNNodeType.CALL_ACTIVITY,
    label: 'Call Activity',
    icon: '⧉',
    category: 'task' as const,
    description: 'Invoke another process definition',
  },
];

// Default node configs (name field is English fallback; canvas overrides with i18n label)
export const DEFAULT_NODE_CONFIGS = {
  [BPMNNodeType.START_EVENT]: {
    name: 'Start',
    description: '',
    initiator: 'initiator',
  },
  [BPMNNodeType.END_EVENT]: {
    name: 'End',
    description: '',
    terminateAll: false,
  },
  [BPMNNodeType.USER_TASK]: {
    name: 'User Task',
    description: '',
    assignee: {
      type: 'user' as const,
      multi: false,
      assigneeMode: 'single' as const,
    },
    priority: 50,
    skipable: false,
  },
  [BPMNNodeType.SERVICE_TASK]: {
    name: 'Service Task',
    description: '',
    serviceType: 'http' as const,
    async: false,
    priority: 50,
  },
  [BPMNNodeType.RECEIVE_TASK]: {
    name: 'Receive Task',
    description: '',
    priority: 50,
  },
  [BPMNNodeType.EXCLUSIVE_GATEWAY]: {
    name: 'Exclusive Gateway',
    description: '',
  },
  [BPMNNodeType.PARALLEL_GATEWAY]: {
    name: 'Parallel Gateway',
    description: '',
  },
  [BPMNNodeType.INCLUSIVE_GATEWAY]: {
    name: 'Inclusive Gateway',
    description: '',
  },
  [BPMNNodeType.CALL_ACTIVITY]: {
    name: 'Call Activity',
    description: '',
    calledProcessKey: '',
  },
};

// Edge styles
export const EDGE_STYLES = {
  default: {
    stroke: '#94a3b8',
    strokeWidth: 2,
  },
  selected: {
    stroke: '#3b82f6',
    strokeWidth: 3,
  },
  conditional: {
    stroke: '#f59e0b',
    strokeWidth: 2,
    strokeDasharray: '5,5',
  },
};

// Grid configuration
export const GRID_CONFIG = {
  size: 20,
  color: '#e2e8f0',
  style: 'dots' as const,
};

// Canvas configuration
export const CANVAS_CONFIG = {
  minZoom: 0.5,
  maxZoom: 2,
  defaultZoom: 1,
  snapToGrid: true,
  snapGrid: [20, 20] as [number, number],
};
