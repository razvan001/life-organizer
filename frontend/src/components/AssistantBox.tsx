import { useId, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { ApplyEventsResponse, BlockDto, CommandResponse } from '../api/types'
import { ProposalCard } from './ProposalCard'

interface Props {
  currentBlocks: BlockDto[]
  /** True while the page's own actions (Done/Postpone/Cancel) are busy - Send is disabled too. */
  busy: boolean
  onApplied: (result: ApplyEventsResponse) => void
}

/** A one-line command box: type what changed, review a proposal, Apply or Reject. */
export function AssistantBox({ currentBlocks, busy: pageBusy, onApplied }: Props) {
  const [text, setText] = useState('')
  const [proposal, setProposal] = useState<CommandResponse | null>(null)
  const [notUnderstood, setNotUnderstood] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputId = useId()

  async function send() {
    if (!text.trim()) return
    setBusy(true)
    setError(null)
    setNotUnderstood(null)
    setProposal(null)
    try {
      const result = await api.proposeCommand({ text })
      if (result.understood) {
        setProposal(result)
      } else {
        setNotUnderstood(result.message ?? "I couldn't understand that.")
      }
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  async function apply() {
    if (!proposal) return
    setBusy(true)
    setError(null)
    try {
      const result = await api.applyEvents({ events: proposal.events })
      onApplied(result)
      setProposal(null)
      setText('')
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  function reject() {
    setProposal(null)
    setText('')
    setError(null)
  }

  const disabled = busy || pageBusy

  return (
    <section className="assistant">
      <h3>Assistant</h3>
      <div className="field">
        <label htmlFor={inputId}>Tell it what changed</label>
        <input
          id={inputId}
          type="text"
          value={text}
          maxLength={2000}
          disabled={disabled}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') send()
          }}
        />
      </div>
      <button type="button" disabled={disabled || !text.trim()} onClick={send}>
        Send
      </button>
      {error && <p role="alert">{error}</p>}
      {notUnderstood && <p className="muted">{notUnderstood}</p>}
      {proposal && proposal.understood && proposal.preview && (
        <ProposalCard
          rationale={proposal.rationale ?? ''}
          events={proposal.events}
          preview={proposal.preview}
          currentBlocks={currentBlocks}
          changes={proposal.changes}
          busy={disabled}
          onApply={apply}
          onReject={reject}
        />
      )}
    </section>
  )
}
