import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TabFilterEditor } from '../TabFilterEditor';

interface TestTab {
  key: string;
  label: string | { 'en-US'?: string; 'zh-CN'?: string };
  filter: null;
  blocks?: Array<Record<string, any>>;
}

function StatefulTabFilterEditor({
  initialTabs,
  onCommit,
}: {
  initialTabs: TestTab[];
  onCommit: (tabs: TestTab[]) => void;
}) {
  const [tabs, setTabs] = React.useState<TestTab[]>(initialTabs);

  return (
    <>
      <TabFilterEditor
        tabs={tabs as any}
        blockId="tabs_block"
        onChange={(nextTabs) => {
          const typedTabs = nextTabs as TestTab[];
          setTabs(typedTabs);
          onCommit(typedTabs);
        }}
      />
      <pre data-testid="serialized-tabs">{JSON.stringify(tabs)}</pre>
    </>
  );
}

function serializedTabs(): TestTab[] {
  return JSON.parse(screen.getByTestId('serialized-tabs').textContent || '[]');
}

describe('TabFilterEditor', () => {
  it('authors text child blocks on the selected tab', () => {
    const onCommit = vi.fn();
    render(
      <StatefulTabFilterEditor
        initialTabs={[
          {
            key: 'overview',
            label: { 'en-US': 'Overview', 'zh-CN': '概览' },
            filter: null,
            blocks: [],
          },
          {
            key: 'history',
            label: { 'en-US': 'History', 'zh-CN': '历史' },
            filter: null,
            blocks: [
              {
                id: 'existing_history_text',
                blockType: 'text',
                title: { 'en-US': 'History text', 'zh-CN': '历史文本' },
                props: { content: 'Existing history copy' },
              },
            ],
          },
        ]}
        onCommit={onCommit}
      />,
    );

    expect(screen.getByTestId('tab-child-blocks-editor')).toBeVisible();
    expect(screen.getByTestId('tab-child-blocks-editor')).toHaveAttribute(
      'data-drop-id',
      'tab-child-drop:tabs_block:overview',
    );
    expect(screen.getByTestId('tab-child-drop-zone')).toBeVisible();

    fireEvent.click(screen.getByTestId('tab-child-add-text-block'));

    const afterAdd = serializedTabs();
    expect(afterAdd[0].blocks).toHaveLength(1);
    expect(afterAdd[0].blocks?.[0]).toMatchObject({
      id: expect.stringMatching(/^tab_text_/),
      blockType: 'text',
      title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
      props: { content: '' },
    });
    expect(afterAdd[1].blocks?.[0]?.props?.content).toBe('Existing history copy');

    fireEvent.change(screen.getByTestId('tab-child-text-content-0'), {
      target: { value: 'Nested overview copy' },
    });
    expect(serializedTabs()[0].blocks?.[0]).toMatchObject({
      blockType: 'text',
      props: { content: 'Nested overview copy' },
    });

    fireEvent.click(screen.getByTestId('tab-history'));
    expect(screen.getByTestId('tab-child-text-content-0')).toHaveValue('Existing history copy');

    fireEvent.click(screen.getByTestId('tab-overview'));
    fireEvent.click(screen.getByTestId('tab-child-remove-0'));
    expect(serializedTabs()[0].blocks).toEqual([]);
  });
});
