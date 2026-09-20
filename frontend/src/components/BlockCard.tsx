import { useId, useState } from 'react'
import type { BlockDto, BlockStatus, PlanEventRequest } from '../api/types'

interface Props {
  block: BlockDto
  /** True while a request is running; every action button is disabled. */
  busy: boolean
  onAction: (event: PlanEventRequest) => void
}

const STATUS_LABEL: Record<BlockStatus, string> = {
  PLANNED: 'Planned',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
  POSTPONED: 'Postponed',
}

// Fixed and routine blocks are not movable, and free time is not a task.
const ACTIONABLE_KINDS = ['GOAL', 'ADHOC']

export function BlockCard({ block, busy, onAction }: Props) {
  const [postponing, setPostponing] = useState(false)
  const [reason, setReason] = useState('')
  const reasonId = useId()

  const actionable = block.status === 'PLANNED' && ACTIONABLE_KINDS.includes(block.kind)

  function confirmPostpone() {
    const trimmed = reason.trim()
    onAction(
      trimmed
        ? { type: 'POSTPONE_BLOCK', blockId: block.id, reason: trimmed }
        : { type: 'POSTPONE_BLOCK', blockId: block.id },
    )
    setPostponing(false)
    setReason('')
  }

  return (
    <li className={`block block-${block.kind.toLowerCase()} status-${block.status.toLowerCase()}`}>
      <div className="block-line">
        <span className="block-time">
          {block.start}–{block.end}
        </span>
        <span className="block-title">{block.title}</span>
        {block.status !== 'PLANNED' && <span className="badge">{STATUS_LABEL[block.status]}</span>}
      </div>
      {block.reason && <p className="block-reason">{block.reason}</p>}
      {block.flagged && (
        <p className="block-flag">Postponed {block.postponeCount}× — try a 25 minute version?</p>
      )}

      {actionable && !postponing && (
        <div className="block-actions">
          <button type="button" disabled={busy} onClick={() => onAction({ type: 'MARK_DONE', blockId: block.id })}>
            Done
          </button>
          <button type="button" disabled={busy} onClick={() => setPostponing(true)}>
            Postpone
          </button>
          <button type="button" disabled={busy} onClick={() => onAction({ type: 'CANCEL_BLOCK', blockId: block.id })}>
            Cancel
          </button>
        </div>
      )}

      {actionable && postponing && (
        <form
          className="block-actions"
          onSubmit={(event) => {
            event.preventDefault()
            confirmPostpone()
          }}
        >
          <label htmlFor={reasonId}>Reason (optional)</label>
          <input
            id={reasonId}
            type="text"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
          <button type="submit" disabled={busy}>
            Confirm postpone
          </button>
          <button type="button" onClick={() => setPostponing(false)}>
            Never mind
          </button>
        </form>
      )}
    </li>
  )
}
