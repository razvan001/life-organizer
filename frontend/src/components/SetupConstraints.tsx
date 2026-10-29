import { useState } from 'react'
import type { ConstraintDto } from '../api/types'
import {
  addConstraint,
  constraintProblem,
  type DraftSetup,
  removeConstraint,
  updateConstraint,
} from '../setup/editSetup'
import { TimeInput } from './form/FormControls'

const WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

/** A new fixed block before the user fills it in: Monday to Friday. */
function blank(): ConstraintDto {
  return { type: 'FIXED_BLOCK', label: '', start: '', end: '', days: WEEK.slice(0, 5) }
}

interface Props {
  draft: DraftSetup
  onChange: (next: DraftSetup) => void
  disabled: boolean
}

/** The fixed blocks (Work, Sleep, ...): one editable row each, plus a form to add one. */
export function SetupConstraints({ draft, onChange, disabled }: Props) {
  return (
    <div className="card">
      <h4>Fixed blocks</h4>
      <p className="muted">Times that are always taken. Goals are planned around them.</p>
      <ul className="constraint-list">
        {draft.constraints.map(({ key, isNew, value }) => {
          const problem = constraintProblem(value)
          return (
            <li key={key}>
              <div className="constraint-row">
                <FixedBlockFields
                  value={value}
                  name={value.label}
                  disabled={disabled}
                  onChange={(next) => onChange(updateConstraint(draft, key, next))}
                />
                {isNew && <span className="new-tag">new</span>}
                <button
                  type="button"
                  className="btn-danger btn-small"
                  disabled={disabled}
                  onClick={() => onChange(removeConstraint(draft, key))}
                >
                  Remove fixed block {value.label}
                </button>
              </div>
              {problem && <p role="alert">{problem}</p>}
            </li>
          )
        })}
      </ul>
      <AddFixedBlockForm draft={draft} onChange={onChange} disabled={disabled} />
    </div>
  )
}

interface FieldsProps {
  value: ConstraintDto
  /** What the aria labels name: "Start for Work", or "Start for the new fixed block" in the add form. */
  name: string
  disabled: boolean
  onChange: (next: ConstraintDto) => void
}

/** The inputs of one fixed block. The add form uses them too, so a new block is entered like an edit. */
function FixedBlockFields({ value, name, disabled, onChange }: FieldsProps) {
  const set = (changes: Partial<ConstraintDto>) => onChange({ ...value, ...changes })
  return (
    <>
      <input
        aria-label={`Label for ${name}`}
        value={value.label}
        disabled={disabled}
        onChange={(event) => set({ label: event.target.value })}
      />
      <TimeInput
        aria-label={`Start for ${name}`}
        value={value.start ?? ''}
        disabled={disabled}
        onChange={(event) => set({ start: event.target.value })}
      />
      <TimeInput
        aria-label={`End for ${name}`}
        value={value.end ?? ''}
        disabled={disabled}
        onChange={(event) => set({ end: event.target.value })}
      />
      <span className="days">
        {WEEK.map((day, index) => (
          <label key={day} title={DAY_NAMES[index]}>
            <input
              type="checkbox"
              aria-label={`${DAY_NAMES[index]} for ${name}`}
              checked={value.days?.includes(day) ?? false}
              disabled={disabled}
              onChange={(event) => {
                const days = event.target.checked ? [...(value.days ?? []), day] : (value.days ?? []).filter((d) => d !== day)
                // keep the week order the rest of the app shows
                set({ days: WEEK.filter((d) => days.includes(d)) })
              }}
            />
            {DAY_NAMES[index].slice(0, 2)}
          </label>
        ))}
      </span>
    </>
  )
}

function AddFixedBlockForm({ draft, onChange, disabled }: Props) {
  const [value, setValue] = useState<ConstraintDto>(blank)

  function add() {
    onChange(addConstraint(draft, value))
    setValue(blank())
  }

  return (
    <div className="add-form">
      <FixedBlockFields value={value} name="the new fixed block" disabled={disabled} onChange={setValue} />
      <button type="button" className="btn-primary" disabled={disabled || constraintProblem(value) !== null} onClick={add}>
        Add fixed block
      </button>
    </div>
  )
}
