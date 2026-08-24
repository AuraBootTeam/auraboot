/**
 * Normalize the dynamic-list response shape used by ListPageContent.
 * Detail responses are not part of the list-membership contract.
 *
 * @param {unknown} body
 * @param {string} responseLabel
 * @returns {Array<Record<string, unknown> & {pid?: string}>}
 */
export function dynamicListRecords(body, responseLabel) {
  const envelope = body;
  if (envelope?.code !== undefined && String(envelope.code) !== '0') {
    throw new Error(`${responseLabel} failed with code ${String(envelope.code)}`);
  }

  const payload = envelope?.data ?? body;
  if (Array.isArray(payload)) return payload;
  if (payload && typeof payload === 'object') {
    const records = payload.records ?? payload.data;
    if (Array.isArray(records)) return records;
  }
  throw new Error(`${responseLabel} did not expose a dynamic-list records array`);
}

/**
 * Prove that one public PID occurs exactly once in the current list response.
 * DOM uniqueness is asserted separately because a later render may reorder rows.
 *
 * @param {Array<Record<string, unknown> & {pid?: string}>} records
 * @param {string} recordPid
 * @param {string} recordLabel
 */
export function assertUniqueListRecordPid(records, recordPid, recordLabel) {
  if (!recordPid) {
    throw new Error(`${recordLabel} must expose a public PID for exact row targeting`);
  }
  const matchCount = records.filter((record) => String(record.pid ?? '') === recordPid).length;
  if (matchCount !== 1) {
    throw new Error(
      `${recordLabel} PID ${recordPid} must occur exactly once in the list response; found ${matchCount}`,
    );
  }
}
