import { describe, it, expect } from 'vitest';
import { isPureCrudFormAuthoringDenied } from '../formActions';
const save = [{ action: 'save' }];
const schema = { modelCode: 'delivery' };
describe('pure CRUD form permission boundary', () => {
  it('denies a reader before editable fields and subtable commands mount', () => {
    expect(isPureCrudFormAuthoringDenied(schema, save, true, false, () => false)).toBe(true);
  });
  it('requires the current operation, not the other write permission', () => {
    const createOnly = (code: string) => code === 'model.delivery.create';
    expect(isPureCrudFormAuthoringDenied(schema, save, true, false, createOnly)).toBe(true);
    expect(isPureCrudFormAuthoringDenied(schema, save, false, false, createOnly)).toBe(false);
  });
  it('preserves command, explicit endpoint and embedded host authorization', () => {
    const denied = () => false;
    expect(
      isPureCrudFormAuthoringDenied(
        { ...schema, commands: { update: 'delivery:review' } },
        save,
        true,
        false,
        denied,
      ),
    ).toBe(false);
    expect(
      isPureCrudFormAuthoringDenied(
        schema,
        [{ action: 'save', commandCode: 'delivery:review' }],
        true,
        false,
        denied,
      ),
    ).toBe(false);
    expect(
      isPureCrudFormAuthoringDenied(
        { ...schema, extension: { submitEndpoint: { endpoint: '/api/review' } } },
        save,
        true,
        false,
        denied,
      ),
    ).toBe(false);
    expect(isPureCrudFormAuthoringDenied(schema, save, true, true, denied)).toBe(false);
  });
});
