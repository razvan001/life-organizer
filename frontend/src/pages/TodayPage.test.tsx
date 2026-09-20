import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeBlock, TODAY } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { TodayPage } from './TodayPage'

afterEach(() => vi.unstubAllGlobals())

function renderToday() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<TodayPage />} />
        <Route path="/setup" element={<p>Setup screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

const CERT_ID = 'career-claude-certification-2026-09-14'

describe('TodayPage', () => {
  it("shows today's blocks in order", async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })

    renderToday()

    expect(await screen.findByRole('heading', { name: "Today's plan" })).toBeInTheDocument()
    expect(screen.getByText('2026-09-14')).toBeInTheDocument()
    const titles = screen.getAllByRole('listitem').map((item) => item.querySelector('.block-title')?.textContent)
    expect(titles).toEqual(['Gym', 'Work', 'Claude certification'])
  })

  it('shows the plan warnings', async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse({ ...TODAY, warnings: ['Learning quota does not fit'] }) })

    renderToday()

    expect(await screen.findByText('Learning quota does not fit')).toBeInTheDocument()
  })

  it('sends the user to Setup when no setup is saved yet', async () => {
    mockApi({
      'GET /api/plan/today': () => problemResponse(404, 'Setup required', 'No setup saved yet'),
    })

    renderToday()

    expect(await screen.findByText('Setup screen')).toBeInTheDocument()
  })

  it('shows other load errors', async () => {
    mockApi({ 'GET /api/plan/today': () => problemResponse(500, 'Unexpected error', 'Something went wrong') })

    renderToday()

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong')
  })

  it('marks a block done and shows the new plan and what changed', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () =>
        jsonResponse({
          date: '2026-09-14',
          warnings: [],
          changes: ['Marked done Claude certification'],
          blocks: TODAY.blocks.map((block) => (block.id === CERT_ID ? { ...block, status: 'DONE' } : block)),
        }),
    })
    renderToday()

    const certification = (await screen.findByText('Claude certification')).closest('li')!
    await user.click(within(certification).getByRole('button', { name: 'Done' }))

    // the live region is always in the page (empty at first), so wait for its text, not for the element
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Marked done Claude certification'))
    expect(within(certification).getByText('Done')).toHaveClass('badge')
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ type: 'MARK_DONE', blockId: CERT_ID })
  })

  it('postpones with a reason and renders the moved block', async () => {
    const user = userEvent.setup()
    const moved = makeBlock({ id: `${CERT_ID}-p1`, start: '21:00', end: '22:00', postponeCount: 1 })
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () =>
        jsonResponse({
          date: '2026-09-14',
          warnings: [],
          changes: ['Moved Claude certification to 21:00'],
          blocks: [
            ...TODAY.blocks.map((block) => (block.id === CERT_ID ? { ...block, status: 'POSTPONED', reason: 'tired' } : block)),
            moved,
          ],
        }),
    })
    renderToday()

    const certification = (await screen.findByText('Claude certification')).closest('li')!
    await user.click(within(certification).getByRole('button', { name: 'Postpone' }))
    await user.type(within(certification).getByLabelText('Reason (optional)'), 'tired')
    await user.click(within(certification).getByRole('button', { name: 'Confirm postpone' }))

    expect(await screen.findByText('21:00–22:00')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Moved Claude certification to 21:00')
    expect(screen.getByText('Postponed')).toBeInTheDocument()
  })

  it('keeps the plan and shows the error when an action fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () => problemResponse(422, 'Unknown block', "No block with id 'x'"),
    })
    renderToday()

    await user.click((await screen.findAllByRole('button', { name: 'Done' }))[0])

    expect(await screen.findByRole('alert')).toHaveTextContent("No block with id 'x'")
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
    // the buttons work again after the failure
    expect(screen.getAllByRole('button', { name: 'Done' })[0]).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Dismiss error' }))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows an action error in the fixed bottom box, not at the top of the page', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () => problemResponse(422, 'Unknown block', "No block with id 'x'"),
    })
    renderToday()

    await user.click((await screen.findAllByRole('button', { name: 'Done' }))[0])

    expect(await screen.findByRole('alert')).toHaveClass('toast', 'toast-error')
  })

  it('clears an old action error when the next action starts', async () => {
    const user = userEvent.setup()
    let attempt = 0
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () =>
        ++attempt === 1
          ? problemResponse(422, 'Unknown block', "No block with id 'x'")
          : new Promise<Response>(() => {}),
    })
    renderToday()
    await user.click((await screen.findAllByRole('button', { name: 'Done' }))[0])
    await screen.findByRole('alert')

    await user.click(screen.getAllByRole('button', { name: 'Done' })[0])

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  it('disables the actions while a request is running', async () => {
    const user = userEvent.setup()
    let finish: (response: Response) => void = () => {}
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () => new Promise<Response>((resolve) => (finish = resolve)),
    })
    renderToday()

    await user.click((await screen.findAllByRole('button', { name: 'Done' }))[0])

    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Done' })[0]).toBeDisabled())
    finish(jsonResponse({ ...TODAY, changes: [] }))
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Done' })[0]).toBeEnabled())
  })
})

