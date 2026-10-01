import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '../api/types'
import { TODAY } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { ChatPanel } from './ChatPanel'

afterEach(() => vi.unstubAllGlobals())

const GYM_ID = TODAY.blocks[0].id

function message(overrides: Partial<ChatMessageDto>): ChatMessageDto {
  return { id: 1, date: '2026-09-14', role: 'USER', text: 'hi', proposal: null, createdAt: '2026-09-14T08:00:00Z', ...overrides }
}

const PENDING: ChatMessageDto = message({
  id: 2,
  role: 'ASSISTANT',
  text: "I'd cancel the gym.",
  proposal: {
    rationale: 'Gym is off',
    events: [{ type: 'CANCEL_BLOCK', blockId: GYM_ID, reason: null, date: null, title: null, start: null, end: null, energy: null }],
    changes: ['Cancelled Gym - slot is now free'],
    preview: { ...TODAY, blocks: TODAY.blocks.map((b) => (b.id === GYM_ID ? { ...b, status: 'CANCELLED' } : b)) },
    status: 'PENDING',
  },
})

const THREAD = [message({ id: 1, text: 'gym is off' }), PENDING]

function renderPanel(onApplied = vi.fn()) {
  render(<ChatPanel today="2026-09-14" currentBlocks={TODAY.blocks} busy={false} onApplied={onApplied} />)
  return onApplied
}

describe('ChatPanel', () => {
  it("shows today's thread with the pending proposal", async () => {
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse(THREAD),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14']),
    })

    renderPanel()

    const conversation = await screen.findByRole('list', { name: 'Conversation' })
    expect(within(conversation).getByText('gym is off')).toBeInTheDocument()
    expect(within(conversation).getByText("I'd cancel the gym.")).toBeInTheDocument()
    expect(within(conversation).getByText('Gym is off')).toBeInTheDocument()
    expect(within(conversation).getByRole('button', { name: 'Apply' })).toBeEnabled()
    expect(screen.queryByLabelText('Earlier chats')).not.toBeInTheDocument()
  })

  it('sends a message, shows it while the agent thinks, then shows the saved thread', async () => {
    const user = userEvent.setup()
    let thread: ChatMessageDto[] = []
    let finish: (response: Response) => void = () => {}
    const fetchMock = mockApi({
      'GET /api/assistant/chat': () => jsonResponse(thread),
      'GET /api/assistant/chat/days': () => jsonResponse([]),
      'POST /api/assistant/chat/messages': () => new Promise<Response>((resolve) => (finish = resolve)),
    })
    renderPanel()
    await screen.findByLabelText('Message the assistant')

    await user.type(screen.getByLabelText('Message the assistant'), 'gym is off')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Thinking…')).toBeInTheDocument()
    expect(screen.getByText('gym is off')).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ text: 'gym is off' })

    thread = THREAD
    finish(jsonResponse(PENDING))

    expect(await screen.findByText("I'd cancel the gym.")).toBeInTheDocument()
    expect(screen.queryByText('Thinking…')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Message the assistant')).toHaveValue('')
  })

  it('keeps the text and shows the error when the assistant is unavailable', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse([]),
      'GET /api/assistant/chat/days': () => jsonResponse([]),
      'POST /api/assistant/chat/messages': () =>
        problemResponse(502, 'Assistant unavailable', 'The assistant is unavailable right now'),
    })
    renderPanel()

    await user.type(await screen.findByLabelText('Message the assistant'), 'gym is off')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The assistant is unavailable right now')
    expect(screen.getByLabelText('Message the assistant')).toHaveValue('gym is off')
  })

  it('applies a proposal and reports the new plan', async () => {
    const user = userEvent.setup()
    let thread = THREAD
    const result = { ...TODAY, changes: ['Cancelled Gym - slot is now free'] }
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse(thread),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14']),
      'POST /api/assistant/chat/messages/2/apply': () => {
        thread = [THREAD[0], { ...PENDING, proposal: { ...PENDING.proposal!, status: 'APPLIED' } }]
        return jsonResponse(result)
      },
    })
    const onApplied = renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Apply' }))

    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(result))
    expect(await screen.findByText('Applied')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
  })

  it('rejects a proposal', async () => {
    const user = userEvent.setup()
    let thread = THREAD
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse(thread),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14']),
      'POST /api/assistant/chat/messages/2/reject': () => {
        thread = [THREAD[0], { ...PENDING, proposal: { ...PENDING.proposal!, status: 'REJECTED' } }]
        return jsonResponse(thread[1])
      },
    })
    renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Reject' }))

    expect(await screen.findByText('Rejected')).toBeInTheDocument()
  })

  it('shows an earlier day read-only', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse([]),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14', '2026-09-13']),
      'GET /api/assistant/chat?date=2026-09-13': () =>
        jsonResponse([message({ date: '2026-09-13', text: 'old question' }), { ...PENDING, date: '2026-09-13' }]),
    })
    renderPanel()

    await user.selectOptions(await screen.findByLabelText('Earlier chats'), '2026-09-13')

    expect(await screen.findByText('old question')).toBeInTheDocument()
    expect(screen.getByText('Expired (a newer message came in)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Message the assistant')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Earlier chats'), '')
    expect(await screen.findByLabelText('Message the assistant')).toBeInTheDocument()
  })
})
