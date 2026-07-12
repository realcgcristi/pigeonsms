import type { Env } from '../types';

export class DmChannel {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(_req: Request): Promise<Response> {
    return Response.json(
      { error: { code: 'not_implemented', message: 'dms land in milestone 2' } },
      { status: 501 },
    );
  }
}
