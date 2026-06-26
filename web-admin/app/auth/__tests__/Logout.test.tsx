import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/session', () => ({
  logout: vi.fn(),
}));

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    Form: ({ children, reloadDocument, ...props }: React.ComponentProps<'form'> & { reloadDocument?: boolean }) => (
      <form data-testid="logout-form" data-reload-document={String(Boolean(reloadDocument))} {...props}>
        {children}
      </form>
    ),
    Link: ({ children, to, ...props }: React.ComponentProps<'a'> & { to: string }) => (
      <a href={to} {...props}>
        {children}
      </a>
    ),
  };
});

describe('Logout screen', () => {
  it('submits through document navigation so the session destroy cookie is applied', () => {
    render(<Screen />);

    expect(screen.getByRole('heading', { name: '退出登录' })).toBeInTheDocument();
    expect(screen.getByTestId('logout-form')).toHaveAttribute('method', 'post');
    expect(screen.getByTestId('logout-form')).toHaveAttribute('data-reload-document', 'true');
  });
});

import Screen from '../Logout';
