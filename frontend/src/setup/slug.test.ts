import { describe, expect, it } from 'vitest'
import { categoryIdFor, goalIdFor, slug } from './slug'

// The same rule as the backend's Slug.of, so ids made here match the ones the text parser makes.
describe('slug', () => {
  it('turns every run of other characters into one dash', () => {
    expect(slug('Padel / football')).toBe('padel-football')
  })

  it('drops leading and trailing dashes and spaces', () => {
    expect(slug('  Side project!  ')).toBe('side-project')
  })

  it('uses "item" when nothing is left', () => {
    expect(slug('---')).toBe('item')
  })

  it('builds category and goal ids', () => {
    expect(categoryIdFor('Side Project')).toBe('side-project')
    expect(goalIdFor('career', 'Claude certification')).toBe('career-claude-certification')
  })
})
