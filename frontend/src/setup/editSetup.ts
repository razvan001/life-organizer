import type { CategoryDto, ConstraintDto, GoalDto, SetupPayload } from '../api/types'
import { categoryIdFor, goalIdFor } from './slug'

// Every change to the setup on the Setup page goes through these functions. They never change the draft they are
// given, and they keep every value the user did not touch, so Save sends it back unchanged.

/** A fixed block on screen. `key` stays the same while it is edited; it is also the React key. */
export interface DraftConstraint {
  key: number
  isNew: boolean
  value: ConstraintDto
}

export interface DraftSetup {
  categories: CategoryDto[]
  goals: GoalDto[]
  constraints: DraftConstraint[]
}

export type CategoryChoice = { existingId: string } | { newName: string }

export type EditResult = { ok: true; draft: DraftSetup } | { ok: false; error: string }

/** The backend's column limit, for names, labels and ids. */
export const MAX_TEXT_LENGTH = 255

/** A full week, the most hours a goal can have. */
export const MAX_MINUTES_PER_WEEK = 7 * 24 * 60

/** The engine plans on a 30-minute grid, so hours go in half-hour steps. */
const GRID_MINUTES = 30

export const EMPTY_SETUP: SetupPayload = { categories: [], goals: [], constraints: [] }

export function toDraft(saved: SetupPayload): DraftSetup {
  return {
    categories: saved.categories,
    goals: saved.goals,
    constraints: saved.constraints.map((value, key) => ({ key, isNew: false, value })),
  }
}

/** What Save sends. A category without goals is dropped. */
export function toPayload(draft: DraftSetup): SetupPayload {
  const categories = draft.categories.filter((category) => draft.goals.some((goal) => goal.categoryId === category.id))
  return { categories, goals: draft.goals, constraints: draft.constraints.map((constraint) => constraint.value) }
}

// Both sides go through toPayload: a saved category without goals is dropped on Save either way, so it is not a
// change the user made.
export function isChanged(draft: DraftSetup, saved: SetupPayload): boolean {
  return JSON.stringify(toPayload(draft)) !== JSON.stringify(toPayload(toDraft(saved)))
}

/** What is wrong with a goal's minutes per week, as the message shown next to it, or null. Mirrors the backend. */
export function hoursProblem(minutes: number): string | null {
  // an emptied or unreadable hours field arrives as NaN
  if (Number.isNaN(minutes)) return 'Enter hours per week.'
  if (!Number.isInteger(minutes) || minutes < 0 || minutes > MAX_MINUTES_PER_WEEK || minutes % GRID_MINUTES !== 0) {
    return 'Hours per week must be between 0 and 168, in half-hour steps.'
  }
  return null
}

const refuse = (error: string): EditResult => ({ ok: false, error })

export function addGoal(draft: DraftSetup, name: string, category: CategoryChoice, minutesPerWeek: number): EditResult {
  const goalName = name.trim()
  if (goalName === '') return refuse('Enter a goal name.')
  if (goalName.length > MAX_TEXT_LENGTH) return refuse('A goal name can have at most 255 characters.')
  const badHours = hoursProblem(minutesPerWeek)
  if (badHours) return refuse(badHours)

  let target: CategoryDto | undefined
  let categories = draft.categories
  if ('newName' in category) {
    const categoryName = category.newName.trim()
    if (categoryName === '') return refuse('Enter a category name.')
    if (categoryName.length > MAX_TEXT_LENGTH) return refuse('A category name can have at most 255 characters.')
    const existing = draft.categories.find((candidate) => candidate.id === categoryIdFor(categoryName))
    if (existing) return refuse(`The category "${existing.name}" already exists. Pick it from the list.`)
    target = { id: categoryIdFor(categoryName), name: categoryName }
    categories = [...categories, target]
  } else {
    target = draft.categories.find((candidate) => candidate.id === category.existingId)
    if (!target) return refuse('Pick a category.')
  }

  const id = goalIdFor(target.id, goalName)
  if (id.length > MAX_TEXT_LENGTH) return refuse('This goal name is too long for its category. Use a shorter one.')
  // goal ids are global keys, so the check covers every category
  const clash = draft.goals.find((goal) => goal.id === id)
  if (clash) {
    const owner = draft.categories.find((candidate) => candidate.id === clash.categoryId)?.name ?? target.name
    return refuse(`${owner} already has the goal "${clash.name}".`)
  }

  // the backend's defaults for a new goal (Goal.withDefaults)
  const goal: GoalDto = {
    id,
    categoryId: target.id,
    name: goalName,
    minutesPerWeek,
    defaultBlockMinutes: 60,
    energy: 'MEDIUM',
    preferredTime: 'ANY',
  }
  return { ok: true, draft: { ...draft, categories, goals: [...draft.goals, goal] } }
}

export function updateGoal(draft: DraftSetup, goalId: string, changes: Pick<GoalDto, 'minutesPerWeek'>): DraftSetup {
  return { ...draft, goals: draft.goals.map((goal) => (goal.id === goalId ? { ...goal, ...changes } : goal)) }
}

export function removeGoal(draft: DraftSetup, goalId: string): DraftSetup {
  return { ...draft, goals: draft.goals.filter((goal) => goal.id !== goalId) }
}

export function addConstraint(draft: DraftSetup, value: ConstraintDto): DraftSetup {
  const key = Math.max(-1, ...draft.constraints.map((constraint) => constraint.key)) + 1
  return { ...draft, constraints: [...draft.constraints, { key, isNew: true, value }] }
}

export function updateConstraint(draft: DraftSetup, key: number, value: ConstraintDto): DraftSetup {
  return {
    ...draft,
    constraints: draft.constraints.map((constraint) => (constraint.key === key ? { ...constraint, value } : constraint)),
  }
}

export function removeConstraint(draft: DraftSetup, key: number): DraftSetup {
  return { ...draft, constraints: draft.constraints.filter((constraint) => constraint.key !== key) }
}

const TIME = /^\d{2}:\d{2}$/

/** The first thing wrong with a fixed block, as the message shown next to it, or null. Mirrors the backend. */
export function constraintProblem(constraint: ConstraintDto): string | null {
  if (constraint.label.trim() === '') return 'Enter a label.'
  // the label is sent as typed, spaces included
  if (constraint.label.length > MAX_TEXT_LENGTH) return 'A label can have at most 255 characters.'
  if (!TIME.test(constraint.start ?? '')) return 'Enter a start time.'
  if (!TIME.test(constraint.end ?? '')) return 'Enter an end time.'
  if (!constraint.days || constraint.days.length === 0) return 'Pick at least one day.'
  return null
}

export function hasProblems(draft: DraftSetup): boolean {
  return (
    draft.goals.some((goal) => hoursProblem(goal.minutesPerWeek) !== null) ||
    draft.constraints.some((constraint) => constraintProblem(constraint.value) !== null)
  )
}
