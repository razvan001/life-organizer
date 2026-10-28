import type { CategoryDto, ConstraintDto, GoalDto, SetupPayload } from '../api/types'
import { categoryIdFor, goalIdFor } from './slug'

// Every change to a saved setup on the Setup page goes through these functions. They never change the draft they
// are given, and they keep every value the user did not touch, so Save sends it back unchanged.

/** A constraint on screen. `key` stays the same while the constraint is edited; it is also the React key. */
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

/** The backend's column limit. */
const MAX_TEXT_LENGTH = 255

export function toDraft(saved: SetupPayload): DraftSetup {
  return {
    categories: saved.categories,
    goals: saved.goals,
    constraints: saved.constraints.map((value, key) => ({ key, isNew: false, value })),
  }
}

/** What Save sends. A category without goals is dropped, unless a constraint still points to it. */
export function toPayload(draft: DraftSetup): SetupPayload {
  const constraints = draft.constraints.map((constraint) => constraint.value)
  const categories = draft.categories.filter(
    (category) =>
      draft.goals.some((goal) => goal.categoryId === category.id) ||
      constraints.some((constraint) => constraint.categoryId === category.id),
  )
  return { categories, goals: draft.goals, constraints }
}

export function isChanged(draft: DraftSetup, saved: SetupPayload): boolean {
  return JSON.stringify(toPayload(draft)) !== JSON.stringify(saved)
}

const refuse = (error: string): EditResult => ({ ok: false, error })

export function addGoal(draft: DraftSetup, name: string, category: CategoryChoice): EditResult {
  const goalName = name.trim()
  if (goalName === '') return refuse('Enter a goal name.')
  if (goalName.length > MAX_TEXT_LENGTH) return refuse('A goal name can have at most 255 characters.')

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
  // goal ids are global keys, so the check covers every category
  const clash = draft.goals.find((goal) => goal.id === id)
  if (clash) {
    const owner = draft.categories.find((candidate) => candidate.id === clash.categoryId)?.name ?? target.name
    return refuse(`${owner} already has the goal "${clash.name}".`)
  }

  // the defaults the text parser gives a new goal (Goal.withDefaults)
  const goal: GoalDto = {
    id,
    categoryId: target.id,
    name: goalName,
    priority: 3,
    deadline: null,
    defaultBlockMinutes: 60,
    energy: 'MEDIUM',
    preferredTime: 'ANY',
  }
  return { ok: true, draft: { ...draft, categories, goals: [...draft.goals, goal] } }
}

export function updateGoal(
  draft: DraftSetup,
  goalId: string,
  changes: Partial<Pick<GoalDto, 'priority' | 'deadline'>>,
): DraftSetup {
  return { ...draft, goals: draft.goals.map((goal) => (goal.id === goalId ? { ...goal, ...changes } : goal)) }
}

/** The constraints that only make sense with this goal: they go when the goal goes. */
export function constraintsRemovedWith(draft: DraftSetup, goalId: string): ConstraintDto[] {
  return draft.constraints.map((constraint) => constraint.value).filter((value) => pointsToGoal(value, goalId))
}

export function removeGoal(draft: DraftSetup, goalId: string): DraftSetup {
  return {
    ...draft,
    goals: draft.goals.filter((goal) => goal.id !== goalId),
    constraints: draft.constraints.filter((constraint) => !pointsToGoal(constraint.value, goalId)),
  }
}

function pointsToGoal(constraint: ConstraintDto, goalId: string): boolean {
  return constraint.type === 'FREQUENCY_QUOTA' && constraint.goalId === goalId
}
