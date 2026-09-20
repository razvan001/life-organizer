import { describe, expect, it } from 'vitest'
import type { ConstraintDto } from '../api/types'
import { describeConstraint } from './describeConstraint'

const BASE: ConstraintDto = {
  type: 'FIXED_BLOCK',
  label: 'Work',
  start: '08:30',
  end: '17:00',
  days: null,
  timesPerWeek: null,
  sessionMinutes: null,
  goalId: null,
  minutesPerWeek: null,
  categoryId: null,
  eveningsPerWeek: null,
}

describe('describeConstraint', () => {
  it('names weekdays as a range', () => {
    const days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']
    expect(describeConstraint({ ...BASE, days })).toBe('Work: 08:30–17:00, Mon–Fri')
  })

  it('says every day for all seven days', () => {
    const days = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']
    expect(describeConstraint({ ...BASE, label: 'Sleep', start: '23:30', end: '07:00', days })).toBe(
      'Sleep: 23:30–07:00, every day',
    )
  })

  it('lists other day sets in week order', () => {
    expect(describeConstraint({ ...BASE, days: ['SATURDAY', 'MONDAY'] })).toBe('Work: 08:30–17:00, Mon, Sat')
  })

  it('describes a frequency quota', () => {
    const gym = { ...BASE, type: 'FREQUENCY_QUOTA' as const, label: 'Gym', timesPerWeek: 4, sessionMinutes: 60 }
    expect(describeConstraint(gym)).toBe('Gym: 4× per week, 60 min each')
  })

  it('describes a duration quota in hours when it is a whole number of hours', () => {
    const learning = { ...BASE, type: 'DURATION_QUOTA' as const, label: 'Learning', minutesPerWeek: 300 }
    expect(describeConstraint(learning)).toBe('Learning: 5h per week')
    expect(describeConstraint({ ...learning, minutesPerWeek: 90 })).toBe('Learning: 90 min per week')
  })

  it('describes an evening quota', () => {
    const social = { ...BASE, type: 'EVENING_QUOTA' as const, label: 'Social', eveningsPerWeek: 2 }
    expect(describeConstraint(social)).toBe('Social: 2 evenings per week')
  })
})
