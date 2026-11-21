import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DetailBodySections } from '../DetailBodySections';
import { ScopedStateManager } from '../../../../runtime/state/scoped-state';
import { createExpressionContext } from '../../../../runtime/expression/context';
import { ExpressionEvaluator } from '../../../../runtime/expression/evaluator';

// Hermetic renderer unit: use the real scope and evaluator; no persistence claim.
describe('Detail body section visibility', () => {
  it('reacts to page state without mounting denied blocks or discarding an active draft', () => {
    const state = new ScopedStateManager({} as any);
    state.createScope('sections',{form:{preview:false}});
    const runtime = {subscribeContext:(listener:()=>void)=>state.getStore('sections')!.subscribe(listener)} as any;
    const evaluator = new ExpressionEvaluator();
    const blocks = [
      {id:'draft',blockType:'form'},
      {id:'preview',blockType:'rich-text',visibleWhen:'form.preview === true'},
      {id:'denied',blockType:'rich-text',permissionCode:'denied'},
    ] as any;
    render(<DetailBodySections groups={[
      {id:'draft',title:{en:'Draft'},blockIds:['draft']},
      {id:'preview',title:{en:'Preview'},blockIds:['preview','denied']},
    ]} blocks={blocks} runtime={runtime} hasPermission={()=>false}
      evaluateVisibleWhen={expression=>!expression || Boolean(evaluator.evaluate(expression,createExpressionContext(state.getScope('sections'))))}
      locale="en" t={key=>key}
      renderBlock={block=>block.id==='draft'?<input key={block.id} aria-label="Allocation" defaultValue="60"/>:<span key={block.id}>{block.id}</span>}/>);
    expect(screen.queryByRole('heading',{name:'Preview'})).toBeNull();
    expect(screen.queryByText('denied')).toBeNull();
    const input = screen.getByRole('textbox',{name:'Allocation'});
    fireEvent.change(input,{target:{value:'75'}});
    input.focus();
    act(()=>state.updateField('sections','preview',true));
    expect(screen.getByRole('heading',{name:'Preview'})).toBeVisible();
    expect(screen.getByText('preview')).toBeVisible();
    expect(screen.queryByText('denied')).toBeNull();
    expect(screen.getByRole('textbox',{name:'Allocation'})).toBe(input);
    expect(input).toHaveValue('75');
    expect(input).toHaveFocus();
    act(()=>state.updateField('sections','preview',false));
    expect(screen.queryByRole('heading',{name:'Preview'})).toBeNull();
  });
});
