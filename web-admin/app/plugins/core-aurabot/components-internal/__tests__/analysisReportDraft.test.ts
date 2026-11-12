import { describe, it, expect } from 'vitest';
import { currentTurnNarrative, narrativeWithoutTables } from '../analysisReportDraft';
import type { SimpleMessage } from '../../components-shell/AuraBotProvider';
const msg = (
  sender: SimpleMessage['sender'],
  type: SimpleMessage['type'],
  content: string,
): SimpleMessage => ({ id: content, sender, type, content, timestamp: 1 });
describe('analysis report review draft', () => {
  it('does not borrow a previous or following customer turn', () => {
    const rows = [
      msg('user', 'text', 'Old customer'),
      msg('bot', 'text', 'Old result'),
      msg('user', 'text', 'Current customer'),
      msg('bot', 'result_contract', ''),
      msg('bot', 'thinking', 'private thought'),
      msg('bot', 'text', 'Current facts'),
      msg('user', 'text', 'Next customer'),
      msg('bot', 'text', 'Next result'),
    ];
    expect(currentTurnNarrative(rows, 3)).toBe('Current facts');
  });
  it('joins only assistant prose in the current turn', () =>
    expect(
      currentTurnNarrative(
        [
          msg('user', 'text', 'Q'),
          msg('bot', 'text', 'A'),
          msg('bot', 'result_contract', ''),
          msg('bot', 'text', 'B'),
        ],
        2,
      ),
    ).toBe('A\n\nB'));
  it('returns an empty draft instead of invented analysis', () =>
    expect(
      currentTurnNarrative([msg('user', 'text', 'Q'), msg('bot', 'result_contract', '')], 1),
    ).toBe(''));
  it('removes a duplicate table without deleting prose containing a pipe', () =>
    expect(
      narrativeWithoutTables(
        'Facts A | B\n\n| Name | Amount |\n| --- | ---: |\n| A | 10 |\n\nReview.',
      ),
    ).toBe('Facts A | B\n\nReview.'));
  it('preserves fenced source and non-table text', () => {
    const s = '```text\n| A | B |\n| --- | --- |\n```\nKeep';
    expect(narrativeWithoutTables(s)).toBe(s);
  });
  it('handles a table with no leading pipe', () =>
    expect(narrativeWithoutTables('Name | Amount\n--- | ---: \nA | 10')).toBe(''));
});
