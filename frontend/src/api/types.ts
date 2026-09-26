// TypeScript mirrors of the backend's JSON shapes (Java records under com.lifeorganizer.*.dto).
// Times are "HH:mm", dates are "yyyy-MM-dd". Optional values arrive as null.

export type Energy = 'LOW' | 'MEDIUM' | 'HIGH'
export type PreferredTime = 'MORNING' | 'EVENING' | 'ANY'

export interface CategoryDto {
  id: string
  name: string
}

export interface GoalDto {
  id: string
  categoryId: string
  name: string
  priority: number
  deadline: string | null
  defaultBlockMinutes: number
  energy: Energy
  preferredTime: PreferredTime
}

export type ConstraintType = 'FIXED_BLOCK' | 'FREQUENCY_QUOTA' | 'DURATION_QUOTA' | 'EVENING_QUOTA'

/** One flat shape for all four constraint types; fields a type does not use are null. */
export interface ConstraintDto {
  type: ConstraintType
  label: string
  start: string | null
  end: string | null
  days: string[] | null
  timesPerWeek: number | null
  sessionMinutes: number | null
  goalId: string | null
  minutesPerWeek: number | null
  categoryId: string | null
  eveningsPerWeek: number | null
}

/** What GET /api/setup returns and PUT /api/setup accepts. */
export interface SetupPayload {
  categories: CategoryDto[]
  goals: GoalDto[]
  constraints: ConstraintDto[]
}

export interface SetupParseRequest {
  goalsText: string
  constraintsText: string
}

export interface SetupExtractionRequest {
  text: string
}

export interface SetupParseResponse extends SetupPayload {
  warnings: string[]
}

export type BlockKind = 'FIXED' | 'ROUTINE' | 'GOAL' | 'FREE' | 'ADHOC'
export type BlockStatus = 'PLANNED' | 'DONE' | 'CANCELLED' | 'POSTPONED'

export interface BlockDto {
  id: string
  date: string
  start: string
  end: string
  title: string
  kind: BlockKind
  goalId: string | null
  status: BlockStatus
  postponeCount: number
  flagged: boolean
  reason: string | null
}

export interface TodayPlanResponse {
  date: string
  blocks: BlockDto[]
  warnings: string[]
}

export interface ApplyEventResponse extends TodayPlanResponse {
  changes: string[]
}

/**
 * The events this UI sends. The backend also accepts SET_ENERGY and ADD_ADHOC, but this
 * slice has no controls for them.
 */
export type PlanEventRequest =
  | { type: 'MARK_DONE'; blockId: string }
  | { type: 'CANCEL_BLOCK'; blockId: string }
  | { type: 'POSTPONE_BLOCK'; blockId: string; reason?: string }

export interface ConfigResponse {
  aiEnabled: boolean
  ragEnabled: boolean
}

/** One event Claude proposes; the exact shape the backend replays on Apply. */
export interface ProposedEventDto {
  type: 'CANCEL_BLOCK' | 'POSTPONE_BLOCK' | 'MARK_DONE' | 'SET_ENERGY' | 'ADD_ADHOC'
  blockId: string | null
  reason: string | null
  date: string | null
  title: string | null
  start: string | null
  end: string | null
  energy: string | null
}

export interface CommandRequest {
  text: string
}

export interface CommandResponse {
  understood: boolean
  rationale: string | null
  message: string | null
  events: ProposedEventDto[]
  preview: TodayPlanResponse | null
  changes: string[]
}

export interface ApplyEventsRequest {
  events: ProposedEventDto[]
}

export interface ApplyEventsResponse extends TodayPlanResponse {
  changes: string[]
}
