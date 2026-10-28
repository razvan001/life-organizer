import { describe, expect, it } from 'vitest'
import type { ConstraintDto, GoalDto, SetupParseResponse } from '../api/types'
import { PARSED } from '../test/fixtures'
import { toDraft, toPayload } from './editSetup'
import { mergeExtraction } from './mergeExtraction'

const { warnings: _warnings, ...SAVED } = PARSED
const [CERTIFICATION] = SAVED.goals
const [WORK] = SAVED.constraints

function goal(changes: Partial<GoalDto>): GoalDto {
  return { ...CERTIFICATION, deadline: null, ...changes }
}

const NO_FIELDS: ConstraintDto = {
  ...WORK,
  start: null,
  end: null,
  days: null,
}

function frequency(goalId: string, timesPerWeek = 3): ConstraintDto {
  return { ...NO_FIELDS, type: 'FREQUENCY_QUOTA', label: 'Quota', timesPerWeek, sessionMinutes: 45, goalId }
}

function hours(categoryId: string): ConstraintDto {
  return { ...NO_FIELDS, type: 'DURATION_QUOTA', label: 'Hours', minutesPerWeek: 120, categoryId }
}

function evenings(categoryId: string): ConstraintDto {
  return { ...NO_FIELDS, type: 'EVENING_QUOTA', label: 'Evenings', eveningsPerWeek: 2, categoryId }
}

function extracted(changes: Partial<SetupParseResponse>): SetupParseResponse {
  return { categories: [], goals: [], constraints: [], warnings: [], ...changes }
}

describe('mergeExtraction', () => {
  const d = toDraft(SAVED)

  it('adds a new goal under an existing category with the slug id', () => {
    const result = mergeExtraction(
      d,
      extracted({ goals: [goal({ id: 'azure', categoryId: 'career', name: 'Azure AI', priority: 4 })] }),
    )

    expect(result.draft.goals[2]).toMatchObject({ id: 'career-azure-ai', categoryId: 'career', priority: 4 })
    expect(result.addedGoalIds).toEqual(['career-azure-ai'])
    expect(result.warnings).toEqual([])
  })

  it('adds a new category with the slug id and rewrites what points to it', () => {
    const result = mergeExtraction(
      d,
      extracted({
        categories: [{ id: 'fun-stuff', name: 'Hobbies' }],
        goals: [goal({ id: 'g1', categoryId: 'fun-stuff', name: 'Chess' })],
        constraints: [frequency('g1'), evenings('fun-stuff')],
      }),
    )

    expect(result.draft.categories).toContainEqual({ id: 'hobbies', name: 'Hobbies' })
    expect(result.draft.goals[2]).toMatchObject({ id: 'hobbies-chess', categoryId: 'hobbies' })
    expect(result.draft.constraints.slice(2)).toEqual([
      { key: 2, isNew: true, value: frequency('hobbies-chess') },
      { key: 3, isNew: true, value: evenings('hobbies') },
    ])
  })

  it('puts the goals of a category whose slug already exists under that category', () => {
    const result = mergeExtraction(
      d,
      extracted({
        categories: [{ id: 'x', name: 'career' }],
        goals: [goal({ id: 'g1', categoryId: 'x', name: 'Azure AI' })],
      }),
    )

    expect(result.draft.categories).toEqual(SAVED.categories)
    expect(result.draft.goals[2]).toMatchObject({ id: 'career-azure-ai', categoryId: 'career' })
  })

  it('skips a goal that already exists and keeps the saved one and its quota', () => {
    const result = mergeExtraction(
      d,
      extracted({
        goals: [goal({ id: 'gym', categoryId: 'health', name: 'Gym', priority: 1 })],
        constraints: [{ ...frequency('gym'), label: 'Gym' }],
      }),
    )

    expect(toPayload(result.draft)).toEqual(SAVED)
    expect(result.addedGoalIds).toEqual([])
    expect(result.warnings).toEqual([
      'Skipped goal "Gym": it already exists.',
      'Skipped "Gym: 3× per week, 45 min each": Gym already has a times-per-week quota.',
    ])
  })

  it('skips a second hours-per-week or evenings-per-week quota for a category', () => {
    const withBoth = toDraft({ ...SAVED, constraints: [...SAVED.constraints, hours('career'), evenings('career')] })

    const result = mergeExtraction(withBoth, extracted({ constraints: [hours('career'), evenings('career')] }))

    expect(result.draft.constraints).toHaveLength(4)
    expect(result.warnings).toEqual([
      'Skipped "Hours: 2h per week": Career already has an hours-per-week quota.',
      'Skipped "Evenings: 2 evenings per week": Career already has an evenings-per-week quota.',
    ])
  })

  it('skips a constraint whose goal or category is not in the setup', () => {
    const result = mergeExtraction(d, extracted({ constraints: [frequency('nope'), hours('nowhere')] }))

    expect(result.draft.constraints).toHaveLength(2)
    expect(result.warnings).toEqual([
      'Skipped "Quota: 3× per week, 45 min each": its goal is not in the setup.',
      'Skipped "Hours: 2h per week": its category is not in the setup.',
    ])
  })

  it('adds fixed blocks as they are', () => {
    const commute = { ...WORK, label: 'Commute', start: '07:30', end: '08:15' }

    const result = mergeExtraction(d, extracted({ constraints: [commute] }))

    expect(result.draft.constraints[2]).toEqual({ key: 2, isNew: true, value: commute })
  })

  it("lists the model's own warnings first", () => {
    const result = mergeExtraction(
      d,
      extracted({ warnings: ['Too vague: "more sport"'], constraints: [frequency('nope')] }),
    )

    expect(result.warnings[0]).toBe('Too vague: "more sport"')
    expect(result.warnings).toHaveLength(2)
  })

  it('does not add a quota the merge itself already added', () => {
    const result = mergeExtraction(
      d,
      extracted({ constraints: [frequency('career-claude-certification', 2), frequency('career-claude-certification', 5)] }),
    )

    expect(result.draft.constraints.slice(2).map((constraint) => constraint.value.timesPerWeek)).toEqual([2])
    expect(result.warnings).toHaveLength(1)
  })
})
