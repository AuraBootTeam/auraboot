/**
 * Block definitions barrel — registers all 14 built-in block types.
 *
 * Call registerAllBlocks() once at app bootstrap (e.g. in studio entry point)
 * before any canvas or palette component is rendered.
 *
 * @since 4.3.0
 */

import { BlockRegistry } from '../block-registry';

import { tableBlock } from './table';
import { chartBlock } from './chart';
import { formSectionBlock } from './form-section';
import { toolbarBlock } from './toolbar';
import { subTableBlock } from './sub-table';
import { tabsBlock } from './tabs';
import { statCardBlock } from './stat-card';
import { monthlyGridBlock } from './monthly-grid';
import { filtersBlock } from './filters';
import { detailSectionBlock } from './detail-section';
import { richTextBlock } from './rich-text';
import { dividerBlock } from './divider';
import { formButtonsBlock } from './form-buttons';
import { bpmPanelBlock } from './bpm-panel';

export function registerAllBlocks(): void {
  BlockRegistry.register(tableBlock);
  BlockRegistry.register(chartBlock);
  BlockRegistry.register(formSectionBlock);
  BlockRegistry.register(toolbarBlock);
  BlockRegistry.register(subTableBlock);
  BlockRegistry.register(tabsBlock);
  BlockRegistry.register(statCardBlock);
  BlockRegistry.register(monthlyGridBlock);
  BlockRegistry.register(filtersBlock);
  BlockRegistry.register(detailSectionBlock);
  BlockRegistry.register(richTextBlock);
  BlockRegistry.register(dividerBlock);
  BlockRegistry.register(formButtonsBlock);
  BlockRegistry.register(bpmPanelBlock);
}

export {
  tableBlock,
  chartBlock,
  formSectionBlock,
  toolbarBlock,
  subTableBlock,
  tabsBlock,
  statCardBlock,
  monthlyGridBlock,
  filtersBlock,
  detailSectionBlock,
  richTextBlock,
  dividerBlock,
  formButtonsBlock,
  bpmPanelBlock,
};
