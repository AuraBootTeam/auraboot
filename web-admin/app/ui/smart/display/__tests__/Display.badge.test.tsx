import React from 'react';
import {render,screen} from '@testing-library/react';
import {describe,it,expect} from 'vitest';
import {Display} from '../Display';

describe('Authored display badges',()=>{
  it('shows an explicitly mapped semantic color and escapes the original text',()=>{
    render(<Display format="badge" appearance="plain" value="<b>Complete</b>" statusColor={{'<b>Complete</b>':'success'}} />);
    expect(screen.getByText('<b>Complete</b>')).toHaveClass('bg-status-green-bg','text-status-green');
    expect(document.querySelector('b')).toBeNull();
  });
  it('keeps an unmapped badge neutral rather than inventing success',()=>{
    render(<Display format="badge" value="Awaiting" statusColor={{Complete:'success'}} />);
    expect(screen.getByText('Awaiting')).toHaveClass('bg-subtle','text-text-2');
    expect(screen.getByText('Awaiting')).not.toHaveClass('bg-status-green-bg');
  });
});
