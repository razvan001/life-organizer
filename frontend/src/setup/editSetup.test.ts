import { describe, expect, it } from 'vitest'
import type { ConstraintDto } from '../api/types'
import { PARSED } from '../test/fixtures'
import {
  addGoal,
  constraintsRemovedWith,
  type DraftSetup,
  type EditResult,
  isChanged,
  removeGoal,
  toDraft,
  toPayload,
  updateGoal,
} from './editSetup'

const { warnings: _warnings, ...SAVED } = PARSED
const [WORK, GYM_QUOTA] = SAVED.constraints

function draftOf(result: EditResult): DraftSetup {
  if (!result.ok) throw new Error(`expected ok, got: ${result.error}`)
  return result.draft
}

function errorOf(result: EditResult): string {
  if (result.ok) throw new Error('expected a refusal')
  return result.error
}

describe('editSetup: goals', () => {
  const d = toDraft(SAVED)

  it('adds a goal with the defaults under an existing category', () => {
    const next = draftOf(addGoal(d, 'Azure AI', { existingId: 'career' }))

    expect(next.goals).toHaveLength(3)
    expect(next.goals[2]).toEqual({
      id: 'career-azure-ai',
      categoryId: 'career',
      name: 'Azure AI',
      priority: 3,
      deadline: null,
      defaultBlockMinutes: 60,
      energy: 'MEDIUM',
      preferredTime: 'ANY',
    })
    expect(next.categories).toEqual(SAVED.categories)
  })

  it('creates a new category for a goal', () => {
    const next = draftOf(addGoal(d, 'Chess', { newName: 'Hobbies' }))

    expect(next.categories).toContainEqual({ id: 'hobbies', name: 'Hobbies' })
    expect(next.goals.map((goal) => goal.id)).toContain('hobbies-chess')
  })

  it('trims the names', () => {
    const next = draftOf(addGoal(d, '  Chess  ', { newName: '  Hobbies ' }))

    expect(next.goals[2].name).toBe('Chess')
    expect(next.categories[2]).toEqual({ id: 'hobbies', name: 'Hobbies' })
  })

  it('refuses a goal without a name', () => {
    expect(errorOf(addGoal(d, '', { existingId: 'career' }))).toBe('Enter a goal name.')
    expect(errorOf(addGoal(d, '   ', { existingId: 'career' }))).toBe('Enter a goal name.')
  })

  it('refuses a goal name longer than 255 characters', () => {
    expect(errorOf(addGoal(d, 'x'.repeat(256), { existingId: 'career' }))).toBe(
      'A goal name can have at most 255 characters.',
    )
  })

  it('refuses a goal whose id already exists', () => {
    expect(errorOf(addGoal(d, 'gym!', { existingId: 'health' }))).toBe('Health already has the goal "Gym".')
  })

  it('refuses a new category that already exists', () => {
    expect(errorOf(addGoal(d, 'Chess', { newName: 'career' }))).toBe(
      'The category "Career" already exists. Pick it from the list.',
    )
  })

  it('refuses a new category without a usable name', () => {
    expect(errorOf(addGoal(d, 'Chess', { newName: ' ' }))).toBe('Enter a category name.')
    expect(errorOf(addGoal(d, 'Chess', { newName: 'x'.repeat(256) }))).toBe(
      'A category name can have at most 255 characters.',
    )
  })

  it('changes priority and deadline of one goal', () => {
    const next = updateGoal(d, 'health-gym', { priority: 5, deadline: '2026-12-01' })

    expect(next.goals[1]).toEqual({ ...SAVED.goals[1], priority: 5, deadline: '2026-12-01' })
    expect(next.goals[0]).toEqual(SAVED.goals[0])
  })

  it('removes a goal together with its times-per-week quota', () => {
    expect(constraintsRemovedWith(d, 'health-gym')).toEqual([GYM_QUOTA])

    const next = removeGoal(d, 'health-gym')

    expect(next.goals.map((goal) => goal.id)).toEqual(['career-claude-certification'])
    expect(next.constraints.map((constraint) => constraint.value)).toEqual([WORK])
  })

  it('does not change the draft it was given', () => {
    removeGoal(d, 'health-gym')
    draftOf(addGoal(d, 'Chess', { newName: 'Hobbies' }))

    expect(toPayload(d)).toEqual(SAVED)
  })
})

describe('editSetup: payload', () => {
  it('returns untouched settings unchanged', () => {
    expect(toPayload(toDraft(SAVED))).toEqual(SAVED)
  })

  it('drops a category left without goals', () => {
    const next = removeGoal(toDraft(SAVED), 'career-claude-certification')

    expect(toPayload(next).categories).toEqual([{ id: 'health', name: 'Health' }])
  })

  it('keeps an empty category a quota points to', () => {
    const hours: ConstraintDto = {
      ...WORK,
      type: 'DURATION_QUOTA',
      label: 'Career',
      start: null,
      end: null,
      days: null,
      minutesPerWeek: 300,
      categoryId: 'career',
    }
    const withHours = toDraft({ ...SAVED, constraints: [...SAVED.constraints, hours] })

    const next = removeGoal(withHours, 'career-claude-certification')

    expect(toPayload(next).categories.map((category) => category.id)).toEqual(['career', 'health'])
  })

  it('tells whether the draft differs from the saved setup', () => {
    const d = toDraft(SAVED)

    expect(isChanged(d, SAVED)).toBe(false)
    expect(isChanged(updateGoal(d, 'health-gym', { priority: 5 }), SAVED)).toBe(true)
  })
})
