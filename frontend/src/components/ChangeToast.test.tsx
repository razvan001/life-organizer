import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChangeToast, TOAST_MILLIS } from './ChangeToast'

afterEach(() => vi.useRealTimers())

describe('ChangeToast', () => {
  it('renders nothing when there are no changes', () => {
    const { container } = render(<ChangeToast changes={[]} onDismiss={vi.fn()} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('lists every change', () => {
    render(<ChangeToast changes={['Postponed Claude certification', 'Moved to 21:00']} onDismiss={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('Postponed Claude certification')
    expect(screen.getByRole('status')).toHaveTextContent('Moved to 21:00')
  })

  it('can be dismissed by hand', async () => {
    const user = userEvent.setup()
    const onDismiss = vi.fn()
    render(<ChangeToast changes={['x']} onDismiss={onDismiss} />)

    await user.click(screen.getByRole('button', { name: 'Dismiss' }))

    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('dismisses itself after a few seconds, not before', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    render(<ChangeToast changes={['x']} onDismiss={onDismiss} />)

    act(() => vi.advanceTimersByTime(TOAST_MILLIS - 1))
    expect(onDismiss).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
