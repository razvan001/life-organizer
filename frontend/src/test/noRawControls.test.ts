import { describe, expect, it } from 'vitest'

// Dropdowns and date / time fields go through components/form (Select, DateInput, TimeInput), so every page gets
// the same look. See frontend/UI.md.
const SOURCES = import.meta.glob<string>(['../**/*.tsx', '!../**/*.test.tsx', '!../components/form/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
})
const RAW_CONTROL = /<select\b|type="date"|type="time"/

describe('form controls', () => {
  it('no component renders a raw select, date or time input', () => {
    const offenders = Object.entries(SOURCES)
      .filter(([, source]) => RAW_CONTROL.test(source))
      .map(([path]) => path)

    expect(Object.keys(SOURCES).length).toBeGreaterThan(10) // the glob found the app's components
    expect(offenders).toEqual([])
  })
})
