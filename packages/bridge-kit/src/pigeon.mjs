export class PigeonBridgeClient {
  constructor({ api, token, fetch: fetcher = globalThis.fetch }) {
    this.api = api.replace(/\/$/, '')
    this.token = token
    this.fetch = fetcher
  }

  async request(path, options = {}) {
    const response = await this.fetch(`${this.api}${path}`, {
      ...options,
      headers: {
        authorization: `Bearer ${this.token}`,
        'content-type': 'application/json',
        ...options.headers,
      },
      body: options.json === undefined ? options.body : JSON.stringify(options.json),
    })
    if (!response.ok) throw new Error(`Pigeon API ${response.status}: ${await response.text()}`)
    return response.status === 204 ? undefined : response.json()
  }

  me() {
    return this.request('/bridges/me')
  }

  pull(after = 0, limit = 50) {
    return this.request(`/bridges/me/messages?after=${after}&limit=${limit}`)
  }

  ack(seq) {
    return this.request('/bridges/me/ack', { method: 'POST', json: { seq } })
  }

  push({ id, author, content }) {
    return this.request('/bridges/me/messages', {
      method: 'POST',
      json: { external_id: id, author, content },
    })
  }
}
