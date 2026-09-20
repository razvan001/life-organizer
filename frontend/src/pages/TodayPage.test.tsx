import { render, screen, waitFor, within } from '@testing-library/react'
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

    expect(await screen.findByRole('status')).toHaveTextContent('Marked done Claude certification')
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
