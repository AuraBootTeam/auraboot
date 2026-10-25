import {describe,it,expect} from 'vitest';
import {collectDesignerSchemas,flattenFields} from '../designer-schema-inventory';

describe('native designer schema denominator',()=>{
  it('preserves zero limits and nested dependency metadata without claiming execution',()=>{
    const fields=flattenFields([{key:'columns',type:'array',itemSchema:[{key:'width',type:'number',min:0,max:100,dependsOn:{field:'mode',anyOf:['fixed']}}]}]);
    expect(fields[1]).toMatchObject({path:'columns[].width',min:0,max:100,dependsOn:{field:'mode',anyOf:['fixed']},execution:'did_not_run',registeredAssertions:[]});
  });
  it('enumerates the actual authoring schema and dynamic picker/action variants',()=>{
    const rows=collectDesignerSchemas();
    const grouped=rows.find(row=>row.surface==='Report'&&row.component==='grouped-table');
    expect(grouped.properties.map((field:any)=>field.path)).toContain('groupSubtotal.columns[].aggregation');
    const picker=rows.find(row=>row.surface==='Page'&&row.component==='field'&&row.variant==='picker');
    expect(picker.properties.map((field:any)=>field.path)).toContain('props.pickerParameters');
    expect(rows.some(row=>row.surface==='Page'&&row.component==='action'&&row.variant==='workflow')).toBe(true);
    expect(rows.every(row=>row.properties.every((field:any)=>field.execution==='did_not_run'))).toBe(true);
  });
});
