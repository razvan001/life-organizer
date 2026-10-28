import { useState } from 'react'
import type { ConstraintDto, ConstraintType } from '../api/types'
import {
  addConstraint,
  constraintProblem,
  type DraftSetup,
  hasQuota,
  removeConstraint,
  updateConstraint,
} from '../setup/editSetup'
import { Select, TimeInput } from './form/FormControls'

const WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

const TYPE_NAMES: Record<ConstraintType, string> = {
  FIXED_BLOCK: 'Fixed block',
  FREQUENCY_QUOTA: 'Times per week',
  DURATION_QUOTA: 'Hours per week',
  EVENING_QUOTA: 'Evenings per week',
}

const EMPTY: ConstraintDto = {
  type: 'FIXED_BLOCK',
  label: '',
  start: null,
  end: null,
  days: null,
  timesPerWeek: null,
  sessionMinutes: null,
  goalId: null,
  minutesPerWeek: null,
  categoryId: null,
  eveningsPerWeek: null,
}

interface Props {
  draft: DraftSetup
  onChange: (next: DraftSetup) => void
  disabled: boolean
}

/** The constraints of a saved setup: one editable row each, plus a form to add one. */
export function SetupConstraints({ draft, onChange, disabled }: Props) {
  // a quota is shown with the name of its goal or category, which cannot change here
  function targetName(constraint: ConstraintDto): string {
    if (constraint.type === 'FREQUENCY_QUOTA') {
      return draft.goals.find((goal) => goal.id === constraint.goalId)?.name ?? constraint.label
    }
    return draft.categories.find((category) => category.id === constraint.categoryId)?.name ?? constraint.label
  }

  return (
    <div className="card">
      <h4>Constraints</h4>
      <ul className="constraint-list">
        {draft.constraints.map(({ key, isNew, value }) => {
          const name = value.type === 'FIXED_BLOCK' ? value.label : targetName(value)
          const problem = constraintProblem(value)
          return (
            <li key={key}>
              <div className="constraint-row">
                <span className="constraint-type">{TYPE_NAMES[value.type]}</span>
                {value.type !== 'FIXED_BLOCK' && <span className="constraint-target">{name}</span>}
                <ConstraintFields
                  value={value}
                  name={name}
                  disabled={disabled}
                  onChange={(next) => onChange(updateConstraint(draft, key, next))}
                />
                {isNew && <span className="new-tag">new</span>}
                <button
                  className="btn-danger btn-small"
                  type="button"
                  disabled={disabled}
                  onClick={() => onChange(removeConstraint(draft, key))}
                >
                  Remove constraint {name}
                </button>
              </div>
              {problem && <p role="alert">{problem}</p>}
            </li>
          )
        })}
      </ul>
      <AddConstraintForm draft={draft} onChange={onChange} disabled={disabled} />
    </div>
  )
}

interface FieldsProps {
  value: ConstraintDto
  /** What the aria labels name: "Start for Work", or "Start for the new constraint" in the add form. */
  name: string
  disabled: boolean
  onChange: (next: ConstraintDto) => void
}

/** The inputs of one constraint type. The add form uses them too, so a new constraint is entered like an edit. */
function ConstraintFields({ value, name, disabled, onChange }: FieldsProps) {
  const set = (changes: Partial<ConstraintDto>) => onChange({ ...value, ...changes })
  switch (value.type) {
    case 'FIXED_BLOCK':
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
    case 'FREQUENCY_QUOTA':
      return (
        <>
          <NumberField
            label={`Times per week for ${name}`}
            value={value.timesPerWeek}
            disabled={disabled}
            onChange={(timesPerWeek) => set({ timesPerWeek })}
          />
          ×
          <NumberField
            label={`Minutes per session for ${name}`}
            value={value.sessionMinutes}
            disabled={disabled}
            onChange={(sessionMinutes) => set({ sessionMinutes })}
          />
          min
        </>
      )
    case 'DURATION_QUOTA':
      return (
        <>
          <NumberField
            label={`Hours per week for ${name}`}
            value={value.minutesPerWeek === null ? null : value.minutesPerWeek / 60}
            decimals
            disabled={disabled}
            onChange={(hours) => set({ minutesPerWeek: Math.round(hours * 60) })}
          />
          h
        </>
      )
    case 'EVENING_QUOTA':
      return (
        <NumberField
          label={`Evenings per week for ${name}`}
          value={value.eveningsPerWeek}
          disabled={disabled}
          onChange={(eveningsPerWeek) => set({ eveningsPerWeek })}
        />
      )
  }
}

