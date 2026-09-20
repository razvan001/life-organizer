import type { BlockDto, ConstraintDto, GoalDto, SetupParseResponse, TodayPlanResponse } from '../api/types'

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
  timesPerWeek: null,
  sessionMinutes: null,
  goalId: null,
  minutesPerWeek: null,
  categoryId: null,
  eveningsPerWeek: null,
}

const GYM: ConstraintDto = {
  ...WORK,
  type: 'FREQUENCY_QUOTA',
  label: 'Gym',
  start: null,
  end: null,
  days: null,
  timesPerWeek: 4,
  sessionMinutes: 60,
  goalId: 'health-gym',
}

const CERTIFICATION: GoalDto = {
  id: 'career-claude-certification',
  categoryId: 'career',
  name: 'Claude certification',
  priority: 3,
  deadline: '2026-10-01',
  defaultBlockMinutes: 60,
  energy: 'MEDIUM',
  preferredTime: 'ANY',
}

const GYM_GOAL: GoalDto = {
  ...CERTIFICATION,
  id: 'health-gym',
  categoryId: 'health',
  name: 'Gym',
  deadline: null,
}

/** What POST /api/setup/parse answers for a small setup, with one warning. */
export const PARSED: SetupParseResponse = {
  categories: [
    { id: 'career', name: 'Career' },
    { id: 'health', name: 'Health' },
  ],
  goals: [CERTIFICATION, GYM_GOAL],
  constraints: [WORK, GYM],
  warnings: ['No goal found for frequency constraint: Padel'],
}
