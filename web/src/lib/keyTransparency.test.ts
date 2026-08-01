import { describe, expect, it } from 'vitest'
import { merkleRoot } from './keyTransparency'

describe('transparency checkpoint', () => {
  it('is deterministic and sensitive to ordering', async () => {
    const first = await merkleRoot(['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)])
    const same = await merkleRoot(['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)])
    const swapped = await merkleRoot(['b'.repeat(64), 'a'.repeat(64), 'c'.repeat(64)])
    expect(first).toBe(same)
    expect(first).not.toBe(swapped)
  })
})
