import { useState } from 'react'
import { describeConstraint } from '../setup/describeConstraint'
import {
  addGoal,
  type CategoryChoice,
  constraintsRemovedWith,
  type DraftSetup,
  removeGoal,
  updateGoal,
} from '../setup/editSetup'
import { SetupConstraints } from './SetupConstraints'
import { DateInput, Select } from './form/FormControls'
import './SetupEditor.css'

interface Props {
  draft: DraftSetup
  onChange: (next: DraftSetup) => void
  /** Called with the id of a goal added by hand, so the page can mark it as new. */
  onGoalAdded: (goalId: string) => void
  /** Goals added since the last Save (by hand or from the brain dump). */
  newGoalIds: string[]
  /** Things the brain dump could not add. */
  warnings: string[]
  /** True while saving: nothing can be changed. */
  disabled: boolean
}

/** A saved setup, editable in place. Nothing is saved here; the page saves the draft. */
export function SetupEditor({ draft, onChange, onGoalAdded, newGoalIds, warnings, disabled }: Props) {
  return (
    <div className="setup-editor">
      {warnings.length > 0 && (
        <div className="warnings">
          <h4>Not added</h4>
          <ul>
            {warnings.map((warning, index) => (
              // the index keeps the key unique when the same warning text appears twice
              <li key={`${index}-${warning}`}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="card">
        <h4>Goals</h4>
        <p className="muted">Priority: 1 = least important, 5 = most important. A deadline gives a goal more time.</p>
        {draft.categories.map((category) => {
          const goals = draft.goals.filter((goal) => goal.categoryId === category.id)
          return (
            <div key={category.id}>
              <h5>{category.name}</h5>
              {goals.length === 0 && <p className="muted">no goals</p>}
              <ul className="goal-list">
                {goals.map((goal) => {
                  const goesWith = constraintsRemovedWith(draft, goal.id)
                  return (
                    <li key={goal.id}>
                      <span className="goal-name">
                        {goal.name}
                        {newGoalIds.includes(goal.id) && <span className="new-tag">new</span>}
                        {goesWith.length > 0 && (
                          <span className="muted removes-hint">
                            also removes: {goesWith.map(describeConstraint).join('; ')}
                          </span>
                        )}
                      </span>
                      <Select
                        aria-label={`Priority for ${goal.name}`}
                        value={goal.priority}
                        disabled={disabled}
                        onChange={(event) =>
                          onChange(updateGoal(draft, goal.id, { priority: Number(event.target.value) }))
                        }
                      >
                        {[1, 2, 3, 4, 5].map((priority) => (
                          <option key={priority} value={priority}>
                            {priority}
                          </option>
                        ))}
                      </Select>
                      <DateInput
                        aria-label={`Deadline for ${goal.name}`}
                        value={goal.deadline ?? ''}
                        disabled={disabled}
                        // an empty date input gives "", but the backend wants null for "no deadline"
                        onChange={(event) =>
                          onChange(updateGoal(draft, goal.id, { deadline: event.target.value || null }))
                        }
                      />
                      <button
                        className="btn-danger btn-small"
                        type="button"
                        disabled={disabled}
                        onClick={() => onChange(removeGoal(draft, goal.id))}
                      >
                        Remove {goal.name}
                      </button>
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

function AddGoalForm({ draft, onChange, onGoalAdded, disabled }: Omit<Props, 'newGoalIds' | 'warnings'>) {
  const [name, setName] = useState('')
  const [chosenCategoryId, setCategoryId] = useState(draft.categories[0]?.id ?? NEW_CATEGORY)
  // the chosen category can disappear (Discard changes): fall back to the first one, as the select shows
  const categoryId =
    chosenCategoryId === NEW_CATEGORY || draft.categories.some((category) => category.id === chosenCategoryId)
      ? chosenCategoryId
      : (draft.categories[0]?.id ?? NEW_CATEGORY)
  const [newCategoryName, setNewCategoryName] = useState('')
  const [error, setError] = useState<string | null>(null)

  function add() {
    const choice: CategoryChoice =
      categoryId === NEW_CATEGORY ? { newName: newCategoryName } : { existingId: categoryId }
    const result = addGoal(draft, name, choice)
    if (!result.ok) {
      setError(result.error)
      return
    }
    setError(null)
    setName('')
    setNewCategoryName('')
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
      <button className="btn-primary" type="button" disabled={disabled} onClick={add}>
        Add goal
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
