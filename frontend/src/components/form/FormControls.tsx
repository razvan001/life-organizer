import type { ComponentProps } from 'react'
import './FormControls.css'

/*
 * Styled native controls. Each one renders the browser's own element with every prop passed through, plus a
 * decorative icon: the closed control matches the app, the popup (option list, calendar, clock) stays native, so
 * keyboard use, screen readers and mobile pickers work as usual. See frontend/UI.md.
 */

/** A native <select> with the app's look and a chevron. */
export function Select(props: ComponentProps<'select'>) {
  return (
    <span className="select">
      <select {...props} />
      <svg className="select-chevron" aria-hidden="true" viewBox="0 0 24 24" width="16" height="16">
        <path d="M6 9l6 6 6-6" />
      </svg>
    </span>
  )
}

type InputProps = Omit<ComponentProps<'input'>, 'type'>

/** A native date input (yyyy-MM-dd value) with a calendar icon that opens the browser's picker. */
export function DateInput(props: InputProps) {
  return (
    <span className="date-field">
      <input type="date" {...props} />
      <svg className="date-field-icon" aria-hidden="true" viewBox="0 0 24 24" width="16" height="16">
        <rect x="3.5" y="5" width="17" height="15.5" rx="2" />
        <path d="M8 3v4M16 3v4M3.5 10h17" />
      </svg>
    </span>
  )
}

/** A native time input (HH:mm value) with a clock icon that opens the browser's picker. */
export function TimeInput(props: InputProps) {
  return (
    <span className="date-field">
      <input type="time" {...props} />
      <svg className="date-field-icon" aria-hidden="true" viewBox="0 0 24 24" width="16" height="16">
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5V12l3 2" />
      </svg>
    </span>
  )
}
