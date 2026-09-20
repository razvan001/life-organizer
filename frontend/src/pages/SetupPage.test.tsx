import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PARSED } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { SetupPage } from './SetupPage'

afterEach(() => vi.unstubAllGlobals())

const EMPTY_SETUP = { categories: [], goals: [], constraints: [] }

function renderSetup() {
  return render(
    <MemoryRouter initialEntries={['/setup']}>
      <Routes>
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/" element={<p>Today screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('SetupPage', () => {
  it('starts with the example text so a first visit needs no typing', () => {
    mockApi({ 'GET /api/setup': () => jsonResponse(EMPTY_SETUP) })

    renderSetup()

    // jest-dom's toHaveValue does not take partial matchers, so compare the raw values
    expect((screen.getByLabelText('Goals') as HTMLTextAreaElement).value).toContain('Claude certification')
    expect((screen.getByLabelText('Constraints (one per line)') as HTMLTextAreaElement).value).toContain(
      'Work:       08:30-17:00',
    )
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
  })

  it('parses the text and shows goals, constraints and warnings', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
    })
    renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByText('Parsed setup (not saved yet)')).toBeInTheDocument()
    expect(screen.getByText('Career')).toBeInTheDocument()
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
    expect(screen.getByLabelText('Priority for Gym')).toHaveValue('3')
    expect(screen.getByText('Work: 08:30–17:00, Mon–Fri')).toBeInTheDocument()
    expect(screen.getByText('Gym: 4× per week, 60 min each')).toBeInTheDocument()
    expect(screen.getByText('No goal found for frequency constraint: Padel')).toBeInTheDocument()
    const parse = fetchMock.mock.calls.find(([path]) => path === '/api/setup/parse')!
    expect(JSON.parse(parse[1]!.body as string)).toMatchObject({
      goalsText: expect.stringContaining('Gym'),
      constraintsText: expect.stringContaining('Work'),
    })
  })

  it('shows the backend message when parsing fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => problemResponse(400, 'Invalid request', 'goalsText must not be empty'),
    })
    renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('goalsText must not be empty')
  })

  it('saves the edited priorities, sends null for a cleared deadline, then opens Today', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
      'PUT /api/setup': () => jsonResponse(EMPTY_SETUP),
    })
    renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await user.selectOptions(await screen.findByLabelText('Priority for Claude certification'), '5')
    // an empty date input reports "" - the page must turn that into null
    fireEvent.change(screen.getByLabelText('Deadline for Claude certification'), { target: { value: '' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Today screen')).toBeInTheDocument()
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    const sent = JSON.parse(put[1]!.body as string)
    expect(sent).not.toHaveProperty('warnings')
    expect(sent.categories).toEqual(PARSED.categories)
    expect(sent.constraints).toEqual(PARSED.constraints)
    expect(sent.goals[0]).toMatchObject({ id: 'career-claude-certification', priority: 5, deadline: null })
    expect(sent.goals[1]).toEqual(PARSED.goals[1])
  })

  it('sets a deadline typed into the date field', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
      'PUT /api/setup': () => jsonResponse(EMPTY_SETUP),
    })
    renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    fireEvent.change(await screen.findByLabelText('Deadline for Gym'), { target: { value: '2026-12-31' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByText('Today screen')
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(put[1]!.body as string).goals[1].deadline).toBe('2026-12-31')
  })

  it('stays on the page and shows the message when saving fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
      'PUT /api/setup': () => problemResponse(400, 'Invalid request', "goal 'gym': priority must be between 1 and 5"),
    })
    renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await user.click(await screen.findByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('priority must be between 1 and 5')
    expect(screen.queryByText('Today screen')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('shows the saved setup on a return visit', async () => {
    const { warnings: _warnings, ...saved } = PARSED
    mockApi({ 'GET /api/setup': () => jsonResponse(saved) })

    renderSetup()

    expect(await screen.findByText('Saved setup')).toBeInTheDocument()
    expect(within(screen.getByText('Career').parentElement!).getByText('Claude certification')).toBeInTheDocument()
    expect(screen.getByLabelText('Deadline for Claude certification')).toHaveValue('2026-10-01')
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('cannot save a parse that found no goals', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse({ ...EMPTY_SETUP, warnings: ['Unrecognized line: ???'] }),
    })
    renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByText('Unrecognized line: ???')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled())
  })
})