function sameNumber(a: number | null, b: number | null): boolean {
  return a === b || (Number.isNaN(a) && Number.isNaN(b))
}

/**
 * A text input for a number. It keeps what the user typed ("1." while typing "1.5"), which a number input
 * bound to the parsed value would lose. An empty or unreadable entry is reported as NaN, so the checks flag it.
 */
function NumberField(props: {
  label: string
  value: number | null
  decimals?: boolean
  disabled: boolean
  onChange: (value: number) => void
}) {
  const { label, value, decimals = false, disabled, onChange } = props
  const [text, setText] = useState(value === null || Number.isNaN(value) ? '' : String(value))
  const [shown, setShown] = useState(value)
  // the value changed from outside (for example Discard changes): show it
  if (!sameNumber(value, shown)) {
    setShown(value)
    setText(value === null || Number.isNaN(value) ? '' : String(value))
  }
  return (
    <input
      className="number"
      inputMode={decimals ? 'decimal' : 'numeric'}
      aria-label={label}
      value={text}
      disabled={disabled}
      onChange={(event) => {
        const typed = event.target.value
        const parsed = typed.trim() === '' ? Number.NaN : Number(typed.replace(',', '.'))
        setText(typed)
        // what the draft gets back: hours round to whole minutes, so compare in the same unit
        setShown(decimals && !Number.isNaN(parsed) ? Math.round(parsed * 60) / 60 : parsed)
        onChange(parsed)
      }}
    />
  )
}

/** A new constraint of each type, before the user fills it in. */
function blank(type: ConstraintType): ConstraintDto {
  switch (type) {
    case 'FIXED_BLOCK':
      return { ...EMPTY, type, start: '', end: '', days: WEEK.slice(0, 5) }
    case 'FREQUENCY_QUOTA':
      return { ...EMPTY, type, timesPerWeek: Number.NaN, sessionMinutes: 60 }
    case 'DURATION_QUOTA':
      return { ...EMPTY, type, minutesPerWeek: Number.NaN }
    case 'EVENING_QUOTA':
      return { ...EMPTY, type, eveningsPerWeek: Number.NaN }
  }
}

function AddConstraintForm({ draft, onChange, disabled }: Props) {
  const [value, setValue] = useState<ConstraintDto>(() => blank('FIXED_BLOCK'))
  const [targetId, setTargetId] = useState('')

  // only targets without a quota of this type: a second one would plan the target twice as often
  const targets =
    value.type === 'FREQUENCY_QUOTA'
      ? draft.goals.filter((goal) => !hasQuota(draft, value.type, goal.id))
      : draft.categories.filter((category) => !hasQuota(draft, value.type, category.id))
  const target = targets.find((candidate) => candidate.id === targetId) ?? targets[0]

  const isQuota = value.type !== 'FIXED_BLOCK'
  const complete: ConstraintDto = !isQuota
    ? value
    : {
        ...value,
        label: target?.name ?? '',
        goalId: value.type === 'FREQUENCY_QUOTA' ? (target?.id ?? null) : null,
        categoryId: value.type === 'FREQUENCY_QUOTA' ? null : (target?.id ?? null),
      }

  function changeType(type: ConstraintType) {
    setValue(blank(type))
    setTargetId('')
  }

  function add() {
    onChange(addConstraint(draft, complete))
    setValue(blank(value.type))
  }

  const goalTarget = value.type === 'FREQUENCY_QUOTA'
  return (
    <div className="add-form">
      <Select
        aria-label="Constraint type"
        value={value.type}
        disabled={disabled}
        onChange={(event) => changeType(event.target.value as ConstraintType)}
      >
        {(Object.keys(TYPE_NAMES) as ConstraintType[]).map((type) => (
          <option key={type} value={type}>
            {TYPE_NAMES[type]}
          </option>
        ))}
      </Select>
      {isQuota && targets.length === 0 ? (
        <span className="muted">{goalTarget ? 'Every goal already has one.' : 'Every category already has one.'}</span>
      ) : (
        <>
          {isQuota && (
            <Select
              aria-label={goalTarget ? 'Goal for the new constraint' : 'Category for the new constraint'}
              value={target?.id ?? ''}
              disabled={disabled}
              onChange={(event) => setTargetId(event.target.value)}
            >
              {targets.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name}
                </option>
              ))}
            </Select>
          )}
          <ConstraintFields value={value} name="the new constraint" disabled={disabled} onChange={setValue} />
          <button
            className="btn-primary"
            type="button"
            disabled={disabled || constraintProblem(complete) !== null}
            onClick={add}
          >
            Add constraint
          </button>
        </>
      )}
    </div>
  )
}
