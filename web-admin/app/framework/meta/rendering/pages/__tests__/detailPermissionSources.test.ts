import { describe, expect, it } from 'vitest';
import { resolvePermissionHiddenDataSourceIds } from '../detailPage/detailLayoutValues';

describe('permission inheritance for detail sources', () => {
  const canRead = (permission: string) => permission === 'read.public';
  it('excludes a source consumed only by a denied top-level block', () => {
    const schema = {
      blocks: [
        {
          id: 'history',
          blockType: 'table',
          permissionCode: 'read.private',
          dataSource: 'history',
        },
      ],
      dataSources: { history: {}, unused: {} },
    } as any;
    expect(resolvePermissionHiddenDataSourceIds(schema, canRead)).toEqual(new Set(['history']));
  });
  it('propagates parent and tab permissions while preserving a shared visible source', () => {
    const schema = {
      blocks: [
        {
          id: 'group',
          blockType: 'tabs',
          permissionCode: 'read.private',
          tabs: [
            {
              key: 'nested',
              blocks: [{ id: 'private', blockType: 'table', dataSource: 'hidden' }],
            },
          ],
        },
        {
          id: 'public-group',
          blockType: 'tabs',
          tabs: [
            {
              key: 'public',
              blocks: [
                {
                  id: 'denied',
                  blockType: 'table',
                  permissionCode: 'read.private',
                  dataSource: 'shared',
                },
                {
                  id: 'allowed',
                  blockType: 'table',
                  permissionCode: 'read.public',
                  dataSource: 'shared',
                },
              ],
            },
          ],
        },
      ],
      dataSources: { hidden: {}, shared: {}, unused: {} },
    } as any;
    expect(resolvePermissionHiddenDataSourceIds(schema, canRead)).toEqual(new Set(['hidden']));
  });
});
