import net from 'node:net'
import tls from 'node:tls'
import http from 'node:http'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const author = (message) => message.author?.display_name || message.author?.username || 'pigeon'
const text = (message) => `**${author(message)}:** ${message.content || ''}`

async function jsonFetch(url, options = {}) {
  const response = await fetch(url, options)
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`)
  return response.status === 204 ? null : response.json()
}

export class DiscordAdapter {
  constructor(config) {
    this.config = config
    this.after = config.after || '0'
    this.running = false
  }

  async send(message) {
    if (this.config.webhook) {
      await jsonFetch(this.config.webhook, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: author(message), content: message.content || ' ' }) })
      return
    }
    await jsonFetch(`https://discord.com/api/v10/channels/${this.config.channel}/messages`, { method: 'POST', headers: { authorization: `Bot ${this.config.token}`, 'content-type': 'application/json' }, body: JSON.stringify({ content: text(message) }) })
  }

  async start(onMessage) {
    if (!this.config.token || !this.config.channel) return
    this.running = true
    while (this.running) {
      const messages = await jsonFetch(`https://discord.com/api/v10/channels/${this.config.channel}/messages?after=${this.after}&limit=50`, { headers: { authorization: `Bot ${this.config.token}` } }).catch(() => [])
      for (const message of [...messages].reverse()) {
        this.after = message.id
        if (!message.author?.bot && message.content) await onMessage({ id: `discord:${message.id}`, author: message.author.global_name || message.author.username, content: message.content })
      }
      await sleep(2000)
    }
  }

  stop() { this.running = false }
}

export class SlackAdapter {
  constructor(config) {
    this.config = config
    this.oldest = config.oldest || String(Date.now() / 1000)
    this.running = false
  }

  async send(message) {
    if (this.config.webhook) {
      await jsonFetch(this.config.webhook, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ text: text(message) }) })
      return
    }
    await jsonFetch('https://slack.com/api/chat.postMessage', { method: 'POST', headers: { authorization: `Bearer ${this.config.token}`, 'content-type': 'application/json' }, body: JSON.stringify({ channel: this.config.channel, text: text(message) }) })
  }

  async start(onMessage) {
    if (!this.config.token || !this.config.channel) return
    this.running = true
    while (this.running) {
      const data = await jsonFetch(`https://slack.com/api/conversations.history?channel=${encodeURIComponent(this.config.channel)}&oldest=${encodeURIComponent(this.oldest)}&limit=100`, { headers: { authorization: `Bearer ${this.config.token}` } }).catch(() => ({ messages: [] }))
      for (const message of [...(data.messages || [])].reverse()) {
        if (Number(message.ts) > Number(this.oldest)) this.oldest = message.ts
        if (!message.bot_id && message.text) await onMessage({ id: `slack:${message.ts}`, author: message.user || 'slack', content: message.text })
      }
      await sleep(2500)
    }
  }

  stop() { this.running = false }
}

export class MatrixAdapter {
  constructor(config) {
    this.config = config
    this.since = config.since
    this.running = false
  }

  headers() { return { authorization: `Bearer ${this.config.token}`, 'content-type': 'application/json' } }

  async send(message) {
    const txn = encodeURIComponent(`pigeon-${message.id}`)
    const room = encodeURIComponent(this.config.room)
    await jsonFetch(`${this.config.homeserver}/_matrix/client/v3/rooms/${room}/send/m.room.message/${txn}`, { method: 'PUT', headers: this.headers(), body: JSON.stringify({ msgtype: 'm.text', body: text(message) }) })
  }

  async start(onMessage) {
    this.running = true
    while (this.running) {
      const query = new URLSearchParams({ timeout: '25000' })
      if (this.since) query.set('since', this.since)
      const data = await jsonFetch(`${this.config.homeserver}/_matrix/client/v3/sync?${query}`, { headers: this.headers() }).catch(() => null)
      if (!data) { await sleep(2000); continue }
      this.since = data.next_batch
      const events = data.rooms?.join?.[this.config.room]?.timeline?.events || []
      for (const event of events) {
        if (event.type === 'm.room.message' && event.content?.msgtype === 'm.text') {
          await onMessage({ id: `matrix:${event.event_id}`, author: event.sender, content: event.content.body })
        }
      }
    }
  }

  stop() { this.running = false }
}

export class IrcAdapter {
  constructor(config) {
    this.config = config
    this.socket = null
    this.onMessage = null
  }

  write(line) { this.socket?.write(`${line}\r\n`) }

