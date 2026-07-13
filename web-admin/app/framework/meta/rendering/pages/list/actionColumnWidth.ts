/**
 * actionColumnWidth — sizes the sticky row-action column to its actual labels.
 *
 * The column used to be pinned at a hard-coded 112px. Any label wider than that
 * (e.g. "统一设计器") was squeezed by the flex layout and wrapped onto a second
 * line, which pushed the whole row from 44px to ~65px. Widths are estimated here
 * so a new page gets a correct column with zero DSL configuration; the wrapping
 * itself is separately made impossible by `whitespace-nowrap` on the buttons,
 * so a mis-estimate degrades to an ellipsis rather than a broken row.
 */

import type { ButtonConfig } from '~/framework/meta/schemas/types';

/** ux-design-system.md §3: at most 3 row actions are laid out inline. */
export const MAX_INLINE_ACTIONS = 3;

/**
 * Split row actions into the ones rendered inline and the ones collapsed into
 * the "⋮" menu. Both the renderer and the column-width estimator go through
 * here, so a column can never be sized for a layout it does not render.
 *
 * Buttons opt in with `inline: true`. With no opt-in we keep the historical
 * layout (first visible button inline, everything else in the menu) — 33 E2E
 * specs drive rows through `row-action-more`, and they must keep working.
 */
export function partitionRowActions(buttons: ButtonConfig[]): {
  inline: ButtonConfig[];
  overflow: ButtonConfig[];
} {
  const optedIn = buttons.filter((button) => button.inline);
  const inline = (optedIn.length > 0 ? optedIn : buttons.slice(0, 1)).slice(0, MAX_INLINE_ACTIONS);
  const inlineCodes = new Set(inline.map((button) => button.code));
  return { inline, overflow: buttons.filter((button) => !inlineCodes.has(button.code)) };
}

/** Floor — keeps narrow action columns visually aligned across pages. */
export const ACTION_COLUMN_MIN_WIDTH = 112;
/** Ceiling — a runaway label must not eat the whole table. */
export const ACTION_COLUMN_MAX_WIDTH = 240;

/** Horizontal padding of the action `<td>` (px-2 on each side). */
const CELL_PADDING_X = 16;
/** Horizontal padding of an inline action button (px-2 on each side). */
const BUTTON_PADDING_X = 16;
/** Gap between items in the action cell (gap-1). */
const ITEM_GAP = 4;
/** The "⋮" overflow trigger: 16px icon + p-1 on each side. */
const OVERFLOW_TRIGGER_WIDTH = 24;

/**
 * Advance width per character at text-sm/font-medium. Full-width scripts (CJK,
 * kana, hangul) render at roughly the font size; Latin averages about half.
 */
const CJK_CHAR_WIDTH = 14;
const ASCII_CHAR_WIDTH = 7.2;
const FULL_WIDTH_PATTERN = /[\u3000-\u9fff\uac00-\ud7af\uff00-\uffef]/;

function estimateTextWidth(label: string): number {
  let width = 0;
  for (const char of label) {
    width += FULL_WIDTH_PATTERN.test(char) ? CJK_CHAR_WIDTH : ASCII_CHAR_WIDTH;
  }
  return width;
}

/**
 * Estimate the width the action column needs to render `inlineLabels` on one line.
 *
 * @param inlineLabels i18n-resolved labels of the buttons rendered inline (not the
 *   ones collapsed into the overflow menu). Pass the DSL-declared set, not a single
 *   row's set — the column width is shared by every row.
 * @param hasOverflow whether the "⋮" trigger is rendered alongside them.
 */
export function estimateActionColumnWidth(
  inlineLabels: string[],
  { hasOverflow }: { hasOverflow: boolean },
): number {
  const buttons = inlineLabels.filter((label) => label.length > 0);

  const buttonsWidth = buttons.reduce(
    (total, label) => total + estimateTextWidth(label) + BUTTON_PADDING_X,
    0,
  );
  const gapCount = Math.max(0, buttons.length - 1) + (hasOverflow && buttons.length > 0 ? 1 : 0);

  const estimated =
    CELL_PADDING_X +
    buttonsWidth +
    gapCount * ITEM_GAP +
    (hasOverflow ? OVERFLOW_TRIGGER_WIDTH : 0);

  return Math.round(
    Math.min(ACTION_COLUMN_MAX_WIDTH, Math.max(ACTION_COLUMN_MIN_WIDTH, estimated)),
  );
}
