import {
  resolveLocalizedText,
  type DslBlock,
} from '~/plugins/core-designer/components/studio/domain/dsl/types';

export interface SectionConfig {
  id: string;
  title: string;
  columns: 1 | 2 | 3 | 4;
  fields: string[];
  collapsible?: boolean;
  defaultCollapsed?: boolean;
}

export interface CustomButton {
  label: string;
  icon?: string;
  command: string;
  requiresSelection?: boolean;
  raw?: Record<string, unknown>;
}

export interface DetailViewModel {
  sections: SectionConfig[];
  actions: {
    presets: ('edit' | 'delete')[];
    presetRaw?: Partial<Record<'edit' | 'delete', Record<string, unknown>>>;
    customButtons: CustomButton[];
  };
  passthroughBlocks: DslBlock[];
  originalToolbarBlock?: DslBlock;
  originalToolbarSignature?: string;
}

let sectionIdCounter = 0;
export function makeSectionId(): string {
  sectionIdCounter += 1;
  return `section_${Date.now()}_${sectionIdCounter}`;
}

export function emptyDetailViewModel(): DetailViewModel {
  return { sections: [], actions: { presets: [], customButtons: [] }, passthroughBlocks: [] };
}

export function detailVmToBlocks(vm: DetailViewModel): DslBlock[] {
  const blocks: DslBlock[] = [];

  // Top actions toolbar (if any)
  const buttons = [
    ...vm.actions.presets.map((p) => serializePresetButton(p, vm.actions.presetRaw?.[p])),
    ...vm.actions.customButtons.map(serializeCustomButton),
  ];
  const toolbarSignature = JSON.stringify({
    presets: vm.actions.presets,
    presetRaw: vm.actions.presetRaw,
    customButtons: vm.actions.customButtons,
  });
  if (vm.originalToolbarBlock && toolbarSignature === vm.originalToolbarSignature) {
    blocks.push(vm.originalToolbarBlock);
  } else if (buttons.length > 0) {
    blocks.push({
      id: 'actions_top',
      blockType: 'toolbar' as any,
      buttons: buttons as any,
    });
  }

  // Sections
  for (const s of vm.sections) {
    const block: DslBlock = {
      id: s.id,
      blockType: 'detail-section' as any,
      title: s.title as any,
      columns: s.columns as any,
      fields: s.fields as any,
    };
    if (s.collapsible) (block as any).collapsible = true;
    if (s.defaultCollapsed) (block as any).defaultCollapsed = true;
    blocks.push(block);
  }

  blocks.push(...(vm.passthroughBlocks ?? []));

  return blocks;
}

export function blocksToDetailVm(blocks: DslBlock[] | undefined): DetailViewModel {
  const list = blocks ?? [];

  // Top toolbar (if present and at index 0, or anywhere matching "actions_top")
  const toolbar = list.find((b) => b.id === 'actions_top' || b.blockType === 'toolbar');
  const toolbarButtons = ((toolbar as any)?.buttons ?? []) as Array<Record<string, unknown>>;
  const presets: ('edit' | 'delete')[] = [];
  const presetRaw: Partial<Record<'edit' | 'delete', Record<string, unknown>>> = {};
  const customButtons: CustomButton[] = [];
  for (const button of toolbarButtons) {
    const presetKey = detectPresetKey(button);
    if (presetKey && !presets.includes(presetKey)) {
      presets.push(presetKey);
      if (hasExtraPresetFields(button, presetKey)) {
        presetRaw[presetKey] = { ...button };
      }
      continue;
    }
    customButtons.push(parseCustomButton(button));
  }
  const passthroughBlocks: DslBlock[] = list.filter(
    (b) => b !== toolbar && b.blockType !== 'detail-section',
  );

  const sections: SectionConfig[] = list
    .filter((b) => b.blockType === 'detail-section')
    .map((b): SectionConfig => {
      const section: SectionConfig = {
        id: String((b as any).id ?? makeSectionId()),
        title: typeof (b as any).title === 'string' ? (b as any).title : String((b as any).title ?? ''),
        columns: normalizeColumns((b as any).columns),
        fields: Array.isArray((b as any).fields) ? (b as any).fields.map(String) : [],
      };
      if ((b as any).collapsible === true) section.collapsible = true;
      if ((b as any).defaultCollapsed === true) section.defaultCollapsed = true;
      return section;
    });

  return {
    sections,
    actions: {
      presets,
      ...(Object.keys(presetRaw).length > 0 ? { presetRaw } : {}),
      customButtons,
    },
    passthroughBlocks,
    originalToolbarBlock: toolbar,
    originalToolbarSignature: JSON.stringify({
      presets,
      presetRaw: Object.keys(presetRaw).length > 0 ? presetRaw : undefined,
      customButtons,
    }),
  };
}

