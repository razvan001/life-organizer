import { useState } from 'react'
import {
  addGoal,
  type CategoryChoice,
  type DraftSetup,
  hoursProblem,
  removeGoal,
  updateGoal,
} from '../setup/editSetup'
import { Select } from './form/FormControls'
import { HoursField } from './form/HoursField'
import { SetupConstraints } from './SetupConstraints'
import './SetupEditor.css'

interface Props {
  draft: DraftSetup
  onChange: (next: DraftSetup) => void
  /** Called with the id of a goal added by hand, so the page can mark it as new. */
  onGoalAdded: (goalId: string) => void
  /** Goals added since the last Save. */
  newGoalIds: string[]
  /** True while saving: nothing can be changed. */
  disabled: boolean
}

/** The setup, editable in place: goals with their hours per week, and fixed blocks. The page saves the draft. */
export function SetupEditor({ draft, onChange, onGoalAdded, newGoalIds, disabled }: Props) {
  return (
    <div className="setup-editor">
      <div className="card">
        <h4>Goals</h4>
        <p className="muted">Each goal gets its hours every week, in sessions of up to an hour on different days.</p>
        {draft.goals.length === 0 && <p className="muted">No goals yet.</p>}
        {draft.categories.map((category) => {
          const goals = draft.goals.filter((goal) => goal.categoryId === category.id)
          return (
            <div key={category.id}>
              <h5>{category.name}</h5>
              {goals.length === 0 && <p className="muted">no goals</p>}
              <ul className="goal-list">
                {goals.map((goal) => {
                  const problem = hoursProblem(goal.minutesPerWeek)
                  return (
                    <li key={goal.id}>
                      <span className="goal-name">
                        {goal.name}
                        {newGoalIds.includes(goal.id) && <span className="new-tag">new</span>}
                        {goal.minutesPerWeek === 0 && <span className="muted unscheduled-hint">won't be scheduled</span>}
                      </span>
                      <HoursField
                        label={`Hours per week for ${goal.name}`}
                        minutes={goal.minutesPerWeek}
                        disabled={disabled}
                        onChange={(minutesPerWeek) => onChange(updateGoal(draft, goal.id, { minutesPerWeek }))}
                      />
                      <span className="unit">h / week</span>
                      <button
                        type="button"
                        className="btn-danger btn-small"
                        disabled={disabled}
                        onClick={() => onChange(removeGoal(draft, goal.id))}
                      >
                        Remove {goal.name}
                      </button>
                      {problem && (
                        <p role="alert" className="row-problem">
                          {problem}
                        </p>
                      )}
                    </li>
                  )
                })}
              </ul>
            </div>
          )
        })}
        <AddGoalForm draft={draft} onChange={onChange} onGoalAdded={onGoalAdded} disabled={disabled} />
      </div>

      <SetupConstraints draft={draft} onChange={onChange} disabled={disabled} />
    </div>
  )
}

/** The value of the category select that asks for a new category. */
const NEW_CATEGORY = '__new__'

function AddGoalForm({ draft, onChange, onGoalAdded, disabled }: Omit<Props, 'newGoalIds'>) {
  const [name, setName] = useState('')
  const [chosenCategoryId, setCategoryId] = useState(draft.categories[0]?.id ?? NEW_CATEGORY)
  // the chosen category can disappear (Discard changes): fall back to the first one, as the select shows
  const categoryId =
    chosenCategoryId === NEW_CATEGORY || draft.categories.some((category) => category.id === chosenCategoryId)
      ? chosenCategoryId
      : (draft.categories[0]?.id ?? NEW_CATEGORY)
  const [newCategoryName, setNewCategoryName] = useState('')
  const [minutes, setMinutes] = useState(Number.NaN)
  // a new key empties the hours field after a goal is added
  const [hoursKey, setHoursKey] = useState(0)
  const [error, setError] = useState<string | null>(null)

  function add() {
    const choice: CategoryChoice =
      categoryId === NEW_CATEGORY ? { newName: newCategoryName } : { existingId: categoryId }
    const result = addGoal(draft, name, choice, minutes)
    if (!result.ok) {
      setError(result.error)
      return
    }
    setError(null)
    setName('')
    setNewCategoryName('')
    setMinutes(Number.NaN)
    setHoursKey((key) => key + 1)
    onChange(result.draft)
    onGoalAdded(result.draft.goals[result.draft.goals.length - 1].id)
  }

  return (
    <div className="add-form">
      <input
        aria-label="New goal name"
        placeholder="New goal"
        value={name}
        disabled={disabled}
        onChange={(event) => setName(event.target.value)}
      />
      <Select
        aria-label="Category for the new goal"
        value={categoryId}
        disabled={disabled}
        onChange={(event) => setCategoryId(event.target.value)}
      >
        {draft.categories.map((category) => (
          <option key={category.id} value={category.id}>
            {category.name}
          </option>
        ))}
        <option value={NEW_CATEGORY}>New category…</option>
      </Select>
      {categoryId === NEW_CATEGORY && (
        <input
          aria-label="New category name"
          placeholder="Category"
          value={newCategoryName}
          disabled={disabled}
          onChange={(event) => setNewCategoryName(event.target.value)}
        />
      )}
      <HoursField
        key={hoursKey}
        label="Hours per week for the new goal"
        minutes={minutes}
        disabled={disabled}
        onChange={setMinutes}
      />
      <span className="unit">h / week</span>
      <button type="button" className="btn-primary" disabled={disabled} onClick={add}>
        Add goal
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
