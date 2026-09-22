import type { BlockDto, TodayPlanResponse, ProposedEventDto } from '../api/types'

interface Props {
  rationale: string
  events: ProposedEventDto[]
  preview: TodayPlanResponse
  currentBlocks: BlockDto[]
  busy: boolean
  onApply: () => void
  onReject: () => void
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
export function ProposalCard({ rationale, preview, currentBlocks, busy, onApply, onReject }: Props) {
  const lines = diffLines(currentBlocks, preview.blocks)

  return (
    <div className="proposal">
      <p>{rationale}</p>
      {lines.length > 0 && (
        <ul>
          {lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      )}
      <div className="block-actions">
        <button type="button" disabled={busy} onClick={onApply}>
          Apply
        </button>
        <button type="button" disabled={busy} onClick={onReject}>
          Reject
        </button>
      </div>
    </div>
  )
}
