import assert from 'node:assert/strict';
import test from 'node:test';
import { selectSubnet } from './lib/oss-ci-subnet.mjs';

test('unused private subnet is selected without default route blocking it', () => {
  assert.equal(selectSubnet([], [{ dst: 'default' }]), '10.240.0.0/24');
});
test('retained Docker subnets and connected host routes are both excluded', () => {
  const networks = [{ IPAM: { Config: [{ Subnet: '10.240.0.0/24' }, { Subnet: 'fd00::/64' }] } }];
  assert.equal(selectSubnet(networks, [{ dst: '10.240.1.0/24' }, { dst: '10.240.2.9' }]), '10.240.3.0/24');
});
test('a broader route rejects every overlapping candidate without mutation', () => {
  assert.throws(() => selectSubnet([], [{ dst: '10.0.0.0/8' }]), /No unused CI subnet/);
});
test('narrow routes inside a candidate still reject the complete candidate', () => {
  assert.equal(selectSubnet([], [{ dst: '10.240.0.128/25' }]), '10.240.1.0/24');
});
test('malformed address inventory fails closed', () => {
  assert.throws(() => selectSubnet([{ IPAM: { Config: [{ Subnet: '999.0.0.0/24' }] } }], []), /Invalid IPv4 CIDR/);
});
