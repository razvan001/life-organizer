import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import { BlockCard } from './BlockCard'

function renderCard(block = makeBlock(), busy = false) {
  const onAction = vi.fn()
  render(
    <ol>
      <BlockCard block={block} busy={busy} onAction={onAction} />
    </ol>,
  )
  return onAction
}

describe('BlockCard', () => {
  it('shows time, title and the three actions for a planned goal block', () => {
    renderCard()

    expect(screen.getByText('18:00–19:00')).toBeInTheDocument()
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Postpone' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('sends MARK_DONE and CANCEL_BLOCK with the block id', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Done' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onAction).toHaveBeenNthCalledWith(1, { type: 'MARK_DONE', blockId: 'career-claude-certification-2026-09-14' })
    expect(onAction).toHaveBeenNthCalledWith(2, { type: 'CANCEL_BLOCK', blockId: 'career-claude-certification-2026-09-14' })
  })

  it('asks for an optional reason before postponing and sends it trimmed', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    expect(onAction).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('Reason (optional)'), '  too tired  ')
    await user.click(screen.getByRole('button', { name: 'Confirm postpone' }))

    expect(onAction).toHaveBeenCalledWith({
      type: 'POSTPONE_BLOCK',
      blockId: 'career-claude-certification-2026-09-14',
      reason: 'too tired',
    })
    // the form closes again
    expect(screen.getByRole('button', { name: 'Postpone' })).toBeInTheDocument()
  })

  it('omits the reason when it is left blank, and Enter confirms', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    await user.type(screen.getByLabelText('Reason (optional)'), '   {Enter}')

    expect(onAction).toHaveBeenCalledWith({ type: 'POSTPONE_BLOCK', blockId: 'career-claude-certification-2026-09-14' })
  })

  it('lets the user back out of a postpone', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    await user.click(screen.getByRole('button', { name: 'Never mind' }))

    expect(onAction).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it('has no actions for fixed, routine or free blocks', () => {
    for (const kind of ['FIXED', 'ROUTINE', 'FREE'] as const) {
      const { unmount } = render(
        <ol>
          <BlockCard block={makeBlock({ kind })} busy={false} onAction={vi.fn()} />
        </ol>,
      )
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      unmount()
    }
  })

  it('has actions for an ad-hoc block', () => {
    renderCard(makeBlock({ kind: 'ADHOC', goalId: null }))

    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it('shows a status badge and no actions once a block is no longer planned', () => {
    renderCard(makeBlock({ status: 'DONE' }))

    expect(screen.getByText('Done')).toHaveClass('badge')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the postponement reason', () => {
    renderCard(makeBlock({ status: 'POSTPONED', reason: 'tired' }))

    expect(screen.getByText('Postponed')).toBeInTheDocument()
    expect(screen.getByText('tired')).toBeInTheDocument()
  })

  it('suggests a shorter session for a flagged block', () => {
    renderCard(makeBlock({ flagged: true, postponeCount: 3 }))

    expect(screen.getByText('Postponed 3× — try a 25 minute version?')).toBeInTheDocument()
  })

  it('shows the shorter-session hint only while the block is still planned', () => {
    renderCard(makeBlock({ flagged: true, postponeCount: 3, status: 'DONE' }))

    expect(screen.queryByText(/try a 25 minute version/)).not.toBeInTheDocument()
  })

  it('moves focus into the reason field when the postpone form opens', async () => {
    const user = userEvent.setup()
    renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))

    expect(screen.getByLabelText('Reason (optional)')).toHaveFocus()
  })

  it('returns focus to the Postpone button when the user backs out', async () => {
    const user = userEvent.setup()
    renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    await user.click(screen.getByRole('button', { name: 'Never mind' }))

    expect(screen.getByRole('button', { name: 'Postpone' })).toHaveFocus()
  })

  it('focuses the card itself when an action makes it non-actionable', () => {
    const onAction = vi.fn()
    const { rerender } = render(
      <ol>
        <BlockCard block={makeBlock()} busy={false} onAction={onAction} />
      </ol>,
    )
    expect(screen.getByRole('listitem')).not.toHaveFocus()

    rerender(
      <ol>
        <BlockCard block={makeBlock({ status: 'DONE' })} busy={false} onAction={onAction} />
      </ol>,
    )

    // the buttons are gone: without this, keyboard focus would fall back to <body>
    expect(screen.getByRole('listitem')).toHaveFocus()
  })

  it('does not steal focus from another control when a card stops being actionable', async () => {
    const user = userEvent.setup()
    const onAction = vi.fn()
    const { rerender } = render(
      <>
        <button type="button">Elsewhere</button>
        <ol>
          <BlockCard block={makeBlock()} busy={false} onAction={onAction} />
        </ol>
      </>,
    )
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }))

    rerender(
      <>
        <button type="button">Elsewhere</button>
        <ol>
          <BlockCard block={makeBlock({ status: 'DONE' })} busy={false} onAction={onAction} />
        </ol>
      </>,
    )

    expect(screen.getByRole('button', { name: 'Elsewhere' })).toHaveFocus()
  })

  it('disables every action while a request is running', () => {
    renderCard(makeBlock(), true)

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
    }
  })
})
