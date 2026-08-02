import { describe, expect, it } from 'vitest'
import worker from '../src/index'
import type { Env } from '../src/types'

function database(): D1Database {
  const statement = {
    bind() {
      return statement
    },
    async all() {
      return { results: [], success: true, meta: {} }
    },
  }
  return { prepare: () => statement } as unknown as D1Database
}

describe('public routes', () => {
  it('serves key transparency instead of the fallback route', async () => {
    const response = await worker.fetch(
      new Request('https://api.test/transparency/user-1'),
      { DB: database() } as Env,
      {} as ExecutionContext,
    )
    expect(response.status).toBe(200)
    await expect(response.json()).resolves.toMatchObject({
      entries: [],
      active_devices: [],
      checkpoint: { tree_size: 0 },
    })
  })
})
