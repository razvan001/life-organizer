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
  EXPIRED: 'Expired (a newer message came in)',
}

const STATUS_LABEL: Record<string, string> = {
  PLANNED: 'Planned',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
  POSTPONED: 'Postponed',
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
      <p>{rationale}</p>
      {lines.length > 0 && (
        <ul>
          {lines.map((line, index) => (
            <li key={`${index}-${line}`}>{line}</li>
          ))}
        </ul>
      )}
      {changes.length > 0 && (
        <ul>
          {changes.map((change, index) => (
            <li key={`${index}-${change}`}>{change}</li>
          ))}
        </ul>
      )}
      {status && status !== 'PENDING' ? (
        <p className="muted proposal-status">{STATUS_TEXT[status]}</p>
      ) : (
        <div className="block-actions">
          <button type="button" disabled={busy} onClick={onApply}>
            Apply
          </button>
          <button type="button" disabled={busy} onClick={onReject}>
            Reject
          </button>
        </div>
      )}
    </div>
  )
}
