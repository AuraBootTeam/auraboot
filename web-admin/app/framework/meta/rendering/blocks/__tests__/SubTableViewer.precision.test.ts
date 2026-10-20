import {describe,it,expect} from 'vitest';
import {formatCellValue} from '../SubTableViewer';
import type {ColumnConfig} from '~/framework/meta/schemas/types';
const column=(precision?:number):ColumnConfig=>({field:'coordinate',format:'number',...(precision===undefined?{}:{precision})});
describe('Sub-table numeric precision',()=>{
 it('preserves distinct WGS84 vertices at nine fractional digits',()=>{
  expect(formatCellValue(10.391676484,column(9))).toBe('10.391676484');
  expect(formatCellValue(10.392860125,column(9))).toBe('10.392860125');
  expect(formatCellValue(-63.433923182,column(9))).toBe('-63.433923182');
 });
 it('keeps the existing two-digit default for unspecified columns',()=>{
  expect(formatCellValue(1234.56789,column())).toBe('1,234.57');
  expect(formatCellValue(12.34567,{field:'cost',format:'currency'})).toBe('12.35');
 });
 it('supports integer order cells and retains non-numeric semantics',()=>{
  expect(formatCellValue(12,column(0))).toBe('12');
  expect(formatCellValue(null,column(9))).toBe('-');
  expect(formatCellValue('vertex-A',column(9))).toBe('vertex-A');
 });
 it('rejects invalid presentation precision rather than silently altering it',()=>{
  for(const precision of [-1,21,1.5,NaN])expect(()=>formatCellValue(1.123,column(precision))).toThrow('numeric precision');
 });
});
