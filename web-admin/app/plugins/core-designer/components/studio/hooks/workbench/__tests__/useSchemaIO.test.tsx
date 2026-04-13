import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSchemaIO } from '~/plugins/core-designer/components/studio/hooks/workbench/useSchemaIO';

const { buildSchemaExportMock, applyImportedSchemaMock } = vi.hoisted(() => ({
  buildSchemaExportMock: vi.fn(),
  applyImportedSchemaMock: vi.fn(),
}));

vi.mock('~/plugins/core-designer/components/studio/services/workflow/schemaIO', () => ({
  buildSchemaExport: buildSchemaExportMock,
  applyImportedSchema: applyImportedSchemaMock,
}));

describe('useSchemaIO', () => {
  beforeEach(() => {
    buildSchemaExportMock.mockReset();
    applyImportedSchemaMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('exports schema using download flow', async () => {
    const blobUrl = 'blob:test';
    buildSchemaExportMock.mockResolvedValue({
      filename: 'schema.json',
      payload: { foo: 'bar' },
    });

    const clickMock = vi.fn();
    const anchorMock = document.createElement('a');
    anchorMock.click = clickMock;

    const originalCreateElement = document.createElement.bind(document);
    const createElementSpy = vi
      .spyOn(document, 'createElement')
      .mockImplementation((tagName, options) => {
        if (typeof tagName === 'string' && tagName.toLowerCase() === 'a') {
          return anchorMock;
        }
        return originalCreateElement(tagName as any, options as any);
      });
    const originalAppendChild = document.body.appendChild.bind(document.body);
    const appendChildSpy = vi
      .spyOn(document.body, 'appendChild')
      .mockImplementation((node: Node) => {
        originalAppendChild(node);
        return node;
      });
    const originalRemoveChild = document.body.removeChild.bind(document.body);
    const removeChildSpy = vi
      .spyOn(document.body, 'removeChild')
      .mockImplementation((node: Node) => {
        originalRemoveChild(node);
        return node;
      });

    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;
    const createUrlSpy = vi.fn().mockReturnValue(blobUrl);
    const revokeSpy = vi.fn();
    (URL as any).createObjectURL = createUrlSpy;
    (URL as any).revokeObjectURL = revokeSpy;

    const { result } = renderHook(() =>
      useSchemaIO({
        pageId: 'page-1',
      }),
    );

    await act(async () => {
      await result.current.exportSchema();
    });

    expect(buildSchemaExportMock).toHaveBeenCalledWith('page-1');
    expect(createElementSpy).toHaveBeenCalledWith('a');
    expect(appendChildSpy).toHaveBeenCalled();
    expect(clickMock).toHaveBeenCalled();
    expect(removeChildSpy).toHaveBeenCalled();
    expect(createUrlSpy).toHaveBeenCalled();
    expect(revokeSpy).toHaveBeenCalledWith(blobUrl);

    createElementSpy.mockRestore();
    appendChildSpy.mockRestore();
    removeChildSpy.mockRestore();
    (URL as any).createObjectURL = originalCreateObjectURL;
    (URL as any).revokeObjectURL = originalRevokeObjectURL;
  });

  it('imports schema and invokes callback', async () => {
    const importedSchema = { id: 'page', components: [] };
    applyImportedSchemaMock.mockResolvedValue(importedSchema);
    const onImported = vi.fn();

    const { result } = renderHook(() =>
      useSchemaIO({
        pageId: 'page-2',
        onSchemaImported: onImported,
      }),
    );

    const fakeFile = {
      text: vi.fn().mockResolvedValue(JSON.stringify({ schema: importedSchema })),
    } as unknown as File;

    await act(async () => {
      await result.current.importSchema(fakeFile);
    });

    expect(fakeFile.text).toHaveBeenCalled();
    expect(applyImportedSchemaMock).toHaveBeenCalledWith({ schema: importedSchema });
    expect(onImported).toHaveBeenCalledWith(importedSchema);
  });
});
