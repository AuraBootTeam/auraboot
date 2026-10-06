import { describe, expect, it } from 'vitest';
import { authorizationMethodLabel, memberStatusLabel } from '../member-detail-labels';
describe('customer account labels', () => {
  it('shows business labels in each locale and accepts normalized status casing', () => {
    const zh = (text: string) => text;
    const en = (_: string, text: string) => text;
    expect(memberStatusLabel('ACTIVE', zh)).toBe('已激活');
    expect(memberStatusLabel('active', en)).toBe('Active');
    expect(authorizationMethodLabel('offline', zh)).toBe('线下授权');
    expect(authorizationMethodLabel('offline', en)).toBe('Offline authorization');
  });
  it('does not leak unknown transport codes into account or audit labels', () => {
    const zh = (text: string) => text;
    expect(memberStatusLabel('server_internal_future_state', zh)).toBe('未知');
    expect(authorizationMethodLabel('server_internal_future_method', zh)).toBe('未知');
  });
});
