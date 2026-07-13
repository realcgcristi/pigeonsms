import type { Env } from '../types';

export class Space {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(_req: Request): Promise<Response> {
    return Response.json(
      { error: { code: 'not_implemented', message: 'spaces land in milestone 3' } },
      { status: 501 },
    );
  }
}
