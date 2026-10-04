"""Read independent receiver evidence without trusting its recorded valid flag."""

import base64
import hashlib
import hmac
import json
import math
from pathlib import Path
import sqlite3
import stat

from open_platform_http import ProtocolError


def require(condition, message):
    if not condition:
        raise ProtocolError(message)


def read_delivery(store, delivery_pid, *, secret, run_id, mode, failures):
    path = Path(store)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077,
            'receiver evidence must be a private regular file')
    with sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        identity = db.execute('SELECT identity FROM config').fetchall()
        require(len(identity) == 1, 'receiver identity is missing or ambiguous')
        config = json.loads(identity[0]['identity'])
        require(config == {'runId': run_id, 'mode': mode, 'failures': failures,
                           'secretSha256': hashlib.sha256(secret).hexdigest()},
                'receiver run, secret or fixed failure policy mismatch')
        columns = {row['name'] for row in db.execute('PRAGMA table_info(deliveries)')}
        require({'signature', 'timestamp_text'}.issubset(columns), 'raw signature evidence missing')
        return [dict(row) for row in db.execute(
            'SELECT * FROM deliveries WHERE delivery_id=? ORDER BY sequence', (delivery_pid,))]


def verify_delivery(rows, *, secret, delivery_pid, event_id, subject_pid, statuses):
    require(len(rows) == len(statuses) and bool(rows), 'receiver attempt denominator mismatch')
    bodies, proofs = [], []
    for row, status in zip(rows, statuses):
        raw = row.get('raw_body')
        require(isinstance(raw, bytes) and len(raw) <= 1024 * 1024, 'raw delivery bytes missing')
        timestamp = row.get('timestamp_text')
        require(isinstance(timestamp, str), 'raw timestamp missing')
        try:
            seconds = float(timestamp)
            received_at = float(row['received_at'])
        except (ValueError, TypeError, KeyError):
            raise ProtocolError('receiver timestamp is invalid') from None
        require(math.isfinite(seconds) and math.isfinite(received_at)
                and abs(received_at - seconds) <= 300, 'delivery timestamp window mismatch')
        signature = 'sha256=' + hmac.new(secret, timestamp.encode() + b'.' + raw,
                                         hashlib.sha256).hexdigest()
        require(isinstance(row.get('signature'), str)
                and hmac.compare_digest(row['signature'], signature), 'independent raw HMAC verification failed')
        require(row.get('signature_valid') == 1 and row.get('delivery_id') == delivery_pid
                and row.get('event_id') == event_id and row.get('response_status') == status,
                'receiver delivery identity/status mismatch')
        body_sha = hashlib.sha256(raw).hexdigest()
        require(row.get('body_sha') == body_sha, 'raw delivery checksum mismatch')
        payload = json.loads(raw)
        require(payload.get('id') == event_id and payload.get('type') == 'assets.assignment.changed'
                and payload.get('schemaVersion') == 1
                and payload.get('subject') == {'type': 'assets', 'pid': subject_pid},
                'receiver envelope does not match the fresh command')
        bodies.append(raw)
        proofs.append({'sequence': row['sequence'], 'receivedAt': received_at,
                       'timestamp': timestamp, 'signature': signature, 'responseStatus': status,
                       'duplicate': bool(row['duplicate']), 'rawBodyBase64': base64.b64encode(raw).decode(),
                       'bodySha256': body_sha})
    require(len(set(bodies)) == 1, 'retry/replay changed raw event bytes')
    return {'deliveryPid': delivery_pid, 'eventId': event_id, 'subjectPid': subject_pid,
            'attempts': len(rows), 'statuses': statuses, 'rawEvidence': proofs}
