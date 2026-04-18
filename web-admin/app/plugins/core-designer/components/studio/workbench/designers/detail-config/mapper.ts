import type { DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';

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
}

export interface DetailViewModel {
  sections: SectionConfig[];
  actions: {
    presets: ('edit' | 'delete')[];
    customButtons: CustomButton[];
  };
}

let sectionIdCounter = 0;
export function makeSectionId(): string {
  sectionIdCounter += 1;
  return `section_${Date.now()}_${sectionIdCounter}`;
}

export function emptyDetailViewModel(): DetailViewModel {
  return { sections: [], actions: { presets: [], customButtons: [] } };
}

export function detailVmToBlocks(vm: DetailViewModel): DslBlock[] {
  const blocks: DslBlock[] = [];

  // Top actions toolbar (if any)
  const buttons = [
    ...vm.actions.presets.map((p) => ({ preset: p })),
    ...vm.actions.customButtons.map(serializeCustomButton),
  ];
  if (buttons.length > 0) {
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

  return blocks;
}

export function blocksToDetailVm(blocks: DslBlock[] | undefined): DetailViewModel {
  const list = blocks ?? [];

  // Top toolbar (if present and at index 0, or anywhere matching "actions_top")
  const toolbar = list.find((b) => b.id === 'actions_top' || b.blockType === 'toolbar');
  const toolbarButtons = ((toolbar as any)?.buttons ?? []) as Array<{ preset?: string } & CustomButton>;
  const presets: ('edit' | 'delete')[] = toolbarButtons
    .filter((b) => b.preset)
    .map((b) => b.preset as any)
    .filter((p): p is 'edit' | 'delete' => p === 'edit' || p === 'delete');
  const customButtons: CustomButton[] = toolbarButtons
    .filter((b) => !b.preset)
    .map(parseCustomButton);

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
    actions: { presets, customButtons },
  };
}

function normalizeColumns(c: unknown): 1 | 2 | 3 | 4 {
  const n = typeof c === 'number' ? c : parseInt(String(c), 10);
  if (n === 1 || n === 2 || n === 3 || n === 4) return n;
  return 2;
}

function serializeCustomButton(b: CustomButton): Record<string, unknown> {
  return {
    label: b.label,
    ...(b.icon ? { icon: b.icon } : {}),
    command: b.command,
    ...(b.requiresSelection ? { requiresSelection: true } : {}),
  };
}

function parseCustomButton(b: unknown): CustomButton {
  if (b && typeof b === 'object') {
    const obj = b as Record<string, unknown>;
    const button: CustomButton = {
      label: String(obj.label ?? ''),
      command: String(obj.command ?? ''),
    };
    if (obj.icon) button.icon = String(obj.icon);
    if (obj.requiresSelection) button.requiresSelection = true;
    return button;
  }
  return { label: String(b), command: '' };
}
