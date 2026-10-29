import type { BlockDto, ConstraintDto, GoalDto, SetupPayload, TodayPlanResponse } from '../api/types'

export function makeBlock(overrides: Partial<BlockDto> = {}): BlockDto {
  return {
    id: 'career-claude-certification-2026-09-14',
    date: '2026-09-14',
    start: '18:00',
    end: '19:00',
    title: 'Claude certification',
    kind: 'GOAL',
    goalId: 'career-claude-certification',
    status: 'PLANNED',
    postponeCount: 0,
    flagged: false,
    reason: null,
    ...overrides,
  }
}

/** A short Monday: gym, work (fixed), the certification session. */
export const TODAY: TodayPlanResponse = {
  date: '2026-09-14',
  warnings: [],
  blocks: [
    makeBlock({ id: 'health-gym-2026-09-14', start: '07:00', end: '08:00', title: 'Gym', goalId: 'health-gym' }),
    makeBlock({
      id: 'fixed-work-2026-09-14',
      start: '08:30',
      end: '17:00',
      title: 'Work',
      kind: 'FIXED',
      goalId: null,
    }),
    makeBlock(),
  ],
}

const WORK: ConstraintDto = {
  type: 'FIXED_BLOCK',
  label: 'Work',
  start: '08:30',
  end: '17:00',
  days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'],
}

const CERTIFICATION: GoalDto = {
  id: 'career-claude-certification',
  categoryId: 'career',
  name: 'Claude certification',
  minutesPerWeek: 300,
  defaultBlockMinutes: 60,
  energy: 'MEDIUM',
  preferredTime: 'ANY',
}

const GYM_GOAL: GoalDto = {
  ...CERTIFICATION,
  id: 'health-gym',
  categoryId: 'health',
  name: 'Gym',
  minutesPerWeek: 240,
}

/** What GET /api/setup answers for a small saved setup. */
export const SAVED: SetupPayload = {
  categories: [
    { id: 'career', name: 'Career' },
    { id: 'health', name: 'Health' },
  ],
  goals: [CERTIFICATION, GYM_GOAL],
  constraints: [WORK],
}
