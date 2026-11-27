/**
 * SmartRichText: links inside dashboard rich text must look like links. The typography plugin is not
 * part of the CSS build, so `prose` alone leaves anchors indistinguishable from body text.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SmartRichText } from '../SmartRichText';

describe('SmartRichText links', () => {
  it('renders an anchor and gives the content the link styling convention of the app', () => {
    render(
      <SmartRichText
        content={'<p>需要你处理：<a href="/dashboards/view/orb_approval_center">审批中心</a> · <a href="/p/orb_deployment_run">部署运行</a></p>'}
      />,
    );
    const link = screen.getByText('审批中心');
    expect(link.tagName).toBe('A');
    const content = screen.getByTestId('smart-rich-text-content');
    expect(content.className).toContain('[&_a]:text-accent');
    expect(content.className).toContain('[&_a]:underline');
  });

  it('keeps ordinary text free of link styling classes on itself', () => {
    render(<SmartRichText content="<p>普通文字</p>" />);
    expect(screen.getByText('普通文字').tagName).toBe('P');
  });
});
