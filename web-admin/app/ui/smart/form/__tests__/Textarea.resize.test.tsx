import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Textarea from '../Textarea';

let width = 400;
let observerCallback: ResizeObserverCallback;
const disconnect = vi.fn();

function view(value: string, autoResize = true, ref?: React.Ref<HTMLTextAreaElement>) {
  return <Textarea name="terms" label="Terms" value={value} readOnly autoResize={autoResize} ref={ref} />;
}

function mount(element: React.ReactNode) {
  return render(<RouterProvider router={createMemoryRouter([{ path: '/', element }])} />);
}

beforeEach(() => {
  width = 400;
  disconnect.mockClear();
  vi.spyOn(HTMLTextAreaElement.prototype, 'scrollHeight', 'get').mockImplementation(function (this: HTMLTextAreaElement) {
    return this.value.length > 100 ? (width < 300 ? 420 : 240) : 80;
  });
  vi.spyOn(HTMLTextAreaElement.prototype, 'clientWidth', 'get').mockImplementation(() => width);
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: ResizeObserverCallback) { observerCallback = callback; }
    observe() {}
    disconnect = disconnect;
  });
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('SmartTextarea autoResize', () => {
  it('fits complete read-only terms and recalculates when the container narrows', () => {
    const ref = React.createRef<HTMLTextAreaElement>();
    const longTerms = '货到付款并包含运输费用'.repeat(20);
    mount(view(longTerms, true, ref));
    const input = screen.getByRole('textbox');
    expect(input).toHaveValue(longTerms);
    expect(input).toHaveAttribute('readonly');
    expect(ref.current).toBe(input);
    expect(input).toHaveStyle({ height: '240px' });
    width = 200;
    act(() => observerCallback([{ target: input, contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver));
    expect(input).toHaveStyle({ height: '420px' });
  });

  it('shrinks after a shorter controlled value and disconnects on unmount', () => {
    function Harness() {
      const [value, setValue] = React.useState('Payment terms '.repeat(30));
      return <><button onClick={() => setValue('Cash on delivery')}>Shorten</button>{view(value)}</>;
    }
    const rendered = mount(<Harness />);
    expect(screen.getByRole('textbox')).toHaveStyle({ height: '240px' });
    act(() => screen.getByRole('button', { name: 'Shorten' }).click());
    expect(screen.getByRole('textbox')).toHaveValue('Cash on delivery');
    expect(screen.getByRole('textbox')).toHaveStyle({ height: '80px' });
    rendered.unmount();
    expect(disconnect).toHaveBeenCalled();
  });

  it('preserves normal row-based sizing when autoResize is not requested', () => {
    mount(view('Terms '.repeat(40), false));
    expect(screen.getByRole('textbox').style.height).toBe('');
  });
});

it('updates the character count when a controlled draft is filled or undone', () => {
  function Harness() {
    const [value, setValue] = React.useState('');
    return <><button onClick={() => setValue('Reviewed draft')}>Fill draft</button><button onClick={() => setValue('')}>Undo draft</button><Textarea name="notes" label="Notes" value={value} maxLength={100} /></>;
  }
  mount(<Harness />);
  expect(screen.getByText('0/100')).toBeVisible();
  act(() => screen.getByRole('button', { name: 'Fill draft' }).click());
  expect(screen.getByRole('textbox')).toHaveValue('Reviewed draft');
  expect(screen.getByText('14/100')).toBeVisible();
  act(() => screen.getByRole('button', { name: 'Undo draft' }).click());
  expect(screen.getByText('0/100')).toBeVisible();
});
