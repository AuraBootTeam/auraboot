import { describe, expect, it } from 'vitest';
import { isCustomListDataReady } from '../list/listSchemaPolicy';
describe('custom-only list readiness', () => {
  const schema={dataSources:{categories:{type:'api',autoFetch:true},catalog:{type:'api',autoFetch:true},preview:{type:'api',autoFetch:false}}};
  it('waits for both real auto-fetched blocks, without a native model query', () => {
    const states: Record<string,any>={categories:{loading:false,lastFetch:1},catalog:{loading:true,lastFetch:null}};
    const manager={getState:(id:string)=>states[id]};
    expect(isCustomListDataReady(schema,manager)).toBe(false);
    states.catalog={loading:false,lastFetch:2};expect(isCustomListDataReady(schema,manager)).toBe(true);
    states.catalog={loading:true,lastFetch:2};expect(isCustomListDataReady(schema,manager)).toBe(false);
    states.catalog={loading:false,lastFetch:2};expect(isCustomListDataReady(schema,manager)).toBe(true);
  });
  it('does not declare missing or unstarted sources ready and lets settled errors render', () => {
    expect(isCustomListDataReady(schema,null)).toBe(false);
    expect(isCustomListDataReady(schema,{getState:()=>undefined})).toBe(false);
    expect(isCustomListDataReady(schema,{getState:()=>({loading:false,lastFetch:null})})).toBe(false);
    expect(isCustomListDataReady(schema,{getState:()=>({loading:false,lastFetch:null,error:new Error('Denied')})})).toBe(true);
  });
});
