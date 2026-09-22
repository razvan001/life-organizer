import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { AssistantBox } from './AssistantBox'

afterEach(() => vi.unstubAllGlobals())

const CURRENT = [makeBlock({ id: 'padel-1', title: 'Padel', status: 'PLANNED', start: '19:00', end: '20:00' })]

describe('AssistantBox', () => {
  it('sends the typed text and shows the proposal', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({
          understood: true,
          rationale: 'Padel is cancelled',
          message: null,
          events: [{ type: 'CANCEL_BLOCK', blockId: 'padel-1', reason: null, date: null, title: null, start: null, end: null, energy: null }],
          preview: { date: '2026-09-14', blocks: [makeBlock({ id: 'padel-1', title: 'Padel', status: 'CANCELLED', start: '19:00', end: '20:00' })], warnings: [] },
          changes: ['Cancelled Padel - slot is now free'],
        }),
    })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    await user.type(screen.getByLabelText('Tell it what changed'), 'padel got canceled')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Padel is cancelled')).toBeInTheDocument()
    const sent = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(sent).toEqual({ text: 'padel got canceled' })
  })

  it('shows the message and no proposal when not understood', async () => {
    const user = userEvent.setup()
    mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: false, rationale: null, message: "I couldn't find a matching block", events: [], preview: null, changes: [] }),
    })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    await user.type(screen.getByLabelText('Tell it what changed'), 'do the thing')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText("I couldn't find a matching block")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
  })

  it('applies the exact proposed events and reports the result', async () => {
    const user = userEvent.setup()
    const event = { type: 'CANCEL_BLOCK', blockId: 'padel-1', reason: null, date: null, title: null, start: null, end: null, energy: null }
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: true, rationale: 'ok', message: null, events: [event], preview: { date: '2026-09-14', blocks: CURRENT, warnings: [] }, changes: [] }),
      'POST /api/plan/today/events/batch': () => jsonResponse({ date: '2026-09-14', blocks: [], warnings: [], changes: ['Cancelled Padel'] }),
    })
    const onApplied = vi.fn()
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={onApplied} />)
    await user.type(screen.getByLabelText('Tell it what changed'), 'cancel padel')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByRole('button', { name: 'Apply' })

    await user.click(screen.getByRole('button', { name: 'Apply' }))

    expect(onApplied).toHaveBeenCalledWith({ date: '2026-09-14', blocks: [], warnings: [], changes: ['Cancelled Padel'] })
    const applyCall = fetchMock.mock.calls.find(([path]) => path === '/api/plan/today/events/batch')!
    expect(JSON.parse(applyCall[1]!.body as string)).toEqual({ events: [event] })
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
  })

  it('clears the proposal on Reject without sending a request', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: true, rationale: 'ok', message: null, events: [], preview: { date: '2026-09-14', blocks: CURRENT, warnings: [] }, changes: [] }),
    })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)
    await user.type(screen.getByLabelText('Tell it what changed'), 'cancel padel')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByRole('button', { name: 'Reject' })

    await user.click(screen.getByRole('button', { name: 'Reject' }))

    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows an error and keeps the text when the assistant is unavailable', async () => {
    const user = userEvent.setup()
    mockApi({ 'POST /api/assistant/command': () => problemResponse(502, 'Assistant unavailable', 'The assistant is unavailable right now') })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    await user.type(screen.getByLabelText('Tell it what changed'), 'cancel padel')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The assistant is unavailable right now')
  })

  it('disables Send while busy or while the text is empty', () => {
    render(<AssistantBox currentBlocks={CURRENT} busy={true} onApplied={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  })

  it('caps the text input at 2000 characters', () => {
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    expect(screen.getByLabelText('Tell it what changed')).toHaveAttribute('maxLength', '2000')
  })
})
