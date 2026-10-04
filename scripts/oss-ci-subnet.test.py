import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('subnet', pathlib.Path(__file__).with_name('oss-ci-subnet.py'))
subnet = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subnet)


class SubnetSelectionTest(unittest.TestCase):
    def test_default_route_does_not_block_all_candidates(self):
        self.assertEqual(subnet.select_subnet([], [{'dst': 'default'}]), '10.252.0.0/24')

    def test_skips_existing_docker_network_and_host_route(self):
        networks = [{'IPAM': {'Config': [{'Subnet': '10.252.0.0/24'}]}}]
        self.assertEqual(subnet.select_subnet(networks, [{'dst': '10.252.1.0/24'}]), '10.252.2.0/24')

    def test_skips_supernet_routes_including_vpn(self):
        self.assertEqual(subnet.select_subnet([], [{'dst': '10.252.0.0/20'}]), '10.252.16.0/24')

    def test_fails_closed_when_candidate_range_is_routed(self):
        with self.assertRaises(RuntimeError):
            subnet.select_subnet([], [{'dst': '10.0.0.0/8'}])


if __name__ == '__main__':
    unittest.main()
