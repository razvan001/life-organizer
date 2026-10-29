import { describe, expect, it } from 'vitest'
import { SAVED } from '../test/fixtures'
import {
  addConstraint,
  addGoal,
  constraintProblem,
  type DraftSetup,
  type EditResult,
  hasProblems,
  hoursProblem,
  isChanged,
  removeConstraint,
  removeGoal,
  toDraft,
  toPayload,
  updateConstraint,
  updateGoal,
} from './editSetup'

const [WORK] = SAVED.constraints

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

  it('adds a goal with its hours and the default settings under an existing category', () => {
    const next = draftOf(addGoal(d, 'Azure AI', { existingId: 'career' }, 90))

    expect(next.goals).toHaveLength(3)
    expect(next.goals[2]).toEqual({
      id: 'career-azure-ai',
      categoryId: 'career',
      name: 'Azure AI',
      minutesPerWeek: 90,
      defaultBlockMinutes: 60,
      energy: 'MEDIUM',
      preferredTime: 'ANY',
    })
    expect(next.categories).toEqual(SAVED.categories)
  })

  it('creates a new category for a goal', () => {
    const next = draftOf(addGoal(d, 'Chess', { newName: 'Hobbies' }, 60))

    expect(next.categories).toContainEqual({ id: 'hobbies', name: 'Hobbies' })
    expect(next.goals.map((goal) => goal.id)).toContain('hobbies-chess')
  })

  it('trims the names', () => {
    const next = draftOf(addGoal(d, '  Chess  ', { newName: '  Hobbies ' }, 60))

    expect(next.goals[2].name).toBe('Chess')
    expect(next.categories[2]).toEqual({ id: 'hobbies', name: 'Hobbies' })
  })

  it('refuses a goal without a name', () => {
    expect(errorOf(addGoal(d, '', { existingId: 'career' }, 60))).toBe('Enter a goal name.')
    expect(errorOf(addGoal(d, '   ', { existingId: 'career' }, 60))).toBe('Enter a goal name.')
  })

  it('refuses a goal name longer than 255 characters', () => {
    expect(errorOf(addGoal(d, 'x'.repeat(256), { existingId: 'career' }, 60))).toBe(
      'A goal name can have at most 255 characters.',
    )
  })

  it('refuses a goal whose id already exists, even when the name differs only in case or punctuation', () => {
    expect(errorOf(addGoal(d, 'gym!', { existingId: 'health' }, 60))).toBe('Health already has the goal "Gym".')
  })

  it('allows the same goal name under another category', () => {
    expect(draftOf(addGoal(d, 'Gym', { existingId: 'career' }, 60)).goals[2].id).toBe('career-gym')
  })

  it('refuses hours off the half-hour grid', () => {
    expect(errorOf(addGoal(d, 'Chess', { newName: 'Hobbies' }, 78))).toBe(
      'Hours per week must be between 0 and 168, in half-hour steps.',
    )
  })

  it('refuses a new category that already exists', () => {
    expect(errorOf(addGoal(d, 'Chess', { newName: 'career' }, 60))).toBe(
      'The category "Career" already exists. Pick it from the list.',
    )
  })

  it('refuses a new category without a usable name', () => {
    expect(errorOf(addGoal(d, 'Chess', { newName: ' ' }, 60))).toBe('Enter a category name.')
    expect(errorOf(addGoal(d, 'Chess', { newName: 'x'.repeat(256) }, 60))).toBe(
      'A category name can have at most 255 characters.',
    )
  })

  it('changes the hours of one goal', () => {
    const next = updateGoal(d, 'health-gym', { minutesPerWeek: 180 })

    expect(next.goals[1]).toEqual({ ...SAVED.goals[1], minutesPerWeek: 180 })
    expect(next.goals[0]).toEqual(SAVED.goals[0])
  })

  it('removes a goal and keeps every fixed block', () => {
    const next = removeGoal(d, 'health-gym')

    expect(next.goals.map((goal) => goal.id)).toEqual(['career-claude-certification'])
    expect(next.constraints.map((constraint) => constraint.value)).toEqual([WORK])
  })

  it('does not change the draft it was given', () => {
    removeGoal(d, 'health-gym')
    draftOf(addGoal(d, 'Chess', { newName: 'Hobbies' }, 60))

    expect(toPayload(d)).toEqual(SAVED)
  })
})

