import type { CategoryDto, ConstraintDto, GoalDto, SetupParseResponse } from '../api/types'
import { describeConstraint } from './describeConstraint'
import { addConstraint, type DraftSetup, hasQuota } from './editSetup'
import { categoryIdFor, goalIdFor } from './slug'

export interface MergeResult {
  draft: DraftSetup
  warnings: string[]
  addedGoalIds: string[]
}

const QUOTA_NAMES: Record<string, string> = {
  FREQUENCY_QUOTA: 'a times-per-week quota',
  DURATION_QUOTA: 'an hours-per-week quota',
  EVENING_QUOTA: 'an evenings-per-week quota',
}

/**
 * Adds a brain-dump extraction to the setup on screen; it never replaces anything. The model sees only the
 * existing categories and invents its own goal ids, so every extracted goal and new category first gets the id
 * the rest of the page uses (see slug.ts). Anything that would clash is skipped with a warning.
 */
export function mergeExtraction(draft: DraftSetup, extracted: SetupParseResponse): MergeResult {
  const warnings = [...extracted.warnings]
  const categories = [...draft.categories]
  const goals = [...draft.goals]
  const addedGoalIds: string[] = []

  // the model's category id -> the id on the page
  const categoryIds = new Map<string, string>()
  const extractedCategories: CategoryDto[] = []
  for (const category of extracted.categories) {
    const id = categories.some((existing) => existing.id === category.id) ? category.id : categoryIdFor(category.name)
    categoryIds.set(category.id, id)
    if (!categories.some((existing) => existing.id === id) && !extractedCategories.some((added) => added.id === id)) {
      extractedCategories.push({ id, name: category.name })
    }
  }
  const pageCategoryId = (id: string | null) => (id === null ? null : (categoryIds.get(id) ?? id))
  // a new category is only added once something that is added uses it
  const useCategory = (id: string) => {
    const pending = extractedCategories.find((category) => category.id === id)
    if (pending && !categories.includes(pending)) categories.push(pending)
  }
  const categoryExists = (id: string) =>
    categories.some((category) => category.id === id) || extractedCategories.some((category) => category.id === id)

  // the model's goal id -> the id on the page
  const goalIds = new Map<string, string>()
  for (const extractedGoal of extracted.goals) {
    const categoryId = pageCategoryId(extractedGoal.categoryId)!
    const id = goalIdFor(categoryId, extractedGoal.name)
    goalIds.set(extractedGoal.id, id)
    if (goals.some((goal) => goal.id === id)) {
      warnings.push(`Skipped goal "${extractedGoal.name}": it already exists.`)
      continue
    }
    if (!categoryExists(categoryId)) {
      warnings.push(`Skipped goal "${extractedGoal.name}": its category is not in the setup.`)
      continue
    }
    useCategory(categoryId)
    const goal: GoalDto = { ...extractedGoal, id, categoryId }
    goals.push(goal)
    addedGoalIds.push(id)
  }

  let next: DraftSetup = { ...draft, categories, goals }
  for (const extractedConstraint of extracted.constraints) {
    const constraint: ConstraintDto = {
      ...extractedConstraint,
      goalId: extractedConstraint.goalId === null ? null : (goalIds.get(extractedConstraint.goalId) ?? extractedConstraint.goalId),
      categoryId: pageCategoryId(extractedConstraint.categoryId),
    }
    const problem = skipReason(next, constraint, categoryExists)
    if (problem) {
      warnings.push(`Skipped "${describeConstraint(constraint)}": ${problem}`)
      continue
    }
    if (constraint.categoryId !== null) useCategory(constraint.categoryId)
    next = addConstraint({ ...next, categories }, constraint)
  }

  return { draft: { ...next, categories }, warnings, addedGoalIds }
}

function skipReason(
  draft: DraftSetup,
  constraint: ConstraintDto,
  categoryExists: (id: string) => boolean,
): string | null {
  if (constraint.type === 'FIXED_BLOCK') return null
  if (constraint.type === 'FREQUENCY_QUOTA') {
    const goal = draft.goals.find((candidate) => candidate.id === constraint.goalId)
    if (!goal) return 'its goal is not in the setup.'
    return hasQuota(draft, constraint.type, goal.id) ? `${goal.name} already has ${QUOTA_NAMES[constraint.type]}.` : null
  }
  const categoryId = constraint.categoryId ?? ''
  if (!categoryExists(categoryId)) return 'its category is not in the setup.'
  const name = draft.categories.find((category) => category.id === categoryId)?.name ?? categoryId
  return hasQuota(draft, constraint.type, categoryId) ? `${name} already has ${QUOTA_NAMES[constraint.type]}.` : null
}