describe('TodayPage on a stale day', () => {
  afterEach(() => {
    vi.useRealTimers()
    // remove the own property so jsdom's real visibilityState is used again
    Reflect.deleteProperty(document, 'visibilityState')
  })

  function showTab(state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: state })
    document.dispatchEvent(new Event('visibilitychange'))
  }

  function countToday(fetchMock: ReturnType<typeof mockApi>) {
    return fetchMock.mock.calls.filter(([path]) => path === '/api/plan/today').length
  }

  it('loads the plan again when the tab becomes visible', async () => {
    const fetchMock = mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })
    renderToday()
    await screen.findByText('Claude certification')
    expect(countToday(fetchMock)).toBe(1)

    act(() => showTab('visible'))

    await waitFor(() => expect(countToday(fetchMock)).toBe(2))
  })

  it('does not load again when the tab is hidden', async () => {
    const fetchMock = mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })
    renderToday()
    await screen.findByText('Claude certification')

    act(() => showTab('hidden'))

    expect(countToday(fetchMock)).toBe(1)
  })

  it('keeps showing the timeline while the reload is running', async () => {
    let calls = 0
    mockApi({
      'GET /api/plan/today': () => (++calls === 1 ? jsonResponse(TODAY) : new Promise<Response>(() => {})),
    })
    renderToday()
    await screen.findByText('Claude certification')

    act(() => showTab('visible'))

    await waitFor(() => expect(calls).toBe(2))
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
    expect(screen.queryByText(/Loading today/)).not.toBeInTheDocument()
  })

  // Only the interval and the clock are faked: findBy/waitFor still need the real setTimeout.
  const FAKE = { toFake: ['setInterval', 'clearInterval', 'Date'] as ('setInterval' | 'clearInterval' | 'Date')[] }

  it('reloads within a minute once the calendar day has moved on from the shown plan', async () => {
    vi.useFakeTimers(FAKE)
    vi.setSystemTime(new Date(2026, 8, 15, 0, 5, 0)) // local time: the plan on screen is for 2026-09-14
    const fetchMock = mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })
    renderToday()
    await screen.findByText('Claude certification')
    expect(countToday(fetchMock)).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })

    expect(countToday(fetchMock)).toBe(2)
  })

  it('does not reload every minute while the shown plan is still for today', async () => {
    vi.useFakeTimers(FAKE)
    vi.setSystemTime(new Date(2026, 8, 14, 23, 30, 0))
    const fetchMock = mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })
    renderToday()
    await screen.findByText('Claude certification')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60_000)
    })

    expect(countToday(fetchMock)).toBe(1)
  })
})
