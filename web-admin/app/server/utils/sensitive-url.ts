/** Redact known URL credentials in diagnostic text while leaving transport URLs untouched. */
export function redactSensitiveUrl(value: string): string {
  return value
    .replace(/(\/(?:api\/views\/shared|share)\/)[^/?#\s"\\]+/gi, '$1[REDACTED]')
    .replace(
      /([?&](?:password|passwd|token|access_token|refresh_token|id_token|client_secret|api_key)=)[^&#\s"\\]*/gi,
      '$1[REDACTED]',
    );
}
