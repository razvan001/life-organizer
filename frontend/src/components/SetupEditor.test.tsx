import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ConstraintDto } from '../api/types'
import { type DraftSetup, removeGoal, toDraft } from '../setup/editSetup'
import { PARSED } from '../test/fixtures'
import { SetupEditor } from './SetupEditor'

const { warnings: _warnings, ...SAVED } = PARSED

interface Options {
  draft?: DraftSetup
  newGoalIds?: string[]
  warnings?: string[]
}

// Keeps the draft in state like SetupPage does, and records every change.
function renderEditor({ draft = toDraft(SAVED), newGoalIds = [], warnings = [] }: Options = {}) {
  const onChange = vi.fn()
  const onGoalAdded = vi.fn()
  function Host() {
    const [current, setCurrent] = useState(draft)
    return (
      <SetupEditor
        draft={current}
        onChange={(next) => {
          onChange(next)
          setCurrent(next)
        }}
        onGoalAdded={onGoalAdded}
        newGoalIds={newGoalIds}
        warnings={warnings}
        disabled={false}
      />
    )
  }
  render(<Host />)
  const last = (): DraftSetup => onChange.mock.lastCall![0]
  return { onChange, onGoalAdded, last }
}

describe('SetupEditor: goals', () => {
  it('changes a priority', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.selectOptions(screen.getByLabelText('Priority for Gym'), '5')

    expect(last().goals.find((goal) => goal.id === 'health-gym')?.priority).toBe(5)
  })

  it('removes a goal and says which quota goes with it', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()
    expect(screen.getByText('also removes: Gym: 4× per week, 60 min each')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Remove Gym' }))

    expect(last().goals.map((goal) => goal.id)).toEqual(['career-claude-certification'])
    expect(last().constraints.map((constraint) => constraint.value.label)).toEqual(['Work'])
  })

  it('adds a goal under an existing category', async () => {
    const user = userEvent.setup()
    const { last, onGoalAdded } = renderEditor()

    await user.type(screen.getByLabelText('New goal name'), 'Azure AI')
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'Career')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(last().goals.map((goal) => goal.id)).toContain('career-azure-ai')
    expect(onGoalAdded).toHaveBeenCalledWith('career-azure-ai')
    expect(screen.getByLabelText('New goal name')).toHaveValue('')
  })

  it('adds a goal under a new category', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.type(screen.getByLabelText('New goal name'), 'Chess')
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'New category…')
    await user.type(screen.getByLabelText('New category name'), 'Hobbies')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(last().categories).toContainEqual({ id: 'hobbies', name: 'Hobbies' })
    expect(last().goals.map((goal) => goal.id)).toContain('hobbies-chess')
    expect(screen.getByRole('heading', { name: 'Hobbies' })).toBeInTheDocument()
  })

  it('shows why a goal cannot be added', async () => {
    const user = userEvent.setup()
    const { onChange } = renderEditor()

    await user.type(screen.getByLabelText('New goal name'), 'Gym')
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'Health')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Health already has the goal "Gym".')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('marks new goals and shows the warnings', () => {
    renderEditor({ newGoalIds: ['health-gym'], warnings: ['Skipped goal "Gym": it already exists.'] })

    const gymRow = screen.getByText('Gym', { selector: '.goal-name' }).closest('li')!
    expect(within(gymRow).getByText('new')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Not added' })).toBeInTheDocument()
    expect(screen.getByText('Skipped goal "Gym": it already exists.')).toBeInTheDocument()
  })

  it('shows a category without goals', () => {
    renderEditor({ draft: removeGoal(toDraft(SAVED), 'career-claude-certification') })

    const career = screen.getByRole('heading', { name: 'Career' }).parentElement!
    expect(within(career).getByText('no goals')).toBeInTheDocument()
  })
})

const [WORK, GYM_QUOTA] = SAVED.constraints
const HOURS: ConstraintDto = {
  ...GYM_QUOTA,
  type: 'DURATION_QUOTA',
  label: 'Career',
  timesPerWeek: null,
  sessionMinutes: null,
  goalId: null,
  minutesPerWeek: 300,
  categoryId: 'career',
}
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']