  async start(onMessage) {
    this.onMessage = onMessage
    const connect = this.config.tls === false ? net.connect : tls.connect
    this.socket = connect({ host: this.config.host, port: this.config.port || (this.config.tls === false ? 6667 : 6697), rejectUnauthorized: this.config.rejectUnauthorized !== false }, () => {
      if (this.config.password) this.write(`PASS ${this.config.password}`)
      this.write(`NICK ${this.config.nick || 'pigeon-bridge'}`)
      this.write(`USER ${this.config.nick || 'pigeon'} 0 * :PigeonSMS bridge`)
      this.write(`JOIN ${this.config.channel}`)
    })
    let buffer = ''
    this.socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      const lines = buffer.split('\r\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('PING ')) this.write(`PONG ${line.slice(5)}`)
        const match = line.match(/^:([^!]+)![^ ]+ PRIVMSG ([^ ]+) :(.+)$/)
        if (match && match[2].toLowerCase() === this.config.channel.toLowerCase() && match[1] !== this.config.nick) {
          void onMessage({ id: `irc:${Date.now()}:${match[1]}`, author: match[1], content: match[3] })
        }
      }
    })
    await new Promise((resolve) => this.socket.once('close', resolve))
  }

  async send(message) { this.write(`PRIVMSG ${this.config.channel} :${author(message)}: ${String(message.content || '').replace(/[\r\n]+/g, ' ')}`) }
  stop() { this.socket?.end() }
}

async function smtpCommand(socket, command, expected) {
  if (command) socket.write(`${command}\r\n`)
  const response = await new Promise((resolve, reject) => {
    const handler = (chunk) => { socket.off('error', reject); resolve(chunk.toString('utf8')) }
    socket.once('data', handler)
    socket.once('error', reject)
  })
  if (!String(response).startsWith(String(expected))) throw new Error(`SMTP: ${response}`)
}

export class EmailAdapter {
  constructor(config) {
    this.config = config
    this.server = null
  }

  async send(message) {
    const connect = this.config.secure === false ? net.connect : tls.connect
    const socket = connect({ host: this.config.host, port: this.config.port || (this.config.secure === false ? 25 : 465), rejectUnauthorized: this.config.rejectUnauthorized !== false })
    await smtpCommand(socket, null, 220)
    await smtpCommand(socket, `EHLO ${this.config.helo || 'pigeonsms.local'}`, 250)
    if (this.config.username) {
      await smtpCommand(socket, 'AUTH LOGIN', 334)
      await smtpCommand(socket, Buffer.from(this.config.username).toString('base64'), 334)
      await smtpCommand(socket, Buffer.from(this.config.password || '').toString('base64'), 235)
    }
    await smtpCommand(socket, `MAIL FROM:<${this.config.from}>`, 250)
    await smtpCommand(socket, `RCPT TO:<${this.config.to}>`, 250)
    await smtpCommand(socket, 'DATA', 354)
    const body = String(message.content || '').replace(/\r?\n/g, '\r\n').replace(/^\./gm, '..')
    socket.write(`From: ${this.config.from}\r\nTo: ${this.config.to}\r\nSubject: [PigeonSMS] ${author(message)}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${body}\r\n.\r\n`)
    await smtpCommand(socket, null, 250)
    socket.end('QUIT\r\n')
  }

  async start(onMessage) {
    if (!this.config.webhookPort) return
    this.server = http.createServer((request, response) => {
      if (request.method !== 'POST' || request.headers.authorization !== `Bearer ${this.config.webhookSecret}`) { response.writeHead(401).end(); return }
      const chunks = []
      request.on('data', (chunk) => chunks.push(chunk))
      request.on('end', () => {
        try {
          const value = JSON.parse(Buffer.concat(chunks).toString('utf8'))
          void onMessage({ id: `email:${value.id || Date.now()}`, author: value.from || 'email', content: [value.subject, value.text].filter(Boolean).join('\n\n') })
          response.writeHead(202).end()
        } catch { response.writeHead(400).end() }
      })
    })
    await new Promise((resolve) => this.server.listen(this.config.webhookPort, this.config.webhookHost || '127.0.0.1', resolve))
  }

  stop() { this.server?.close() }
}

export function createAdapter(kind, config) {
  const adapters = { discord: DiscordAdapter, slack: SlackAdapter, matrix: MatrixAdapter, irc: IrcAdapter, email: EmailAdapter }
  const Adapter = adapters[kind]
  if (!Adapter) throw new Error(`unsupported adapter: ${kind}`)
  return new Adapter(config)
}
