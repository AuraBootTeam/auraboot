import { describe, expect, it } from 'vitest';
import { selectImportClass } from '../RosterImport';

describe('selectImportClass', () => {
  const classes = [
    { pid: 'class-a', name: '三（7）班' },
    { pid: 'class-b', name: '三（8）班' },
  ];

  it('keeps the class selected by the activation-center handoff', () => {
    expect(selectImportClass(classes, 'class-b')).toEqual(classes[1]);
  });

  it('falls back to the first active class for a stale URL', () => {
    expect(selectImportClass(classes, 'missing')).toEqual(classes[0]);
  });
});