describe('editSetup: hours', () => {
  it('accepts whole half hours from 0 to a full week', () => {
    for (const minutes of [0, 30, 90, 10080]) expect(hoursProblem(minutes)).toBeNull()
  })

  it('reports an empty, off-grid or out-of-range value', () => {
    expect(hoursProblem(Number.NaN)).toBe('Enter hours per week.')
    for (const minutes of [78, -30, 10110]) {
      expect(hoursProblem(minutes)).toBe('Hours per week must be between 0 and 168, in half-hour steps.')
    }
  })

  it('counts a goal with bad hours as a problem of the draft', () => {
    expect(hasProblems(toDraft(SAVED))).toBe(false)
    expect(hasProblems(updateGoal(toDraft(SAVED), 'health-gym', { minutesPerWeek: 78 }))).toBe(true)
    expect(hasProblems(updateGoal(toDraft(SAVED), 'health-gym', { minutesPerWeek: Number.NaN }))).toBe(true)
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

  it('tells whether the draft differs from the saved setup', () => {
    const d = toDraft(SAVED)

    expect(isChanged(d, SAVED)).toBe(false)
    expect(isChanged(updateGoal(d, 'health-gym', { minutesPerWeek: 180 }), SAVED)).toBe(true)
  })

  it('does not count a saved category without goals as a change', () => {
    const saved = { ...SAVED, categories: [...SAVED.categories, { id: 'music', name: 'Music' }] }

    expect(isChanged(toDraft(saved), saved)).toBe(false)
  })

  it('starts an empty draft from an empty setup', () => {
    expect(toPayload(toDraft({ categories: [], goals: [], constraints: [] }))).toEqual({
      categories: [],
      goals: [],
      constraints: [],
    })
  })
})

describe('editSetup: fixed blocks', () => {
  const d = toDraft(SAVED)

  it('adds a fixed block with a new key, marked as new', () => {
    const commute = { ...WORK, label: 'Commute', start: '07:30', end: '08:15' }

    const next = addConstraint(d, commute)

    expect(next.constraints[1]).toEqual({ key: 1, isNew: true, value: commute })
  })

  it('changes and removes only the fixed block with that key', () => {
    const withSleep = addConstraint(d, { ...WORK, label: 'Sleep', start: '23:30', end: '07:00' })
    const changed = updateConstraint(withSleep, 0, { ...WORK, start: '09:00' })

    expect(changed.constraints[0].value.start).toBe('09:00')
    expect(changed.constraints[1].value.label).toBe('Sleep')
    expect(removeConstraint(withSleep, 0).constraints.map((constraint) => constraint.value.label)).toEqual(['Sleep'])
  })

  it('accepts every saved fixed block', () => {
    for (const constraint of SAVED.constraints) expect(constraintProblem(constraint)).toBeNull()
  })

  it('reports a bad label', () => {
    expect(constraintProblem({ ...WORK, label: ' ' })).toBe('Enter a label.')
    expect(constraintProblem({ ...WORK, label: 'x'.repeat(256) })).toBe('A label can have at most 255 characters.')
    // the label is sent as typed, spaces included
    expect(constraintProblem({ ...WORK, label: 'x'.repeat(250) + ' '.repeat(10) })).toBe(
      'A label can have at most 255 characters.',
    )
  })

  it('reports bad times and days', () => {
    expect(constraintProblem({ ...WORK, start: '' })).toBe('Enter a start time.')
    expect(constraintProblem({ ...WORK, start: '8:3' })).toBe('Enter a start time.')
    expect(constraintProblem({ ...WORK, end: '' })).toBe('Enter an end time.')
    expect(constraintProblem({ ...WORK, days: [] })).toBe('Pick at least one day.')
    expect(hasProblems(addConstraint(d, { ...WORK, days: [] }))).toBe(true)
  })

  it('refuses a goal whose id would be longer than 255 characters', () => {
    expect(errorOf(addGoal(d, 'x'.repeat(250), { existingId: 'health' }, 60))).toBe(
      'This goal name is too long for its category. Use a shorter one.',
    )
  })
})
