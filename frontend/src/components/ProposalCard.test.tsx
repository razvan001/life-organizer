import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import type { TodayPlanResponse } from '../api/types'
import { ProposalCard } from './ProposalCard'

const CURRENT = [makeBlock({ id: 'padel-1', title: 'Padel', status: 'PLANNED', start: '19:00', end: '20:00' })]

function preview(blocks: TodayPlanResponse['blocks']): TodayPlanResponse {
  return { date: '2026-09-14', blocks, warnings: [] }
}

describe('ProposalCard', () => {
  it('shows the rationale and the Apply/Reject buttons', () => {
    render(
      <ProposalCard
        rationale="Padel is cancelled"
        events={[]} changes={[]}
        preview={preview(CURRENT)}
        currentBlocks={CURRENT}
        busy={false}
        onApply={vi.fn()}
        onReject={vi.fn()}
      />,
    )

    expect(screen.getByText('Padel is cancelled')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('shows a decided proposal as a status pill instead of buttons', () => {
    render(
      <ProposalCard rationale="x" events={[]} changes={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        status="APPLIED" busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByText('Applied')).toHaveClass('status-pill', 'status-applied')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('Proposed changes')).toBeInTheDocument()
  })

  it('shows one line for a block whose status changed', () => {
    const changed = [makeBlock({ id: 'padel-1', title: 'Padel', status: 'CANCELLED', start: '19:00', end: '20:00' })]

    render(
      <ProposalCard rationale="x" events={[]} changes={[]} preview={preview(changed)} currentBlocks={CURRENT}
        busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByText(/Padel/)).toHaveTextContent('Padel 19:00 → Cancelled 19:00')
  })

  it('shows nothing changed when the preview matches the current plan', () => {
    render(
      <ProposalCard rationale="x" events={[]} changes={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
  })

  it('shows the engine changes even when the preview matches the current plan (no diff line)', () => {
    render(
      <ProposalCard rationale="x" events={[]} changes={['No room this week to reschedule Gym']}
        preview={preview(CURRENT)} currentBlocks={CURRENT} busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByText('No room this week to reschedule Gym')).toBeInTheDocument()
    expect(screen.queryByRole('listitem')).toBeInTheDocument()
  })

  it('calls onApply and onReject', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    const onReject = vi.fn()
    render(
      <ProposalCard rationale="x" events={[]} changes={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        busy={false} onApply={onApply} onReject={onReject} />,
    )

    await user.click(screen.getByRole('button', { name: 'Apply' }))
    await user.click(screen.getByRole('button', { name: 'Reject' }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onReject).toHaveBeenCalledTimes(1)
  })

  it('disables both buttons while busy', () => {
    render(
      <ProposalCard rationale="x" events={[]} changes={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        busy={true} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled()
  })
})
