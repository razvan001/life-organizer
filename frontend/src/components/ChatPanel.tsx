import { useEffect, useId, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { ApplyEventsResponse, BlockDto, ChatMessageDto } from '../api/types'
import { useApi } from '../api/useApi'
import { ProposalCard } from './ProposalCard'

interface Props {
  /** today's date (yyyy-MM-dd), from the plan on screen */
  today: string
  currentBlocks: BlockDto[]
  /** True while the page's own actions are busy - Send / Apply are disabled too. */
  busy: boolean
  onApplied: (result: ApplyEventsResponse) => void
}

/** The assistant: today's saved conversation with the planning agent, plus earlier days read-only. */
export function ChatPanel({ today, currentBlocks, busy: pageBusy, onApplied }: Props) {
  const thread = useApi(api.getChat)
  const days = useApi(api.getChatDays)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState<string | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pastDay, setPastDay] = useState('')
  const [pastThread, setPastThread] = useState<ChatMessageDto[] | null>(null)
  const inputId = useId()
  const pickerId = useId()
  const busy = pageBusy || sending !== null || working

  useEffect(() => {
    if (!pastDay) {
      setPastThread(null)
      return
    }
    let cancelled = false
    api.getChat(pastDay).then(
      (messages) => {
        if (!cancelled) setPastThread(messages)
      },
      (failure: unknown) => {
        if (!cancelled) setError(errorMessage(failure))
      },
    )
    return () => {
      cancelled = true
    }
  }, [pastDay])

  async function reloadThread() {
    thread.setData(await api.getChat())
  }

  async function send() {
    const text = input.trim()
    if (!text) return
    setSending(text)
    setError(null)
    try {
      await api.sendChatMessage({ text })
      setInput('')
      await reloadThread()
      days.refetch()
    } catch (failure) {
      setError(errorMessage(failure)) // the text stays in the box
    } finally {
      setSending(null)
    }
  }

  async function act(action: () => Promise<void>) {
    setWorking(true)
    setError(null)
    try {
      await action()
      await reloadThread()
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setWorking(false)
    }
  }

  const earlierDays = (days.data ?? []).filter((day) => day !== today)
  const readOnly = pastDay !== ''
  const messages = readOnly ? (pastThread ?? []) : (thread.data ?? [])

  return (
    <section className="assistant">
      <h3>Assistant</h3>
      {earlierDays.length > 0 && (
        <div className="field chat-days">
          <label htmlFor={pickerId}>Earlier chats</label>
          <select id={pickerId} value={pastDay} onChange={(event) => setPastDay(event.target.value)}>
            <option value="">Today</option>
            {earlierDays.map((day) => (
              <option key={day} value={day}>
                {day}
              </option>
            ))}
          </select>
        </div>
      )}

      <ul className="chat-messages" aria-label="Conversation">
        {messages.map((message) => (
          <li key={message.id} className={message.role === 'USER' ? 'chat-user' : 'chat-assistant'}>
            <p>{message.text}</p>
            {message.proposal && (
              <ProposalCard
                rationale={message.proposal.rationale}
                events={message.proposal.events}
                preview={message.proposal.preview}
                // only a live proposal is compared with the plan on screen
                currentBlocks={!readOnly && message.proposal.status === 'PENDING' ? currentBlocks : message.proposal.preview.blocks}
                changes={message.proposal.changes}
                status={readOnly && message.proposal.status === 'PENDING' ? 'EXPIRED' : message.proposal.status}
                busy={busy}
                onApply={() =>
                  act(async () => {
                    onApplied(await api.applyProposal(message.id))
                  })
                }
                onReject={() =>
                  act(async () => {
                    await api.rejectProposal(message.id)
                  })
                }
              />
            )}
          </li>
        ))}
        {!readOnly && sending !== null && (
          <>
            <li className="chat-user">
              <p>{sending}</p>
            </li>
            <li className="chat-assistant muted">
              <p>Thinking…</p>
            </li>
          </>
        )}
      </ul>
      {error && <p role="alert">{error}</p>}

      {!readOnly && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            send()
          }}
        >
          <div className="field">
            <label htmlFor={inputId}>Message the assistant</label>
            <input
              id={inputId}
              type="text"
              maxLength={2000}
              value={input}
              disabled={busy}
              onChange={(event) => setInput(event.target.value)}
            />
          </div>
          <button type="submit" disabled={busy || !input.trim()}>
            Send
          </button>
        </form>
      )}
    </section>
  )
}
