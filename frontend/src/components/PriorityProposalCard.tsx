import type { PriorityChangeDto, ProposalStatus } from '../api/types'
import { StatusPill } from './ProposalCard'
import './ProposalCard.css'

interface Props {
  rationale: string
  priorityChanges: PriorityChangeDto[]
  /** The re-plan note, e.g. "Would re-plan Thu 2026-10-01". */
  changes: string[]
  status: ProposalStatus
}

/**
 * A proposal of new goal priorities from the old weekly review, one row per goal. Nothing creates these any more;
 * old threads still show them, read-only. One still pending can no longer be applied, so it shows as expired.
 */
export function PriorityProposalCard({ rationale, priorityChanges, changes, status }: Props) {
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
      <StatusPill status={status === 'PENDING' ? 'EXPIRED' : status} />
    </div>
  )
}
