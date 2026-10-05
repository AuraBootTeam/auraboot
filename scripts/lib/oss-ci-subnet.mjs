import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

function range(cidr) {
  const match = /^(\d+)\.(\d+)\.(\d+)\.(\d+)\/(\d+)$/.exec(cidr);
  if (!match) throw new Error(`Invalid IPv4 CIDR: ${cidr}`);
  const octets = match.slice(1, 5).map(Number);
  const prefix = Number(match[5]);
  if (octets.some(value => value > 255) || prefix > 32) throw new Error(`Invalid IPv4 CIDR: ${cidr}`);
  const value = octets.reduce((total, octet) => total * 256 + octet, 0);
  const size = 2 ** (32 - prefix);
  const start = Math.floor(value / size) * size;
  return [start, start + size - 1];
}

export function selectSubnet(networks, routes) {
  const occupied = [
    ...networks.flatMap(network => (network.IPAM?.Config ?? []).map(config => config.Subnet)),
    ...routes.filter(route => route.dst !== 'default').map(route => route.dst),
  ].filter(Boolean).filter(cidr => !cidr.includes(':')).map(cidr => range(cidr.includes('/') ? cidr : `${cidr}/32`));
  // Allocate only an unused RFC1918 /24. Never reclaim retained networks or
  // change the host daemon's default address pools.
  for (let third = 0; third < 256; third++) {
    const candidate = `10.240.${third}.0/24`;
    const [start, end] = range(candidate);
    if (occupied.every(([otherStart, otherEnd]) => end < otherStart || start > otherEnd)) return candidate;
  }
  throw new Error('No unused CI subnet in 10.240.0.0/16; retained networks and host routes were preserved');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const ids = execFileSync('docker', ['network', 'ls', '-q'], { encoding: 'utf8' }).trim().split(/\s+/).filter(Boolean);
    const networks = ids.length ? JSON.parse(execFileSync('docker', ['network', 'inspect', ...ids], { encoding: 'utf8' })) : [];
    const routes = JSON.parse(execFileSync('ip', ['-j', '-4', 'route', 'show', 'table', 'all'], { encoding: 'utf8' }));
    process.stdout.write(`${selectSubnet(networks, routes)}\n`);
  } catch (error) {
    process.stderr.write(`[oss-ci-subnet] ${error.message}\n`);
    process.exitCode = 2;
  }
}
