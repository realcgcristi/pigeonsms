import { readFile } from 'node:fs/promises'
import { describe, expect, it } from 'vitest'

describe('security settings theme', () => {
  it('uses theme text tokens for transparency and networkless copy', async () => {
    const css = await readFile(new URL('../src/screens/settings/Settings.css', import.meta.url), 'utf8')
    expect(css).toMatch(/\.settings-screen\s*\{[^}]*color:\s*var\(--text-primary\)/s)
    expect(css).toMatch(/\.transparency__status strong,[\s\S]*?\.networkless__status strong\s*\{[^}]*color:\s*var\(--text-primary\)/)
    expect(css).toMatch(/\.transparency__status span,[\s\S]*?color:\s*var\(--text-secondary\)/)
    expect(css).toMatch(/\.networkless__status span\s*\{[^}]*color:\s*var\(--text-secondary\)/)
  })
})
