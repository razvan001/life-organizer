import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MemoryResult, NoteDto } from '../api/types'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { NotesPage } from './NotesPage'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const GYM: NoteDto = {
  id: 1,
  title: 'Gym',
  body: "after gym I can't focus on study",
  createdAt: '2026-09-28T10:00:00Z',
  updatedAt: '2026-09-28T10:00:00Z',
}

const RESULTS: MemoryResult[] = [
  { sourceType: 'NOTE', sourceId: '1', title: 'Gym', date: null, snippet: "after gym I can't focus", score: 0.81 },
  {
    sourceType: 'DAY',
    sourceId: '2026-09-24',
    title: 'Thu 2026-09-24',
    date: '2026-09-24',
    snippet: 'Thu 2026-09-24: Gym done 07:00',
    score: 0.52,
  },
]

describe('NotesPage', () => {
  it('lists saved notes', async () => {
    mockApi({ 'GET /api/notes': () => jsonResponse([GYM]) })

    render(<NotesPage />)

    expect(await screen.findByRole('button', { name: 'Gym' })).toBeInTheDocument()
  })

  it('creates a note and shows it in the list', async () => {
    const user = userEvent.setup()
    let notes: NoteDto[] = []
    const fetchMock = mockApi({
      'GET /api/notes': () => jsonResponse(notes),
      'POST /api/notes': () => {
        notes = [GYM]
        return jsonResponse(GYM, 201)
      },
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Title'), 'Gym')
    await user.type(screen.getByLabelText('Body'), "after gym I can't focus on study")
    await user.click(screen.getByRole('button', { name: 'Save note' }))

    expect(await screen.findByRole('button', { name: 'Gym' })).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ title: 'Gym', body: "after gym I can't focus on study" })
    expect(screen.getByRole('heading', { name: 'Edit note' })).toBeInTheDocument()
  })

  it('edits an existing note with PUT', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/notes': () => jsonResponse([GYM]),
      'PUT /api/notes/1': () => jsonResponse({ ...GYM, body: 'new body' }),
    })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))

    const body = screen.getByLabelText('Body')
    await user.clear(body)
    await user.type(body, 'new body')
    await user.click(screen.getByRole('button', { name: 'Save note' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true))
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(put[1]!.body as string)).toEqual({ title: 'Gym', body: 'new body' })
  })

  it('deletes after confirming', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    let notes = [GYM]
    mockApi({
      'GET /api/notes': () => jsonResponse(notes),
      'DELETE /api/notes/1': () => {
        notes = []
        return new Response(null, { status: 204 })
      },
    })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))

    await user.click(screen.getByRole('button', { name: 'Delete note' }))

    expect(await screen.findByText('No notes yet.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New note' })).toBeInTheDocument()
  })

  it('does not delete when the confirm is cancelled', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const fetchMock = mockApi({ 'GET /api/notes': () => jsonResponse([GYM]) })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))

    await user.click(screen.getByRole('button', { name: 'Delete note' }))

    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
  })

  it('shows search results with their source and opens a note result', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/notes': () => jsonResponse([GYM]),
      'GET /api/memory/search?q=tired%20after%20workout': () => jsonResponse(RESULTS),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'tired after workout')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    const list = await screen.findByRole('list', { name: 'Search results' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('Note')).toBeInTheDocument()
    expect(within(items[1]).getByText('Day')).toBeInTheDocument()
    expect(within(items[1]).getByText('Thu 2026-09-24: Gym done 07:00')).toBeInTheDocument()
    expect(within(items[0]).getByText('0.81')).toBeInTheDocument()

    await user.click(within(items[0]).getByRole('button', { name: 'Open' }))
    expect(screen.getByLabelText('Title')).toHaveValue('Gym')
  })

  it('says when nothing was found', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/notes': () => jsonResponse([]),
      'GET /api/memory/search?q=xyz': () => jsonResponse([]),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'xyz')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText('Nothing found.')).toBeInTheDocument()
  })

  it('shows a search error', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/notes': () => jsonResponse([]),
      'GET /api/memory/search?q=xyz': () => problemResponse(400, 'Invalid request', 'q must be at most 500 characters'),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'xyz')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('q must be at most 500 characters')
  })

  it('disables Save until a title is typed', async () => {
    mockApi({ 'GET /api/notes': () => jsonResponse([]) })

    render(<NotesPage />)

    expect(await screen.findByText('No notes yet.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save note' })).toBeDisabled()
  })

  it('shows the load error when notes fail to load', async () => {
    mockApi({ 'GET /api/notes': () => problemResponse(500, 'Unexpected error', 'Something went wrong') })

    render(<NotesPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong')
  })

  it('disables switching notes and opening search results while a save is in flight', async () => {
    const user = userEvent.setup()
    const STUDY: NoteDto = { ...GYM, id: 2, title: 'Study' }
    let finish: (response: Response) => void = () => {}
    mockApi({
      'GET /api/notes': () => jsonResponse([GYM, STUDY]),
      'GET /api/memory/search?q=gym': () => jsonResponse(RESULTS),
      'PUT /api/notes/1': () => new Promise<Response>((resolve) => (finish = resolve)),
    })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))
    await user.type(screen.getByLabelText('Search your notes and past days'), 'gym')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await screen.findByRole('list', { name: 'Search results' })

    await user.click(screen.getByRole('button', { name: 'Save note' }))

    expect(screen.getByRole('button', { name: 'Study' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Open' })).toBeDisabled()

    finish(jsonResponse(GYM))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Study' })).toBeEnabled())
  })

  it('shows an error when Opening a search result whose note is gone', async () => {
    const user = userEvent.setup()
    const STALE: MemoryResult = { sourceType: 'NOTE', sourceId: '99', title: 'Old note', date: null, snippet: 'gone', score: 0.5 }
    mockApi({
      'GET /api/notes': () => jsonResponse([GYM]),
      'GET /api/memory/search?q=old': () => jsonResponse([STALE]),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'old')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    await user.click(await screen.findByRole('button', { name: 'Open' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That note no longer exists.')
  })
})
