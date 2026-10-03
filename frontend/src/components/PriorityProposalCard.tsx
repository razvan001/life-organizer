import type { PriorityChangeDto, ProposalStatus } from '../api/types'
import { ProposalFooter } from './ProposalCard'
import './ProposalCard.css'

interface Props {
  rationale: string
  priorityChanges: PriorityChangeDto[]
  /** The re-plan note, e.g. "Would re-plan Thu 2026-10-01". */
  changes: string[]
  status?: ProposalStatus
  busy: boolean
  onApply: () => void
  onReject: () => void
}

/** A weekly-review proposal: new goal priorities, one row per goal, with Apply / Reject. */
export function PriorityProposalCard({ rationale, priorityChanges, changes, status, busy, onApply, onReject }: Props) {
  return (
    <div className="proposal">
      <p className="proposal-heading">Proposed priorities</p>
      <p className="proposal-rationale">{rationale}</p>
      <ul className="proposal-lines proposal-priorities">
        {priorityChanges.map((change) => (
          <li key={change.goalId}>
            <span className="priority-goal">{change.goalName}</span>{' '}
            <span className="priority-values">
              {change.from} → {change.to}
            </span>
            {' · '}
            <span className="priority-reason">{change.reason}</span>
          </li>
        ))}
      </ul>
      {changes.length > 0 && (
        <ul className="proposal-lines proposal-engine">
          {changes.map((change, index) => (
            <li key={`${index}-${change}`}>{change}</li>
          ))}
        </ul>
      )}
      <ProposalFooter status={status} busy={busy} onApply={onApply} onReject={onReject} />
    </div>
  )
}
