import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { DateInput, Select, TimeInput } from './FormControls'

function priorities() {
  return (
    <>
      <option value="1">1</option>
      <option value="2">2</option>
    </>
  )
}

describe('Select', () => {
  it('renders a native select with its options and label', () => {
    render(
      <Select aria-label="Priority" defaultValue="2">
        {priorities()}
      </Select>,
    )

    const select = screen.getByLabelText('Priority')
    expect(select.tagName).toBe('SELECT')
    expect(select).toHaveValue('2')
  })

  it('calls onChange when an option is chosen', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <Select aria-label="Priority" defaultValue="2" onChange={onChange}>
        {priorities()}
      </Select>,
    )

    await user.selectOptions(screen.getByLabelText('Priority'), '1')

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('Priority')).toHaveValue('1')
  })

  it('passes className, id and disabled to the native select', () => {
    render(
      <Select aria-label="Priority" className="x" id="p" disabled>
        {priorities()}
      </Select>,
    )

    const select = screen.getByLabelText('Priority')
    expect(select).toHaveClass('x')
    expect(select).toHaveAttribute('id', 'p')
    expect(select).toBeDisabled()
    expect(select.parentElement).toHaveClass('select')
  })
})

function ControlledDate() {
  const [value, setValue] = useState('')
  return <DateInput aria-label="Deadline" value={value} onChange={(event) => setValue(event.target.value)} />
}

function ControlledTime() {
  const [value, setValue] = useState('')
  return <TimeInput aria-label="Start" value={value} onChange={(event) => setValue(event.target.value)} />
}

describe('DateInput', () => {
  it('renders an input of type date that reports changes', () => {
    render(<ControlledDate />)
    const input = screen.getByLabelText('Deadline')

    fireEvent.change(input, { target: { value: '2026-11-01' } })

    expect(input).toHaveAttribute('type', 'date')
    expect(input).toHaveValue('2026-11-01')
    expect(input.parentElement).toHaveClass('date-field')
  })

  it('passes disabled to the native input', () => {
    render(<DateInput aria-label="Deadline" disabled />)
    expect(screen.getByLabelText('Deadline')).toBeDisabled()
  })
})

describe('TimeInput', () => {
  it('renders an input of type time that reports changes', () => {
    render(<ControlledTime />)
    const input = screen.getByLabelText('Start')

    fireEvent.change(input, { target: { value: '09:30' } })

    expect(input).toHaveAttribute('type', 'time')
    expect(input).toHaveValue('09:30')
    expect(input.parentElement).toHaveClass('date-field')
  })
})

describe('icons', () => {
  it('are hidden from assistive tech', () => {
    const { container } = render(
      <>
        <Select aria-label="Priority">{priorities()}</Select>
        <DateInput aria-label="Deadline" />
        <TimeInput aria-label="Start" />
      </>,
    )

    const icons = container.querySelectorAll('svg')
    expect(icons).toHaveLength(3)
    icons.forEach((icon) => expect(icon).toHaveAttribute('aria-hidden', 'true'))
  })
})