describe('SetupEditor: constraints', () => {
  it('edits the times of a fixed block and keeps its days', async () => {
    const { last } = renderEditor()

    fireEvent.change(screen.getByLabelText('Start for Work'), { target: { value: '09:00' } })

    expect(last().constraints[0].value).toEqual({ ...WORK, start: '09:00' })
  })

  it('edits the days of a fixed block', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.click(screen.getByLabelText('Friday for Work'))

    expect(last().constraints[0].value.days).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY'])
  })

  it('edits times per week and keeps the session minutes', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.clear(screen.getByLabelText('Times per week for Gym'))
    await user.type(screen.getByLabelText('Times per week for Gym'), '3')

    expect(last().constraints[1].value).toEqual({ ...GYM_QUOTA, timesPerWeek: 3 })
  })

  it('shows hours per week and stores them as minutes', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor({ draft: toDraft({ ...SAVED, constraints: [WORK, GYM_QUOTA, HOURS] }) })
    expect(screen.getByLabelText('Hours per week for Career')).toHaveValue('5')

    await user.clear(screen.getByLabelText('Hours per week for Career'))
    await user.type(screen.getByLabelText('Hours per week for Career'), '1.5')

    expect(last().constraints[2].value.minutesPerWeek).toBe(90)
  })

  it('reports an emptied field', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.clear(screen.getByLabelText('Times per week for Gym'))

    expect(screen.getByRole('alert')).toHaveTextContent('Times per week must be a whole number of at least 1.')
    expect(Number.isNaN(last().constraints[1].value.timesPerWeek)).toBe(true)
  })

  it('removes a constraint', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.click(screen.getByRole('button', { name: 'Remove constraint Work' }))

    expect(last().constraints.map((constraint) => constraint.value)).toEqual([GYM_QUOTA])
  })

  it('adds a fixed block, Monday to Friday by default', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.selectOptions(screen.getByLabelText('Constraint type'), 'Fixed block')
    await user.type(screen.getByLabelText('Label for the new constraint'), 'Commute')
    fireEvent.change(screen.getByLabelText('Start for the new constraint'), { target: { value: '07:30' } })
    fireEvent.change(screen.getByLabelText('End for the new constraint'), { target: { value: '08:15' } })
    await user.click(screen.getByRole('button', { name: 'Add constraint' }))

    expect(last().constraints[2]).toEqual({
      key: 2,
      isNew: true,
      value: { ...WORK, label: 'Commute', start: '07:30', end: '08:15', days: WEEKDAYS },
    })
    const row = screen.getByLabelText('Start for Commute').closest('li')!
    expect(within(row).getByText('new')).toBeInTheDocument()
  })

  it('adds a times-per-week quota named after its goal', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.selectOptions(screen.getByLabelText('Constraint type'), 'Times per week')
    await user.type(screen.getByLabelText('Times per week for the new constraint'), '2')
    await user.click(screen.getByRole('button', { name: 'Add constraint' }))

    expect(last().constraints[2].value).toMatchObject({
      type: 'FREQUENCY_QUOTA',
      label: 'Claude certification',
      goalId: 'career-claude-certification',
      timesPerWeek: 2,
      sessionMinutes: 60,
    })
  })

  it('offers only goals without a times-per-week quota', async () => {
    const user = userEvent.setup()
    renderEditor()

    await user.selectOptions(screen.getByLabelText('Constraint type'), 'Times per week')

    const goals = within(screen.getByLabelText('Goal for the new constraint')).getAllByRole('option')
    expect(goals.map((option) => option.textContent)).toEqual(['Claude certification'])
  })

  it('says when every goal already has a times-per-week quota', async () => {
    const user = userEvent.setup()
    const both = { ...GYM_QUOTA, label: 'Claude certification', goalId: 'career-claude-certification' }
    renderEditor({ draft: toDraft({ ...SAVED, constraints: [WORK, GYM_QUOTA, both] }) })

    await user.selectOptions(screen.getByLabelText('Constraint type'), 'Times per week')

    expect(screen.getByText('Every goal already has one.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add constraint' })).not.toBeInTheDocument()
  })
})
