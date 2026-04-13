import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { buildSchemaExport, applyImportedSchema } from '~/plugins/core-designer/components/studio/services/workflow/schemaIO';

const exportStateMock = vi.fn();
const importStateMock = vi.fn();

vi.mock('~/plugins/core-designer/components/studio/services/state/PageStateManager', () => ({
  getPageStateManager: () => ({
    exportState: exportStateMock,
    importState: importStateMock,
  }),
}));

describe('schemaIO service', () => {
  beforeEach(() => {
    exportStateMock.mockReset();
    importStateMock.mockReset();
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: vi.fn(),
      clear: vi.fn(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('merges localStorage component props during export', async () => {
    exportStateMock.mockResolvedValue({
      pageSchema: {
        components: [
          {
            id: 'comp1',
            type: 'input',
            props: { label: 'Name' },
          },
        ],
      },
    });

    (localStorage.getItem as any).mockReturnValue(
      JSON.stringify({ label: '来自本地', placeholder: '请输入' }),
    );

    const result = await buildSchemaExport('page-1');

    expect(exportStateMock).toHaveBeenCalled();
    expect(result.filename).toContain('page-1');
    expect(result.payload.pageSchema.components[0].props).toMatchObject({
      label: '来自本地',
      placeholder: '请输入',
    });
  });

  it('falls back to original schema when no local overrides', async () => {
    exportStateMock.mockResolvedValue({
      pageSchema: {
        components: [
          {
            id: 'comp2',
            type: 'text',
            props: { content: 'Hello' },
          },
        ],
      },
    });
    (localStorage.getItem as any).mockReturnValue(null);

    const result = await buildSchemaExport('page-1');

    expect(result.payload.pageSchema.components[0].props).toMatchObject({ content: 'Hello' });
  });

  it('prefers schema field when importing data', async () => {
    const schema = { id: 'page', components: [] };
    const importData = { schema };

    importStateMock.mockResolvedValue(undefined);

    const result = await applyImportedSchema(importData);

    expect(importStateMock).toHaveBeenCalledWith(importData);
    expect(result).toBe(schema);
  });

  it('falls back to pageSchema when schema field missing', async () => {
    const schema = { id: 'page2', components: [] };
    const importData = { pageSchema: schema };

    const result = await applyImportedSchema(importData);

    expect(result).toBe(schema);
  });

  it('returns null when no schema found', async () => {
    const result = await applyImportedSchema({});
    expect(result).toBeNull();
  });
});
