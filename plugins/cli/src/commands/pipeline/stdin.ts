/**
 * Read JSON from stdin (for pipeline input).
 * Returns null if stdin is a TTY (no piped input).
 */
export async function readStdin(): Promise<any[] | null> {
  if (process.stdin.isTTY) return null;

  return new Promise((resolve, reject) => {
    let data = '';
    process.stdin.setEncoding('utf-8');
    process.stdin.on('data', (chunk) => { data += chunk; });
    process.stdin.on('end', () => {
      if (!data.trim()) {
        resolve(null);
        return;
      }
      try {
        const parsed = JSON.parse(data);
        resolve(Array.isArray(parsed) ? parsed : [parsed]);
      } catch (e) {
        reject(new Error(`Invalid JSON from stdin: ${(e as Error).message}`));
      }
    });
    process.stdin.on('error', reject);
  });
}
