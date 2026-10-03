import { useEffect, useId, useRef, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { ApplyEventsResponse, BlockDto, ChatMessageDto } from '../api/types'
import { useApi } from '../api/useApi'
import { PriorityProposalCard } from './PriorityProposalCard'
import { ProposalCard } from './ProposalCard'
import './ChatPanel.css'

/** What the "Review my week" shortcut sends. */
const REVIEW_PROMPT = 'Review my week and suggest goal priorities.'

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
  const inputRef = useRef<HTMLInputElement>(null)
  const busy = pageBusy || sending !== null || working

  // The box is disabled while a message is out; hand the focus back once the send is over.
  const wasSending = useRef(false)
  useEffect(() => {
    const isSending = sending !== null
    if (wasSending.current && !isSending) inputRef.current?.focus()
    wasSending.current = isSending
  }, [sending])

  // A tab can stay open past midnight: when the page's day moves on, the saved thread and the day list
  // are reloaded and any earlier day on screen gives way to the new Today.
  const shownToday = useRef(today)
  const refetchThread = thread.refetch
  const refetchDays = days.refetch
  useEffect(() => {
    if (shownToday.current === today) return
    shownToday.current = today
    setPastDay('')
    refetchThread()
    refetchDays()
  }, [today, refetchThread, refetchDays])

  useEffect(() => {
    setPastThread(null) // never keep the previous day's messages while the next day loads
    if (!pastDay) return
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

  async function send(text: string, fromInput: boolean) {
    if (!text) return
    setSending(text)
    setError(null)
    try {
      await api.sendChatMessage({ text })
      if (fromInput) setInput('')
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
    let failed = false
    try {
      await action()
    } catch (failure) {
      failed = true
      setError(errorMessage(failure))
    }
    // Reload even after a failure: the proposal may have been applied or expired elsewhere (409).
    try {
      await reloadThread()
    } catch (failure) {
      if (!failed) setError(errorMessage(failure)) // otherwise keep the action's own error
    } finally {
      setWorking(false)
    }
  }

  const earlierDays = (days.data ?? []).filter((day) => day !== today)
  const readOnly = pastDay !== ''
  const messages = readOnly ? (pastThread ?? []) : (thread.data ?? [])
  const showHint = !readOnly && thread.data !== null && messages.length === 0 && sending === null

  // Keep the newest message in view inside the scrolling conversation area.
  const conversationRef = useRef<HTMLUListElement>(null)
  useEffect(() => {
    const list = conversationRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [messages, sending])

  return (
    <section className="assistant chat-card">
      <header className="chat-header">
        <div className="chat-title">
          <span className="chat-avatar" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="16" height="16">
              <path d="M12 3l1.8 4.6L18.5 9.4l-4.7 1.8L12 16l-1.8-4.8L5.5 9.4l4.7-1.8z" />
              <path d="M18 15l.8 1.9 1.9.8-1.9.8L18 20.4l-.8-1.9-1.9-.8 1.9-.8z" />
            </svg>
          </span>
          <h3>Assistant</h3>
        </div>
        {earlierDays.length > 0 && (
          <div className="chat-days">
            <label htmlFor={pickerId} className="visually-hidden">
              Earlier chats
            </label>
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
      </header>

      {showHint && <p className="chat-hint">Try: padel got canceled and I'm tired, or press Review my week</p>}
      <ul className="chat-messages" aria-label="Conversation" aria-live="polite" ref={conversationRef}>
        {messages.map((message) => (
          <li key={message.id} className={message.role === 'USER' ? 'chat-user' : 'chat-assistant'}>
            <p className="chat-bubble">{message.text}</p>
            {message.proposal?.kind === 'PRIORITIES' && (
              <PriorityProposalCard
                rationale={message.proposal.rationale}
                priorityChanges={message.proposal.priorityChanges}
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
            {message.proposal && message.proposal.kind !== 'PRIORITIES' && message.proposal.preview && (
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
              <p className="chat-bubble">{sending}</p>
            </li>
            <li className="chat-assistant">
              <p className="chat-bubble chat-typing">
                <span className="typing-dot" aria-hidden="true" />
                <span className="typing-dot" aria-hidden="true" />
                <span className="typing-dot" aria-hidden="true" />
                <span className="visually-hidden">Thinking…</span>
              </p>
            </li>
          </>
        )}
      </ul>
      {error && (
        <p role="alert" className="chat-error">
          {error}
        </p>
      )}
      {thread.error != null && !thread.data && (
        <p role="alert" className="chat-error">
          {errorMessage(thread.error)}
        </p>
      )}

      {!readOnly && (
        <>
          <div className="chat-shortcuts">
            <button type="button" className="chat-chip" disabled={busy} onClick={() => send(REVIEW_PROMPT, false)}>
              Review my week
            </button>
          </div>
          <form
            className="chat-input"
            onSubmit={(event) => {
              event.preventDefault()
              send(input.trim(), true)
            }}
          >
            <label htmlFor={inputId} className="visually-hidden">
              Message the assistant
            </label>
            <input
              id={inputId}
              ref={inputRef}
              type="text"
              maxLength={2000}
              placeholder="Tell the assistant what changed"
              value={input}
              disabled={busy}
              onChange={(event) => setInput(event.target.value)}
            />
            <button type="submit" className="chat-send" aria-label="Send" disabled={busy || !input.trim()}>
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path d="M4 12l16-8-6 16-2.5-6.5z" />
                <path d="M11.5 13.5L20 4" />
              </svg>
            </button>
          </form>
        </>
      )}
    </section>
  )
}
