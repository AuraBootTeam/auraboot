import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, it } from 'vitest';
import { Input } from '../Input';

it('honors compact inline fields without losing exact decimal drafts or accessible labels', () => {
  function Harness() {
    const [value,setValue] = React.useState('350');
    return <Input name="allocation" label="Purchase quantity" inline density="compact"
      controlClassName="ml-auto" controlWidth={90} value={value} onChange={setValue} />;
  }
  render(<RouterProvider router={createMemoryRouter([{path:'/',element:<Harness />}])} />);
  const input = screen.getByRole('textbox',{name:'Purchase quantity'});
  expect(input.closest('.mb-0')).not.toBeNull();
  expect(input.parentElement).toHaveStyle({width:'90px',flex:'0 0 90px'});
  expect(input.parentElement?.parentElement).toHaveClass('flex');
  expect(input).not.toHaveAttribute('inline');
  expect(input).not.toHaveAttribute('density');
  fireEvent.change(input,{target:{value:'12.000001'}});
  expect(input).toHaveValue('12.000001');
});
