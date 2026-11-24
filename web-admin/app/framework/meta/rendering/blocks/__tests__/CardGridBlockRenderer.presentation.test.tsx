import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { registerActiveFormDialog } from '../../../runtime/actions/formDialogLifecycle';
import { describe, expect, it, vi } from 'vitest';
import { CardGridBlockRenderer } from '../CardGridBlockRenderer';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
import { businessFunctions } from '../../../runtime/expression/business-functions';
import { Display } from '~/ui/smart/display/Display';
const { handleAction } = vi.hoisted(() => ({handleAction: vi.fn()}));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({useActionHandler: () => ({handleAction})}));
vi.mock('~/contexts/AuthContext', () => ({useAuth: () => ({hasPermission: () => true}),usePermission: () => true}));
vi.mock('~/contexts/ToastContext', () => ({useToastContext: () => ({showSuccessToast: vi.fn(),showErrorToast: vi.fn(),showWarningToast: vi.fn(),showInfoToast: vi.fn()})}));
vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({useDictCache: () => ({loaded:true,getDictLabel: () => undefined})}));
vi.mock('../../components/ComponentLoader', () => ({ComponentLoader: ({props}: any) => <Display {...props} />}));
function setup(config: any = {}) {
 const state = new ScopedStateManager({} as any);state.createScope('detail');
 const rows = [{pid:'batch-a',title:'Batch one',tracking:'Only first trace',address:'Old address',children:[{pid:'line-a',batch:'batch-a',name:'Resistor',quantity:'25',spec:'Complete original specification'}]}, {pid:'batch-b',title:'Batch two',tracking:'Only second trace',address:'New address',children:[{pid:'line-b',batch:'batch-b',name:'Resistor',quantity:'20',spec:'Second frozen specification'}]}];
 const data = {getData: () => rows,getState: () => ({data:rows}),subscribe: () => () => {}};
 const runtime = {getContext: () => ({...state.getScope('detail'),fn:businessFunctions,locale:'en',t:(s:string) => s}), getSchema: () => ({id:'orders'}),getScopeId: () => 'detail',getStateManager: () => state,getEvaluator: () => new ExpressionEvaluator(),getDataSourceManager: () => data,triggerFieldLinkage: vi.fn()} as any;
 const field = (name:string) => ({field:name,label:name,component:'SmartDisplay',readOnly:true,props:{appearance:'plain'}});
 const block = {id:'batches',blockType:'card-grid',dataSource:'batches',childrenField:'children',groupByField:'batch',groupTitleField:'title',titleField:'name',itemPresentation:'table',table:{columns:[{field:'name',label:'Material',presentation:{kind:'object',descriptionField:'spec'}},{field:'quantity',label:'Quantity',align:'right'}]},groupRows:{dataSource:'batches',field:'groups',identityField:'pid',titleField:'title',asideFields:[field('address')],asideDetailsFields:[field('tracking')],asideDetailsLabel:'Full tracking'},...config};
 return {state,rows,view:render(<CardGridBlockRenderer block={block as any} runtime={runtime} />)};
}
describe('Authored collection presentations', () => {
 it('keeps table children and modal details scoped to their own batch', async () => {
  const {rows} = setup();
  expect(screen.getAllByRole('table')).toHaveLength(2);
  expect(screen.queryByTestId('card-grid-card')).toBeNull();
  expect(screen.getByText('25')).toBeInTheDocument();expect(screen.getByText('20')).toBeInTheDocument();
  fireEvent.click(screen.getAllByRole('button',{name:'Full tracking'})[0]);
  expect(screen.getByRole('dialog')).toHaveTextContent('Only first trace');expect(screen.getByRole('dialog')).not.toHaveTextContent('Only second trace');
  fireEvent.click(screen.getByRole('button',{name:'Close'}));await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  fireEvent.click(screen.getAllByRole('button',{name:'Full tracking'})[1]);expect(screen.getByRole('dialog')).toHaveTextContent('Only second trace');
  expect(rows[0].address).toBe('Old address');expect(handleAction).not.toHaveBeenCalled();
 });
 it('reverses displayed batch groups while keeping child quantities and modal details bound to their source', () => {
  setup({groupOrder:'reverse'});
  const groups=screen.getAllByTestId('card-grid-group');
  expect(groups[0]).toHaveTextContent('Batch two');
  expect(groups[0]).toHaveTextContent('20');
  expect(groups[1]).toHaveTextContent('Batch one');
  fireEvent.click(screen.getAllByRole('button',{name:'Full tracking'})[0]);
  expect(screen.getByRole('dialog')).toHaveTextContent('Only second trace');
  expect(screen.getByRole('dialog')).not.toHaveTextContent('Only first trace');
 });
 it('opens only the first displayed group and preserves closed material contents', () => {
  setup({groupOrder:'reverse',collapsibleGroups:true,groupsDefaultOpen:'first'});
  const groups=screen.getAllByTestId('card-grid-group');
  expect(groups[0]).toHaveAttribute('open');
  expect(groups[0]).toHaveTextContent('Batch two');
  expect(groups[1]).not.toHaveAttribute('open');
  expect(groups[1]).toHaveTextContent('25');
  groups[1].setAttribute('open','');
  expect(groups[1]).toHaveTextContent('Complete original specification');
  expect(handleAction).not.toHaveBeenCalled();
 });
 it('renders a collection review in a modal and routes dismissal through the configured action', async () => {
  handleAction.mockClear();
  setup({collectionDialog:{title:'Review entire plan',headerFields:[{field:'plannedCount',label:'Planned orders',component:'SmartDisplay',valueWhen:'${fn.COUNT(form.groups)}',props:{appearance:'plain'}}],buttons:[],dismissAction:{code:'discard-preview',action:{type:'flow',steps:[]}}}});
  expect(screen.getByRole('dialog')).toHaveTextContent('Batch one');
  expect(screen.getByRole('dialog')).toHaveTextContent('Batch two');
  await waitFor(() => expect(screen.getByText('2',{exact:true})).toBeVisible());
  expect(screen.getByRole('dialog')).not.toHaveClass('max-w-lg','p-6');
  let release: () => void;
  act(() => {release = registerActiveFormDialog();});
  fireEvent.click(screen.getByRole('button',{name:'Close'}));
  expect(handleAction).not.toHaveBeenCalled();
  act(() => release());
  fireEvent.click(screen.getByRole('button',{name:'Close'}));
  await waitFor(() => expect(handleAction).toHaveBeenCalledTimes(1));
  expect(handleAction.mock.calls[0][0].code).toBe('discard-preview');
 });
 it('rejects editable arrays for a read-only table presentation', () => {
  expect(() => setup({field:'draft'})).toThrow(/read-only|Nested child/);
 });
 it('renders structured timeline events without interpreting event content as HTML', () => {
  render(<Display format="timeline" value={[{time:'2026-11-24 12:00',context:'<b>Delivered</b>'},{time:'2026-11-23',context:'Picked up'}]} />);
  expect(screen.getAllByRole('listitem')).toHaveLength(2);expect(screen.getByText('<b>Delivered</b>')).toBeInTheDocument();expect(document.querySelector('b')).toBeNull();
 });
});
