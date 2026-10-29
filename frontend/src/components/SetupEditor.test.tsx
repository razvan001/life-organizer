import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { type DraftSetup, hasProblems, toDraft, updateGoal } from '../setup/editSetup'
import { SAVED } from '../test/fixtures'
import { SetupEditor } from './SetupEditor'

interface Options {
  draft?: DraftSetup
  newGoalIds?: string[]
}

// Keeps the draft in state like SetupPage does, and records every change.
function renderEditor({ draft = toDraft(SAVED), newGoalIds = [] }: Options = {}) {
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
        disabled={false}
      />
    )
  }
  render(<Host />)
  const last = (): DraftSetup => onChange.mock.lastCall![0]
  return { onChange, onGoalAdded, last }
}

describe('SetupEditor: goals', () => {
  it('shows each goal with its hours per week and nothing else to set', () => {
    renderEditor()

    expect(screen.getByLabelText('Hours per week for Gym')).toHaveValue('4')
    expect(screen.getByLabelText('Hours per week for Claude certification')).toHaveValue('5')
    expect(screen.queryByLabelText(/Priority for/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Deadline for/)).not.toBeInTheDocument()
  })

  it('stores hours as minutes and accepts a comma', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.clear(screen.getByLabelText('Hours per week for Gym'))
    await user.type(screen.getByLabelText('Hours per week for Gym'), '1,5')

    expect(last().goals.find((goal) => goal.id === 'health-gym')?.minutesPerWeek).toBe(90)
  })

  it('flags hours off the half-hour grid', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.clear(screen.getByLabelText('Hours per week for Gym'))
    await user.type(screen.getByLabelText('Hours per week for Gym'), '1.3')

    expect(screen.getByRole('alert')).toHaveTextContent('Hours per week must be between 0 and 168, in half-hour steps.')
    expect(hasProblems(last())).toBe(true)
  })

  it("says that a goal with no hours won't be scheduled", () => {
    renderEditor({ draft: updateGoal(toDraft(SAVED), 'health-gym', { minutesPerWeek: 0 }) })

    const gymRow = screen.getByLabelText('Hours per week for Gym').closest('li')!
    expect(within(gymRow).getByText("won't be scheduled")).toBeInTheDocument()
    const certRow = screen.getByLabelText('Hours per week for Claude certification').closest('li')!
    expect(within(certRow).queryByText("won't be scheduled")).not.toBeInTheDocument()
  })

  it('removes a goal and keeps the fixed blocks', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.click(screen.getByRole('button', { name: 'Remove Gym' }))

    expect(last().goals.map((goal) => goal.id)).toEqual(['career-claude-certification'])
    expect(last().constraints.map((constraint) => constraint.value.label)).toEqual(['Work'])
  })

  it('adds a goal with its hours under an existing category', async () => {
    const user = userEvent.setup()
    const { last, onGoalAdded } = renderEditor()

    await user.type(screen.getByLabelText('New goal name'), 'Azure AI')
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'Career')
    await user.type(screen.getByLabelText('Hours per week for the new goal'), '2')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(last().goals.find((goal) => goal.id === 'career-azure-ai')?.minutesPerWeek).toBe(120)
    expect(onGoalAdded).toHaveBeenCalledWith('career-azure-ai')
    expect(screen.getByLabelText('New goal name')).toHaveValue('')
    expect(screen.getByLabelText('Hours per week for the new goal')).toHaveValue('')
  })

  it('adds a goal under a new category', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.type(screen.getByLabelText('New goal name'), 'Chess')
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'New category…')
    await user.type(screen.getByLabelText('New category name'), 'Hobbies')
    await user.type(screen.getByLabelText('Hours per week for the new goal'), '1')
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
    await user.type(screen.getByLabelText('Hours per week for the new goal'), '1')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Health already has the goal "Gym".')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('asks for hours before adding a goal', async () => {
    const user = userEvent.setup()
    const { onChange } = renderEditor()

    await user.type(screen.getByLabelText('New goal name'), 'Chess')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Enter hours per week.')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('marks new goals', () => {
    renderEditor({ newGoalIds: ['health-gym'] })

    const gymRow = screen.getByLabelText('Hours per week for Gym').closest('li')!
    expect(within(gymRow).getByText('new')).toBeInTheDocument()
  })

  it('starts empty on a first visit', () => {
    renderEditor({ draft: toDraft({ categories: [], goals: [], constraints: [] }) })

    expect(screen.getByRole('heading', { name: 'Goals' })).toBeInTheDocument()
    expect(screen.getByText('No goals yet.')).toBeInTheDocument()
    expect(screen.getByLabelText('Category for the new goal')).toHaveValue('__new__')
  })
})

const [WORK] = SAVED.constraints
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']

describe('SetupEditor: fixed blocks', () => {
  it('has no constraint types to choose from', () => {
    renderEditor()

    expect(screen.getByRole('heading', { name: 'Fixed blocks' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Constraint type')).not.toBeInTheDocument()
  })

  it('edits the times of a fixed block and keeps its days', () => {
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

  it('reports a fixed block without days', async () => {
    const user = userEvent.setup()
    renderEditor({ draft: toDraft({ ...SAVED, constraints: [{ ...WORK, days: ['MONDAY'] }] }) })

    await user.click(screen.getByLabelText('Monday for Work'))

    expect(screen.getByRole('alert')).toHaveTextContent('Pick at least one day.')
  })

  it('removes a fixed block', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.click(screen.getByRole('button', { name: 'Remove fixed block Work' }))

    expect(last().constraints).toEqual([])
  })

  it('adds a fixed block, Monday to Friday by default', async () => {
    const user = userEvent.setup()
    const { last } = renderEditor()

    await user.type(screen.getByLabelText('Label for the new fixed block'), 'Commute')
    fireEvent.change(screen.getByLabelText('Start for the new fixed block'), { target: { value: '07:30' } })
    fireEvent.change(screen.getByLabelText('End for the new fixed block'), { target: { value: '08:15' } })
    await user.click(screen.getByRole('button', { name: 'Add fixed block' }))

    expect(last().constraints[1]).toEqual({
      key: 1,
      isNew: true,
      value: { ...WORK, label: 'Commute', start: '07:30', end: '08:15', days: WEEKDAYS },
    })
    const row = screen.getByLabelText('Start for Commute').closest('li')!
    expect(within(row).getByText('new')).toBeInTheDocument()
  })
})
