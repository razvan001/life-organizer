import type { GoalDto, SetupParseResponse } from '../api/types'
import { describeConstraint } from '../setup/describeConstraint'
import { DateInput, Select } from './form/FormControls'

interface Props {
  value: SetupParseResponse
  onChange: (next: SetupParseResponse) => void
}

/** The parsed setup. Priority and deadline of each goal can be edited before saving. */
export function SetupPreview({ value, onChange }: Props) {
  function updateGoal(id: string, changes: Partial<GoalDto>) {
    onChange({
      ...value,
      goals: value.goals.map((goal) => (goal.id === id ? { ...goal, ...changes } : goal)),
    })
  }

  return (
    <div className="preview">
      {value.warnings.length > 0 && (
        <div className="warnings">
          <h4>Not understood (these lines are ignored)</h4>
          <ul>
            {value.warnings.map((warning, index) => (
              // the index keeps the key unique when the same warning text appears twice
              <li key={`${index}-${warning}`}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      <h4>Goals</h4>
      <p className="muted">Priority: 1 = least important, 5 = most important. A deadline gives a goal more time.</p>
      {value.categories.map((category) => (
        <div key={category.id}>
          <h5>{category.name}</h5>
          <ul className="goal-list">
            {value.goals
              .filter((goal) => goal.categoryId === category.id)
              .map((goal) => (
                <li key={goal.id}>
                  <span className="goal-name">{goal.name}</span>
                  <Select
                    aria-label={`Priority for ${goal.name}`}
                    value={goal.priority}
                    onChange={(event) => updateGoal(goal.id, { priority: Number(event.target.value) })}
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
                    // an empty date input gives "", but the backend wants null for "no deadline"
                    onChange={(event) => updateGoal(goal.id, { deadline: event.target.value || null })}
                  />
                </li>
              ))}
          </ul>
        </div>
      ))}

      <h4>Constraints</h4>
      <ul>
        {value.constraints.map((constraint, index) => (
          <li key={index}>{describeConstraint(constraint)}</li>
        ))}
      </ul>
    </div>
  )
}
