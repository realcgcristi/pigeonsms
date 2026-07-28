export class PigeonError extends Error {
  constructor(message, { status = 0, code = 'client_error', requestId = null, body = null, cause } = {}) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'PigeonError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.body = body;
  }

  get retryable() {
    return this.status === 429 || (this.status >= 500 && this.status < 600);
  }

  toString() {
    const where = this.status ? `${this.status} ${this.code}` : this.code;
    return `PigeonError [${where}]: ${this.message}${this.requestId ? ` (request ${this.requestId})` : ''}`;
  }
}

export function errorFromResponse(status, body, fallback) {
  const envelope = body && typeof body === 'object' ? body.error : null;
  return new PigeonError(envelope?.message || fallback || `request failed with ${status}`, {
    status,
    code: envelope?.code || httpCode(status),
    requestId: envelope?.request_id ?? null,
    body,
  });
}

function httpCode(status) {
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 429) return 'rate_limited';
  if (status >= 500) return 'internal';
  return 'bad_request';
}
