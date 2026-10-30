import { describe, expect, it, vi } from 'vitest';
import { DATA_TYPE_COMPONENT_MAP } from '../../../workbench/panels/fields/types';
import { FieldLibraryService } from '../FieldLibraryService';
import { get } from '~/shared/services/http-client';
vi.mock('~/shared/services/http-client', () => ({ get: vi.fn(), post: vi.fn() }));

describe('model field palette metadata', () => {
  it('normalizes canonical type casing without modifying the response or business metadata', async () => {
    const source = [{ code: 'budget', dataType: 'decimal', required: true, displayName: 'Budget' }, { code: 'start', dataType: 'date' }];
    vi.mocked(get).mockResolvedValue({ code: '0', data: source } as any);
    const fields = await new FieldLibraryService().getModelFields('model-pid');
    expect(get).toHaveBeenCalledWith('/api/meta/models/model-pid/fields');
    expect(fields).toEqual([{ ...source[0], dataType: 'DECIMAL' }, { ...source[1], dataType: 'DATE' }]);
    expect(source[0].dataType).toBe('decimal');
    expect(DATA_TYPE_COMPONENT_MAP.MONEY.type).toBe('moneyinput');
  });
});
