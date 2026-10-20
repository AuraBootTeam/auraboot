# Tencent COS storage provider

Optional runtime provider, selected only by `aura.storage.type=cos`. The platform includes this module via `runtimeOnly`, following the existing OSS/MinIO/S3 packaging; SDK classes remain in the host, not business plugins.

Required server configuration: `aura.storage.cos.region`, `bucket` (full BucketName-APPID), `secret-id`, `secret-key`. Credentials have no defaults and are excluded from configuration `toString`. The provider uses HTTPS, never creates/probes buckets or changes ACL during startup, never logs signed URLs/credentials, and signs GET downloads with an explicit positive expiry of at most one hour. Upload returns an internal `cos://` locator; authorization remains the responsibility of the calling service. SDK dependency is `com.qcloud:cos_api:5.6.227`, the explicit published version in [Tencent quick start](https://cloud.tencent.com/document/product/436/10199). Signing API follows [Tencent presigned URL documentation](https://cloud.tencent.com/document/product/436/35217).

Default `:platform-storage-cos:test` is hermetic and excludes `*IT`. Nine unit tests cover HTTPS/configuration secrecy, upload metadata, download/delete/existence, short GET expiry, HTTP rejection, actual SDK offline signing/encoding, SDK error propagation, disposal and owner-only ACL classification (public/AuthenticatedUsers/other-account grants reject).

Explicit read-only cloud check:

```
aura gradle <owned-runtime> --project <core>/platform -- :platform-storage-cos:cosAclCheck -PcosAclReadOnly=true -PcosAclPrivateFile=<0600-private-json> -PcosAclReceipt=<sanitized-json>
```

Private JSON keys: `type`, `region`, `bucket`, `secretId`, `secretKey`. This task is restricted to the user-authorized `edu-1321667056`/`ap-nanjing` target and invokes only GET Bucket ACL. It does not write objects, buckets or ACL; its receipt contains only private-owner-only status, numeric HTTP status, safe category and writes=0. Credential/URL/raw exception details are never printed. 403 means ACL access could not be verified; it must not be treated as a private verdict. See [Tencent access control API](https://cloud.tencent.com/document/product/436/50708).

This iteration: 9 UT pass/0 skip, one explicit real GET ACL pass (200, PRIVATE_OWNER_ONLY, writes=0), platform plain jar generated. Evidence is retained in the team's private evidence store; no host startup, real media upload/download/playback or phone verification was performed by this provider task. The owning integration task controls formal staging, credentials, business private-storage declaration and cloud media tests.
