import type { Env } from '../types';

export class UserGateway {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    if (url.pathname === '/bind' && req.method === 'POST') {
      await this.state.storage.put('uid', await req.text());
      return new Response(null, { status: 204 });
    }

    if (req.headers.get('upgrade')?.toLowerCase() === 'websocket') {
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      await this.touchPresence();
      return new Response(null, { status: 101, webSocket: pair[0] });
    }

    if (url.pathname === '/notify' && req.method === 'POST') {
      const payload = await req.text();
      const sockets = this.state.getWebSockets();
      let delivered = 0;
      for (const ws of sockets) {
        try {
          ws.send(payload);
          delivered += 1;
        } catch {
        }
      }
      return Response.json({ delivered });
    }

    return new Response('not found', { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (message === 'ping') {
      ws.send('pong');
      await this.touchPresence();
    }
  }

  async webSocketClose(): Promise<void> {
    await this.touchPresence();
  }

  private async touchPresence(): Promise<void> {
    const uid = await this.state.storage.get<string>('uid');
    if (!uid) return;
    await this.env.DB.prepare('UPDATE users SET last_online = ? WHERE id = ?')
      .bind(Date.now(), uid)
      .run();
  }
}