function normalizeColumns(c: unknown): 1 | 2 | 3 | 4 {
  const n = typeof c === 'number' ? c : parseInt(String(c), 10);
  if (n === 1 || n === 2 || n === 3 || n === 4) return n;
  return 2;
}

function serializePresetButton(
  key: 'edit' | 'delete',
  raw: Record<string, unknown> | undefined,
): Record<string, unknown> {
  if (raw && typeof raw === 'object') {
    return { ...raw };
  }
  return key === 'edit'
    ? {
        code: 'edit',
        label: { 'zh-CN': '编辑', 'en-US': 'Edit' },
        variant: 'primary',
      }
    : {
        code: 'delete',
        label: { 'zh-CN': '删除', 'en-US': 'Delete' },
        variant: 'danger',
        danger: true,
      };
}

function serializeCustomButton(b: CustomButton): Record<string, unknown> {
  const out: Record<string, unknown> =
    b.raw && typeof b.raw === 'object'
      ? { ...b.raw }
      : { code: makeCustomButtonCode(b), variant: 'default' };

  out.label = b.label;
  if (b.icon) out.icon = b.icon;
  else delete out.icon;

  if (b.command) {
    out.command = b.command;
    out.commandCode = b.command;
    out.action = { type: 'command', command: b.command };
  } else {
    delete out.command;
    delete out.commandCode;
    delete out.action;
  }

  if (b.requiresSelection) out.requiresSelection = true;
  else delete out.requiresSelection;

  if (typeof out.code !== 'string' || !out.code) {
    out.code = makeCustomButtonCode(b);
  }
  return out;
}

function parseCustomButton(b: unknown): CustomButton {
  if (b && typeof b === 'object') {
    const obj = b as Record<string, unknown>;
    const button: CustomButton = {
      label: normalizeTextLike(obj.label) || normalizeTextLike(obj.code),
      command: extractCommandCode(obj),
    };
    const icon = normalizeIcon(obj.icon);
    if (icon) button.icon = icon;
    if (obj.requiresSelection) button.requiresSelection = true;
    const hasExtra = Object.keys(obj).some((k) => !CUSTOM_BUTTON_KEYS.has(k));
    if (hasExtra) button.raw = { ...obj };
    return button;
  }
  return { label: String(b), command: '' };
}

const CUSTOM_BUTTON_KEYS = new Set([
  'label',
  'command',
  'commandCode',
  'icon',
  'requiresSelection',
  'action',
  'code',
  'variant',
  'danger',
  'primary',
]);

function detectPresetKey(button: Record<string, unknown>): 'edit' | 'delete' | null {
  const preset = normalizePresetToken(button.preset);
  if (preset) return preset;

  const code = normalizePresetToken(button.code);
  if (code) return code;

  const label = normalizePresetToken(button.label);
  if (label) return label;

  return null;
}

function normalizePresetToken(value: unknown): 'edit' | 'delete' | null {
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase();
    if (normalized === 'edit' || normalized === '编辑') return 'edit';
    if (normalized === 'delete' || normalized === '删除') return 'delete';
  }
  if (value && typeof value === 'object') {
    const text = resolveLocalizedText(value as Record<string, string>);
    return normalizePresetToken(text);
  }
  return null;
}

function hasExtraPresetFields(
  button: Record<string, unknown>,
  key: 'edit' | 'delete',
): boolean {
  const trivialKeys = new Set(['preset', 'code', 'label', 'variant', 'danger']);
  for (const field of Object.keys(button)) {
    if (!trivialKeys.has(field)) return true;
    if (field === 'code' && String(button[field]) !== key) return true;
  }
  return false;
}

function extractCommandCode(obj: Record<string, unknown>): string {
  if (typeof obj.command === 'string') return obj.command;
  if (typeof obj.commandCode === 'string') return obj.commandCode;
  if (obj.action && typeof obj.action === 'object') {
    const action = obj.action as Record<string, unknown>;
    if (action.type === 'command' && typeof action.command === 'string') {
      return action.command;
    }
  }
  return '';
}

function normalizeTextLike(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object') {
    return resolveLocalizedText(value as Record<string, string>);
  }
  return '';
}

function normalizeIcon(value: unknown): string | undefined {
  if (typeof value === 'string' && value.trim()) return value;
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    if (typeof obj.name === 'string' && obj.name.trim()) return obj.name;
    if (typeof obj.code === 'string' && obj.code.trim()) return obj.code;
  }
  return undefined;
}

function makeCustomButtonCode(button: Pick<CustomButton, 'label' | 'command'>): string {
  const base = button.command || button.label || 'custom-action';
  const normalized = base
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return normalized || 'custom_action';
}
