import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectCall } from '@/api/gateway'

class FakeWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  static instances: FakeWebSocket[] = []

  readonly url: string
  readyState = FakeWebSocket.CONNECTING
  sent: string[] = []
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }

  send(payload: string) {
    if (this.readyState !== FakeWebSocket.OPEN) throw new Error('not open')
    this.sent.push(payload)
  }

  close() {
    if (this.readyState === FakeWebSocket.CLOSED) return
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.()
  }
}

afterEach(() => {
  FakeWebSocket.instances = []
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('call signaling recovery', () => {
  it('reconnects and flushes a bounded outage queue', async () => {
    vi.useFakeTimers()
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const opened: boolean[] = []
    const closed: boolean[] = []
    const call = connectCall('channel', 'voice', 'token', {
      onEvent: () => undefined,
      onOpen: (reconnected) => opened.push(reconnected),
      onClose: (willRetry) => closed.push(willRetry),
    })

    const first = FakeWebSocket.instances[0]
    expect(first?.url).toContain('/calls/channel/ws?mode=voice&token=token')
    first?.open()
    call.send({ type: 'mute', data: { muted: true } })
    expect(first?.sent).toHaveLength(1)

    first?.close()
    call.send({ type: 'camera', data: { on: false } })
    await vi.advanceTimersByTimeAsync(1_000)

    const second = FakeWebSocket.instances[1]
    expect(second?.sent).toHaveLength(0)
    second?.open()
    expect(second?.sent).toHaveLength(1)
    expect(opened).toEqual([false, true])
    expect(closed).toEqual([true])

    call.close()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(closed).toEqual([true, false])
  })
})
