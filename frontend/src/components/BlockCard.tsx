import { useEffect, useId, useRef, useState } from 'react'
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

  // Keyboard focus. A ref holds a DOM element (or a plain value) between renders without causing a re-render.
  const cardRef = useRef<HTMLLIElement>(null)
  const postponeButtonRef = useRef<HTMLButtonElement>(null)
  const backedOut = useRef(false) // set by "Never mind" so the Postpone button gets focus back
  const wasActionable = useRef(actionable)

  // "Never mind" removes the form (and the focused button inside it): put focus back on Postpone.
  useEffect(() => {
    if (!postponing && backedOut.current) {
      backedOut.current = false
      postponeButtonRef.current?.focus()
    }
  }, [postponing])

  // Once an action makes the block non-actionable its buttons disappear and focus would fall back to
  // <body>, so the keyboard user loses their place. Move focus to the card instead - but only if focus
  // was lost that way (or was already inside the card), so it never steals focus from something else.
  useEffect(() => {
    if (wasActionable.current && !actionable) {
      const card = cardRef.current
      const focused = document.activeElement
      if (card && (focused === document.body || card.contains(focused))) {
        card.focus()
      }
    }
    wasActionable.current = actionable
  }, [actionable])

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
    // tabIndex -1: focusable by script (see above), but not a Tab stop
    <li
      ref={cardRef}
      tabIndex={-1}
      className={`block block-${block.kind.toLowerCase()} status-${block.status.toLowerCase()}`}
    >
      <div className="block-line">
        <span className="block-time">
          {block.start}–{block.end}
        </span>
        <span className="block-title">{block.title}</span>
        {block.status !== 'PLANNED' && <span className="badge">{STATUS_LABEL[block.status]}</span>}
      </div>
      {block.reason && <p className="block-reason">{block.reason}</p>}
      {block.flagged && block.status === 'PLANNED' && (
        <p className="block-flag">Postponed {block.postponeCount}× — try a 25 minute version?</p>
      )}

      {actionable && !postponing && (
        <div className="block-actions">
          <button
            className="btn-small"
            type="button"
            disabled={busy}
            onClick={() => onAction({ type: 'MARK_DONE', blockId: block.id })}
          >
            Done
          </button>
          <button
            className="btn-small"
            ref={postponeButtonRef}
            type="button"
            disabled={busy}
            onClick={() => setPostponing(true)}
          >
            Postpone
          </button>
          <button
            className="btn-small"
            type="button"
            disabled={busy}
            onClick={() => onAction({ type: 'CANCEL_BLOCK', blockId: block.id })}
          >
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
            autoFocus // the user just pressed Postpone: let them type the reason straight away
            onChange={(event) => setReason(event.target.value)}
          />
          <button className="btn-primary btn-small" type="submit" disabled={busy}>
            Confirm postpone
          </button>
          <button
            className="btn-small"
            type="button"
            onClick={() => {
              backedOut.current = true
              setPostponing(false)
            }}
          >
            Never mind
          </button>
        </form>
      )}
    </li>
  )
}
