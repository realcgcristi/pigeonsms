export class BridgeRunner {
  constructor(client, adapter, { interval = 1500, onError = console.error } = {}) {
    this.client = client
    this.adapter = adapter
    this.interval = interval
    this.onError = onError
    this.running = false
    this.cursor = 0
  }

  async start() {
    const { bridge } = await this.client.me()
    this.cursor = bridge.cursor_seq || 0
    this.running = true
    void this.adapter.start((message) => this.client.push(message)).catch(this.onError)
    while (this.running) {
      try {
        const page = await this.client.pull(this.cursor)
        for (const message of page.messages || []) {
          await this.adapter.send(message)
          this.cursor = Math.max(this.cursor, Number(message.seq || 0))
          await this.client.ack(this.cursor)
        }
      } catch (error) {
        this.onError(error)
      }
      await new Promise((resolve) => setTimeout(resolve, this.interval))
    }
  }

  stop() {
    this.running = false
    this.adapter.stop()
  }
}
