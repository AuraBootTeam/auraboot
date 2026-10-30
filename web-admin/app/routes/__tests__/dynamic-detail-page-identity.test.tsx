import React, { useState } from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
const route = vi.hoisted(() => ({ data: { tableName: 'ab_announcement', recordPid: 'root-one', accessError: null } }));
vi.mock('react-router', async importOriginal => ({ ...await importOriginal<typeof import('react-router')>(), useLoaderData: () => route.data }));
vi.mock('~/framework/meta/rendering/pages/DynamicPageRenderer', () => ({
  DynamicPageRenderer: (props: { tableName: string; recordPid: string }) => {
    const [loadedIdentity] = useState(`${props.tableName}:${props.recordPid}`);
    return <div data-testid="loaded-page-identity">{loadedIdentity}</div>;
  },
}));
import DynamicDetailView from '../p.$pageKey.view';

describe('dynamic detail navigation identity', () => {
  it.each([
    { tableName: 'announcement_statistics', recordPid: 'capture-one' },
    { tableName: 'ab_announcement', recordPid: 'root-two' },
  ])('starts a fresh page instance for $tableName/$recordPid', next => {
    route.data = { tableName: 'ab_announcement', recordPid: 'root-one', accessError: null };
    const view = render(<DynamicDetailView />);
    expect(view.getByTestId('loaded-page-identity')).toHaveTextContent('ab_announcement:root-one');
    route.data = { ...next, accessError: null };
    view.rerender(<DynamicDetailView />);
    expect(view.getByTestId('loaded-page-identity')).toHaveTextContent(`${next.tableName}:${next.recordPid}`);
  });
});
