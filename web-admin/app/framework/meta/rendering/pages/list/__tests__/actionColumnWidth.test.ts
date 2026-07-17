import { describe, it, expect } from 'vitest';
import {
  ACTION_COLUMN_MIN_WIDTH,
  ACTION_COLUMN_MAX_WIDTH,
  MAX_INLINE_ACTIONS,
  estimateActionColumnWidth,
  partitionRowActions,
} from '../actionColumnWidth';

const btn = (code: string, inline?: boolean) => ({ code, inline }) as any;

describe('partitionRowActions', () => {
  it('keeps the legacy layout when no button opts in', () => {
    // 33 E2E specs drive rows via `row-action-more`; the default must not move.
    const { inline, overflow } = partitionRowActions([btn('a'), btn('b'), btn('c')]);
    expect(inline.map((b) => b.code)).toEqual(['a']);
    expect(overflow.map((b) => b.code)).toEqual(['b', 'c']);
  });

  it('lays out the opted-in buttons inline and collapses the rest', () => {
    const { inline, overflow } = partitionRowActions([
      btn('edit_unified', true),
      btn('edit_legacy'),
      btn('publish', true),
      btn('delete'),
    ]);
    expect(inline.map((b) => b.code)).toEqual(['edit_unified', 'publish']);
    expect(overflow.map((b) => b.code)).toEqual(['edit_legacy', 'delete']);
  });

  it('caps inline buttons at the design-system limit', () => {
    const { inline, overflow } = partitionRowActions([
      btn('a', true),
      btn('b', true),
      btn('c', true),
      btn('d', true),
    ]);
    expect(inline).toHaveLength(MAX_INLINE_ACTIONS);
    expect(overflow.map((b) => b.code)).toEqual(['d']);
  });

  it('leaves no overflow when the only button is inline', () => {
    const { inline, overflow } = partitionRowActions([btn('only')]);
    expect(inline.map((b) => b.code)).toEqual(['only']);
    expect(overflow).toEqual([]);
  });
});

describe('estimateActionColumnWidth', () => {
  it('falls back to the minimum width for a short single action', () => {
    // "编辑" (2 CJK chars) fits well inside the minimum column.
    expect(estimateActionColumnWidth(['编辑'], { hasOverflow: false })).toBe(
      ACTION_COLUMN_MIN_WIDTH,
    );
  });

  it('widens the column so a long inline label is not clipped', () => {
    // Regression: "统一设计器" + the overflow trigger needed ~130px but the column
    // was pinned at 112px, so the button text wrapped onto a second line and
    // pushed the row from 44px to ~65px.
    const width = estimateActionColumnWidth(['统一设计器'], { hasOverflow: true });
    expect(width).toBeGreaterThan(ACTION_COLUMN_MIN_WIDTH);
    expect(width).toBeGreaterThanOrEqual(130);
  });

  it('reserves room for the overflow trigger', () => {
    const withOverflow = estimateActionColumnWidth(['标记已联系'], { hasOverflow: true });
    const withoutOverflow = estimateActionColumnWidth(['标记已联系'], { hasOverflow: false });
    expect(withOverflow).toBeGreaterThan(withoutOverflow);
  });

  it('accumulates the width of every inline button', () => {
    const one = estimateActionColumnWidth(['统一设计器'], { hasOverflow: true });
    const two = estimateActionColumnWidth(['统一设计器', '发布'], { hasOverflow: true });
    expect(two).toBeGreaterThan(one);
  });

  it('measures CJK wider than ASCII at the same character count', () => {
    // Long enough that both land above the minimum width, otherwise the floor
    // would flatten the difference we are asserting on.
    const cjk = estimateActionColumnWidth(['统一设计器统一设计器'], { hasOverflow: true });
    const ascii = estimateActionColumnWidth(['abcdefghij'], { hasOverflow: true });
    expect(cjk).toBeGreaterThan(ascii);
    expect(ascii).toBeGreaterThan(ACTION_COLUMN_MIN_WIDTH);
  });

  it('clamps absurdly long labels to the maximum width', () => {
    const width = estimateActionColumnWidth(['这是一个非常非常非常冗长的操作按钮名称'], {
      hasOverflow: true,
    });
    expect(width).toBe(ACTION_COLUMN_MAX_WIDTH);
  });

  it('never returns less than the minimum width', () => {
    expect(estimateActionColumnWidth([], { hasOverflow: false })).toBe(ACTION_COLUMN_MIN_WIDTH);
    expect(estimateActionColumnWidth([''], { hasOverflow: true })).toBe(ACTION_COLUMN_MIN_WIDTH);
  });

  // `统一设计器` (page-manager / page_schema_list) is the label that actually broke: it is
  // the first row-action of the only shipped `kind: list` page whose action column overflowed
  // the old hard-coded 112px. Pinned so the regression cannot return through it.
  //
  // The other two are labels of comparable length taken from real pages, kept as guards for
  // future long labels. They are NOT past victims: crm_lead_workbench and
  // faq_candidate_workbench are `kind: detail` (workbench) pages, whose tables render through
  // TableBlockRenderer, not ListPageContent — a different action-column implementation that
  // never had the fixed width. An earlier scan of this repo missed that distinction and
  // over-counted the blast radius as three pages; verified in a browser that both workbench
  // pages lay their row actions out inline and have never wrapped.
  it.each([
    ['统一设计器', 'page-manager / page_schema_list — the actual regression'],
    ['标记已联系', 'length guard (crm_lead_workbench renders via TableBlockRenderer)'],
    ['编辑问答', 'length guard (faq_candidate_workbench renders via TableBlockRenderer)'],
  ])('gives "%s" (%s) a column wide enough to render it unclipped', (label) => {
    const width = estimateActionColumnWidth([label], { hasOverflow: true });
    // Wider than the old fixed 112px — that is precisely what made the label wrap.
    expect(width).toBeGreaterThan(ACTION_COLUMN_MIN_WIDTH);
    // And not so long that it hits the ceiling and gets truncated.
    expect(width).toBeLessThan(ACTION_COLUMN_MAX_WIDTH);
  });
});
