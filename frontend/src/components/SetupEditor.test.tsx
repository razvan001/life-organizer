import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
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
