import { describe, it, expect, vi } from 'vitest';

import { executeRegistryAction } from '~/framework/meta/hooks/executeRegistryAction';

describe('executeRegistryAction', () => {
  it('treats cancel as a registered back-navigation action', async () => {
    const navigate = vi.fn();

    await executeRegistryAction({
      button: { code: 'cancel' } as any,
      navigate: navigate as any,
      tableName: 'showcase_all_fields',
      context: {},
      dataSourceManager: {} as any,
      locale: 'zh-CN',
      t: (key: string) => key,
    });

    expect(navigate).toHaveBeenCalledWith(-1);
  });
});
