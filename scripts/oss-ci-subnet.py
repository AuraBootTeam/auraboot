"""Select an explicit CI subnet without consuming Docker's default pools."""
import ipaddress
import json
import subprocess


def select_subnet(networks, routes):
    occupied = [ipaddress.ip_network(c['Subnet'], strict=False)
                for n in networks for c in (n.get('IPAM', {}).get('Config') or [])
                if c.get('Subnet')]
    occupied.extend(ipaddress.ip_network(r['dst'], strict=False)
                    for r in routes if r.get('dst') not in (None, 'default'))
    for candidate in ipaddress.ip_network('10.252.0.0/16').subnets(new_prefix=24):
        if not any(candidate.version == n.version and candidate.overlaps(n) for n in occupied):
            return str(candidate)
    raise RuntimeError('No collision-free subnet in the CI candidate range')


if __name__ == '__main__':
    ids = subprocess.check_output(['docker', 'network', 'ls', '-q'], text=True).split()
    networks = json.loads(subprocess.check_output(['docker', 'network', 'inspect', *ids], text=True)) if ids else []
    routes = json.loads(subprocess.check_output(['ip', '-j', 'route', 'show', 'table', 'all'], text=True))
    print(select_subnet(networks, routes))
