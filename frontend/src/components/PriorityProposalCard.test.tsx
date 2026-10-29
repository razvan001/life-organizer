import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PriorityProposalCard } from './PriorityProposalCard'

const CHANGES = [
  { goalId: 'career-study', goalName: 'Study', from: 3, to: 5, reason: 'exam Friday' },
  { goalId: 'health-padel', goalName: 'Padel', from: 4, to: 3, reason: 'rest' },
]

// Weekly reviews no longer propose priorities; the card only shows ones saved in older threads.
describe('PriorityProposalCard', () => {
  it('shows one row per priority change and the re-plan note', () => {
    render(
      <PriorityProposalCard rationale="Exam week" priorityChanges={CHANGES} changes={['Would re-plan Thu 2026-10-01']}
        status="APPLIED" />,
    )

    expect(screen.getByText('Proposed priorities')).toBeInTheDocument()
    expect(screen.getByText('Exam week')).toBeInTheDocument()
    expect(screen.getByText('Study')).toBeInTheDocument()
    expect(screen.getByText('3 → 5')).toBeInTheDocument()
    expect(screen.getByText('exam Friday')).toBeInTheDocument()
    expect(screen.getByText('4 → 3')).toBeInTheDocument()
    expect(screen.getByText('Would re-plan Thu 2026-10-01')).toBeInTheDocument()
  })

  it('shows its stored outcome and no buttons', () => {
    const { rerender } = render(
      <PriorityProposalCard rationale="x" priorityChanges={CHANGES} changes={[]} status="APPLIED" />,
    )
    expect(screen.getByText('Applied')).toHaveClass('status-pill', 'status-applied')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()

    rerender(<PriorityProposalCard rationale="x" priorityChanges={CHANGES} changes={[]} status="REJECTED" />)
    expect(screen.getByText('Rejected')).toHaveClass('status-pill', 'status-rejected')
  })

  it('shows one that is still pending as expired: it can no longer be applied', () => {
    render(<PriorityProposalCard rationale="x" priorityChanges={CHANGES} changes={[]} status="PENDING" />)

    expect(screen.getByText('Expired')).toHaveClass('status-pill', 'status-expired')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
