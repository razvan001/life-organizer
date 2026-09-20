import type { ConstraintDto } from '../api/types'

const WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const SHORT: Record<string, string> = {
  MONDAY: 'Mon',
  TUESDAY: 'Tue',
  WEDNESDAY: 'Wed',
  THURSDAY: 'Thu',
  FRIDAY: 'Fri',
  SATURDAY: 'Sat',
  SUNDAY: 'Sun',
}

function describeDays(days: string[] | null): string {
  const selected = WEEK.filter((day) => days?.includes(day))
  if (selected.length === 7) return 'every day'
  if (selected.join() === WEEK.slice(0, 5).join()) return 'Mon–Fri'
  return selected.map((day) => SHORT[day]).join(', ')
}

function describeMinutes(minutes: number): string {
  const hours = minutes / 60
  return Number.isInteger(hours) ? `${hours}h` : `${minutes} min`
}

/** One human-readable line per constraint, for the Setup preview. */
export function describeConstraint(constraint: ConstraintDto): string {
  switch (constraint.type) {
    case 'FIXED_BLOCK': {
      const time = `${constraint.label}: ${constraint.start}–${constraint.end}`
      const days = describeDays(constraint.days)
      // no days given: nothing to append (and no dangling comma)
      return days ? `${time}, ${days}` : time
    }
    case 'FREQUENCY_QUOTA':
      return `${constraint.label}: ${constraint.timesPerWeek}× per week, ${constraint.sessionMinutes} min each`
    case 'DURATION_QUOTA':
      return `${constraint.label}: ${describeMinutes(constraint.minutesPerWeek ?? 0)} per week`
    case 'EVENING_QUOTA':
      return `${constraint.label}: ${constraint.eveningsPerWeek} ${constraint.eveningsPerWeek === 1 ? 'evening' : 'evenings'} per week`
  }
}
