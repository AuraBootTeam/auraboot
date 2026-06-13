import { describe, it, expect, beforeEach } from 'vitest';
import { DependencyGraph } from '../DependencyGraph';

describe('DependencyGraph', () => {
  let graph: DependencyGraph;

  beforeEach(() => {
    graph = new DependencyGraph();
  });

  describe('addField / isComputed', () => {
    it('marks a field as computed after addField', () => {
      graph.addField('total', ['price', 'qty']);
      expect(graph.isComputed('total')).toBe(true);
    });

    it('a field with no deps is still computed', () => {
      graph.addField('constant', []);
      expect(graph.isComputed('constant')).toBe(true);
    });

    it('unknown field is not computed', () => {
      expect(graph.isComputed('unknown')).toBe(false);
    });
  });

  describe('getDependencies / getDependents', () => {
    beforeEach(() => {
      graph.addField('total', ['price', 'qty']);
      graph.addField('tax', ['total']);
    });

    it('getDependencies returns direct dependencies', () => {
      expect(new Set(graph.getDependencies('total'))).toEqual(new Set(['price', 'qty']));
    });

    it('getDependencies returns empty for field with no deps', () => {
      graph.addField('leaf', []);
      expect(graph.getDependencies('leaf')).toHaveLength(0);
    });

    it('getDependencies returns empty for unknown field', () => {
      expect(graph.getDependencies('ghost')).toHaveLength(0);
    });

    it('getDependents returns fields that depend on total', () => {
      expect(graph.getDependents('total')).toContain('tax');
    });

    it('getDependents returns total for qty (because total depends on qty)', () => {
      // qty is used as a dependency input; getDependents tracks reverse edges regardless
      expect(graph.getDependents('qty')).toContain('total');
    });
  });

  describe('getAffectedFields', () => {
    beforeEach(() => {
      // price → total → tax → display
      graph.addField('total', ['price', 'qty']);
      graph.addField('tax', ['total']);
      graph.addField('display', ['tax']);
    });

    it('returns all downstream computed fields when price changes', () => {
      const affected = graph.getAffectedFields('price');
      expect(affected).toContain('total');
      expect(affected).toContain('tax');
      expect(affected).toContain('display');
    });

    it('returns subset when total changes (price not included)', () => {
      const affected = graph.getAffectedFields('total');
      expect(affected).not.toContain('total');
      expect(affected).toContain('tax');
      expect(affected).toContain('display');
    });

    it('respects topological order – parent before child', () => {
      const affected = graph.getAffectedFields('price');
      const idxTotal = affected.indexOf('total');
      const idxTax = affected.indexOf('tax');
      const idxDisplay = affected.indexOf('display');
      expect(idxTotal).toBeLessThan(idxTax);
      expect(idxTax).toBeLessThan(idxDisplay);
    });

    it('returns empty array when field has no dependents', () => {
      expect(graph.getAffectedFields('display')).toHaveLength(0);
    });

    it('returns empty for unregistered field', () => {
      expect(graph.getAffectedFields('not_a_field')).toHaveLength(0);
    });
  });

  describe('getEvaluationOrder', () => {
    it('returns all fields in topological order', () => {
      graph.addField('c', ['b']);
      graph.addField('b', ['a']);
      graph.addField('a', []);
      const order = graph.getEvaluationOrder();
      expect(order.indexOf('a')).toBeLessThan(order.indexOf('b'));
      expect(order.indexOf('b')).toBeLessThan(order.indexOf('c'));
    });

    it('returns cached result on second call', () => {
      graph.addField('x', ['y']);
      graph.addField('y', []);
      const first = graph.getEvaluationOrder();
      const second = graph.getEvaluationOrder();
      expect(first).toBe(second); // same array reference
    });

    it('cache is invalidated after addField', () => {
      graph.addField('x', []);
      const first = graph.getEvaluationOrder();
      graph.addField('y', ['x']);
      const second = graph.getEvaluationOrder();
      expect(first).not.toBe(second);
    });
  });

  describe('detectCycle', () => {
    it('returns null for acyclic graph', () => {
      graph.addField('a', []);
      graph.addField('b', ['a']);
      graph.addField('c', ['b']);
      expect(graph.detectCycle()).toBeNull();
    });

    it('detects a direct cycle', () => {
      graph.addField('a', ['b']);
      graph.addField('b', ['a']);
      const cycle = graph.detectCycle();
      expect(cycle).not.toBeNull();
      expect(cycle!.length).toBeGreaterThan(1);
    });

    it('detects an indirect cycle', () => {
      graph.addField('a', ['c']);
      graph.addField('b', ['a']);
      graph.addField('c', ['b']);
      const cycle = graph.detectCycle();
      expect(cycle).not.toBeNull();
    });

    it('returns null for empty graph', () => {
      expect(graph.detectCycle()).toBeNull();
    });

    it('cycle path contains the involved nodes', () => {
      graph.addField('x', ['y']);
      graph.addField('y', ['x']);
      const cycle = graph.detectCycle();
      expect(cycle).not.toBeNull();
      const cycleSet = new Set(cycle!);
      expect(cycleSet.has('x') || cycleSet.has('y')).toBe(true);
    });
  });

  describe('removeField', () => {
    it('removes the field so isComputed returns false', () => {
      graph.addField('total', ['price']);
      graph.removeField('total');
      expect(graph.isComputed('total')).toBe(false);
    });

    it('removing a field removes it from its dependencies dependents list', () => {
      graph.addField('total', ['price']);
      graph.removeField('total');
      expect(graph.getDependents('price')).not.toContain('total');
    });

    it('invalidates the cached order', () => {
      graph.addField('a', []);
      graph.addField('b', ['a']);
      const before = graph.getEvaluationOrder();
      graph.removeField('b');
      const after = graph.getEvaluationOrder();
      expect(after).not.toBe(before);
      expect(after).not.toContain('b');
    });
  });

  describe('clear', () => {
    it('empties the graph', () => {
      graph.addField('a', []);
      graph.addField('b', ['a']);
      graph.clear();
      expect(graph.isComputed('a')).toBe(false);
      expect(graph.isComputed('b')).toBe(false);
      expect(graph.getEvaluationOrder()).toHaveLength(0);
    });
  });

  describe('edge cases', () => {
    it('adding the same field twice overwrites dependencies', () => {
      graph.addField('f', ['a', 'b']);
      graph.addField('f', ['c']); // re-register
      expect(graph.getDependencies('f')).toContain('c');
    });

    it('independent fields all appear in evaluation order', () => {
      graph.addField('x', []);
      graph.addField('y', []);
      graph.addField('z', []);
      const order = graph.getEvaluationOrder();
      expect(order).toContain('x');
      expect(order).toContain('y');
      expect(order).toContain('z');
    });
  });
});
