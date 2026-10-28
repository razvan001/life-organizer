import { describe, expect, it } from 'vitest'
import type { ConstraintDto } from '../api/types'
import { PARSED } from '../test/fixtures'
import {
  addConstraint,
  constraintProblem,
  hasProblems,
  hasQuota,
  removeConstraint,
  updateConstraint,
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

describe('editSetup: constraints', () => {
  const d = toDraft(SAVED)

  it('knows which targets already have a quota of a type', () => {
    expect(hasQuota(d, 'FREQUENCY_QUOTA', 'health-gym')).toBe(true)
    expect(hasQuota(d, 'FREQUENCY_QUOTA', 'career-claude-certification')).toBe(false)
    expect(hasQuota(d, 'DURATION_QUOTA', 'career')).toBe(false)
    expect(hasQuota(d, 'FIXED_BLOCK', 'Work')).toBe(false)
  })

  it('adds a constraint with a new key, marked as new', () => {
    const commute = { ...WORK, label: 'Commute', start: '07:30', end: '08:15' }

    const next = addConstraint(d, commute)

    expect(next.constraints[2]).toEqual({ key: 2, isNew: true, value: commute })
  })

  it('changes and removes only the constraint with that key', () => {
    const changed = updateConstraint(d, 1, { ...GYM_QUOTA, timesPerWeek: 3 })

    expect(changed.constraints[1].value.timesPerWeek).toBe(3)
    expect(changed.constraints[0].value).toEqual(WORK)
    expect(removeConstraint(d, 0).constraints.map((constraint) => constraint.value)).toEqual([GYM_QUOTA])
  })

  it('accepts every saved constraint', () => {
    for (const constraint of SAVED.constraints) expect(constraintProblem(constraint)).toBeNull()
    expect(hasProblems(d)).toBe(false)
  })

  it('reports a bad label', () => {
    expect(constraintProblem({ ...WORK, label: ' ' })).toBe('Enter a label.')
    expect(constraintProblem({ ...WORK, label: 'x'.repeat(256) })).toBe('A label can have at most 255 characters.')
  })

  it('reports bad times and days of a fixed block', () => {
    expect(constraintProblem({ ...WORK, start: '' })).toBe('Enter a start time.')
    expect(constraintProblem({ ...WORK, start: '8:3' })).toBe('Enter a start time.')
    expect(constraintProblem({ ...WORK, end: '' })).toBe('Enter an end time.')
    expect(constraintProblem({ ...WORK, days: [] })).toBe('Pick at least one day.')
  })

  it('reports counts that are not whole numbers of at least 1', () => {
    for (const timesPerWeek of [0, Number.NaN, 1.5]) {
      expect(constraintProblem({ ...GYM_QUOTA, timesPerWeek })).toBe(
        'Times per week must be a whole number of at least 1.',
      )
    }
    expect(constraintProblem({ ...GYM_QUOTA, sessionMinutes: 0 })).toBe(
      'Minutes per session must be a whole number of at least 1.',
    )
    const evenings: ConstraintDto = { ...GYM_QUOTA, type: 'EVENING_QUOTA', goalId: null, timesPerWeek: null, sessionMinutes: null, categoryId: 'health', eveningsPerWeek: 0 }
    expect(constraintProblem(evenings)).toBe('Evenings per week must be a whole number of at least 1.')
  })

  it('reports hours per week of 0 or empty', () => {
    const hours: ConstraintDto = { ...GYM_QUOTA, type: 'DURATION_QUOTA', goalId: null, timesPerWeek: null, sessionMinutes: null, categoryId: 'health', minutesPerWeek: 0 }

    expect(constraintProblem(hours)).toBe('Hours per week must be more than 0.')
    expect(constraintProblem({ ...hours, minutesPerWeek: Number.NaN })).toBe('Hours per week must be more than 0.')
    expect(hasProblems(addConstraint(d, hours))).toBe(true)
  })
})

describe('editSetup: review fixes', () => {
  it('does not count a saved category without goals as a change', () => {
    const saved = { ...SAVED, categories: [...SAVED.categories, { id: 'music', name: 'Music' }] }

    expect(isChanged(toDraft(saved), saved)).toBe(false)
  })

  it('refuses a goal whose id would be longer than 255 characters', () => {
    expect(errorOf(addGoal(toDraft(SAVED), 'x'.repeat(250), { existingId: 'health' }))).toBe(
      'This goal name is too long for its category. Use a shorter one.',
    )
  })

  it('checks the length of the label as it is sent', () => {
    expect(constraintProblem({ ...WORK, label: 'x'.repeat(250) + ' '.repeat(10) })).toBe(
      'A label can have at most 255 characters.',
    )
  })
})
