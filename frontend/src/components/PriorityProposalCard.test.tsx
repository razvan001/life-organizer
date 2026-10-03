import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PriorityProposalCard } from './PriorityProposalCard'

const CHANGES = [
  { goalId: 'career-study', goalName: 'Study', from: 3, to: 5, reason: 'exam Friday' },
  { goalId: 'health-padel', goalName: 'Padel', from: 4, to: 3, reason: 'rest' },
]

describe('PriorityProposalCard', () => {
  it('shows one row per priority change and the re-plan note', () => {
    render(
      <PriorityProposalCard rationale="Exam week" priorityChanges={CHANGES} changes={['Would re-plan Thu 2026-10-01']}
        busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByText('Proposed priorities')).toBeInTheDocument()
    expect(screen.getByText('Exam week')).toBeInTheDocument()
    expect(screen.getByText('Study')).toBeInTheDocument()
    expect(screen.getByText('3 → 5')).toBeInTheDocument()
    expect(screen.getByText('exam Friday')).toBeInTheDocument()
    expect(screen.getByText('4 → 3')).toBeInTheDocument()
    expect(screen.getByText('Would re-plan Thu 2026-10-01')).toBeInTheDocument()
  })

  it('applies and rejects', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    const onReject = vi.fn()
    render(
      <PriorityProposalCard rationale="x" priorityChanges={CHANGES} changes={[]} busy={false} onApply={onApply}
        onReject={onReject} />,
    )

    await user.click(screen.getByRole('button', { name: 'Apply' }))
    await user.click(screen.getByRole('button', { name: 'Reject' }))

    expect(onApply).toHaveBeenCalledOnce()
    expect(onReject).toHaveBeenCalledOnce()
  })

  it('shows a decided proposal as a status pill', () => {
    render(
      <PriorityProposalCard rationale="x" priorityChanges={CHANGES} changes={[]} status="APPLIED" busy={false}
        onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByText('Applied')).toHaveClass('status-pill', 'status-applied')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
