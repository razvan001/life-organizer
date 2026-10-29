import { useState } from 'react'

interface Props {
  label: string
  /** The value in minutes; NaN while the field is empty or unreadable. */
  minutes: number
  disabled: boolean
  onChange: (minutes: number) => void
}

function toText(minutes: number): string {
  return Number.isNaN(minutes) ? '' : String(minutes / 60)
}

/**
 * Hours typed as text ("1.5" or "1,5") and reported as whole minutes. It keeps what the user typed ("1." while
 * typing "1.5"), which a number input bound to the parsed value would lose. An empty or unreadable entry is reported
 * as NaN, so the checks flag it.
 */
export function HoursField({ label, minutes, disabled, onChange }: Props) {
  const [text, setText] = useState(toText(minutes))
  const [shown, setShown] = useState(minutes)
  // the value changed from outside (for example Discard changes): show it
  if (!Object.is(minutes, shown)) {
    setShown(minutes)
    setText(toText(minutes))
  }
  return (
    <input
      className="number"
      inputMode="decimal"
      aria-label={label}
      value={text}
      disabled={disabled}
      onChange={(event) => {
        const typed = event.target.value
        const hours = typed.trim() === '' ? Number.NaN : Number(typed.replace(',', '.'))
        const next = Number.isNaN(hours) ? Number.NaN : Math.round(hours * 60)
        setText(typed)
        setShown(next)
        onChange(next)
      }}
    />
  )
}
