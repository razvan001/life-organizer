import type { BlockDto, ProposalStatus, TodayPlanResponse, ProposedEventDto } from '../api/types'
import './ProposalCard.css'

interface Props {
  rationale: string
  events: ProposedEventDto[]
  preview: TodayPlanResponse
  currentBlocks: BlockDto[]
  /** The engine's own change/refusal messages (e.g. "No room this week to reschedule Gym"). */
  changes: string[]
  /** When set and not PENDING, the buttons give way to the outcome. */
  status?: ProposalStatus
  busy: boolean
  onApply: () => void
  onReject: () => void
}

const STATUS_TEXT: Record<Exclude<ProposalStatus, 'PENDING'>, string> = {
  APPLIED: 'Applied',
  REJECTED: 'Rejected',
  EXPIRED: 'Expired',
}

const STATUS_LABEL: Record<string, string> = {
  PLANNED: 'Planned',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
  POSTPONED: 'Postponed',
}

interface FooterProps {
  /** When set and not PENDING, the buttons give way to the outcome. */
  status?: ProposalStatus
  busy: boolean
  onApply: () => void
  onReject: () => void
}

/** Apply / Reject, or the final status pill once the proposal is decided. Shared by both proposal cards. */
export function ProposalFooter({ status, busy, onApply, onReject }: FooterProps) {
  if (status && status !== 'PENDING') {
    return <span className={`status-pill status-${status.toLowerCase()}`}>{STATUS_TEXT[status]}</span>
  }
  return (
    <div className="proposal-actions">
      <button type="button" className="proposal-apply" disabled={busy} onClick={onApply}>
        Apply
      </button>
      <button type="button" disabled={busy} onClick={onReject}>
        Reject
      </button>
    </div>
  )
}

/** One line per block that differs between the current plan and the simulated preview. */
function diffLines(currentBlocks: BlockDto[], previewBlocks: BlockDto[]): string[] {
  const current = new Map(currentBlocks.map((block) => [block.id, block]))
  const lines: string[] = []
  for (const next of previewBlocks) {
    const before = current.get(next.id)
    if (!before) {
      lines.push(`${next.title} ${next.start}–${next.end} → new`)
    } else if (before.status !== next.status || before.start !== next.start) {
      lines.push(`${before.title} ${before.start} → ${STATUS_LABEL[next.status]} ${next.start}`)
    }
  }
  return lines
}

/** The rationale plus a diff, shown after a command is understood. Apply resends the exact events. */
export function ProposalCard({ rationale, preview, currentBlocks, changes, status, busy, onApply, onReject }: Props) {
  const lines = diffLines(currentBlocks, preview.blocks)

  return (
    <div className="proposal">
      <p className="proposal-heading">Proposed changes</p>
      <p className="proposal-rationale">{rationale}</p>
      {lines.length > 0 && (
        <ul className="proposal-lines">
          {lines.map((line, index) => (
            <li key={`${index}-${line}`}>{line}</li>
          ))}
        </ul>
      )}
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
