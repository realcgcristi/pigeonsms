import { describe, expect, it } from 'vitest'
import { migratePrefs } from './prefs'

describe('preference migrations', () => {
  it('keeps existing settings and enables E2EE when upgrading from RC2', () => {
    expect(migratePrefs({ invisible: true, drafts: { dm: 'hello' } }, 2)).toMatchObject({
      invisible: true,
      drafts: { dm: 'hello' },
      e2ee: true,
    })
  })

  it('preserves an explicit RC3 E2EE choice', () => {
    expect(migratePrefs({ e2ee: false }, 3)).toMatchObject({ e2ee: false })
  })
})
