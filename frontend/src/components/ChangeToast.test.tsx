import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChangeToast, TOAST_MILLIS } from './ChangeToast'

afterEach(() => vi.useRealTimers())

describe('ChangeToast', () => {
  it('keeps an empty live region mounted when there are no changes', () => {
    render(<ChangeToast changes={[]} onDismiss={vi.fn()} />)

    // the region must exist before its content does, otherwise screen readers may not announce it
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    expect(screen.getByRole('status')).toHaveClass('visually-hidden')
    expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument()
  })

  it('fills the same live region element when changes arrive', () => {
    const { rerender } = render(<ChangeToast changes={[]} onDismiss={vi.fn()} />)
    const region = screen.getByRole('status')

    rerender(<ChangeToast changes={['Moved to 21:00']} onDismiss={vi.fn()} />)

    expect(screen.getByRole('status')).toBe(region)
    expect(region).toHaveTextContent('Moved to 21:00')
    expect(region).toHaveClass('toast')
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

  it('restarts the timer when the changes change', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    const { rerender } = render(<ChangeToast changes={['first']} onDismiss={onDismiss} />)

    act(() => vi.advanceTimersByTime(3000))
    rerender(<ChangeToast changes={['second']} onDismiss={onDismiss} />)

    act(() => vi.advanceTimersByTime(TOAST_MILLIS - 1))
    expect(onDismiss).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('does not dismiss after it was unmounted', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    const { unmount } = render(<ChangeToast changes={['x']} onDismiss={onDismiss} />)

    unmount()
    act(() => vi.advanceTimersByTime(TOAST_MILLIS * 2))

    expect(onDismiss).not.toHaveBeenCalled()
  })
})
