import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EXAMPLE_GOALS } from '../setup/example'
import { PARSED } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { SetupPage } from './SetupPage'

afterEach(() => vi.unstubAllGlobals())

const EMPTY_SETUP = { categories: [], goals: [], constraints: [] }

// The page shows "Loading…" until the saved setup answers; every test starts after that.
async function renderSetup() {
  const result = render(
    <MemoryRouter initialEntries={['/setup']}>
      <Routes>
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/" element={<p>Today screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
  await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
  return result
}

const { warnings: _warnings, ...SAVED } = PARSED
const AI_OFF = { aiEnabled: false, ragEnabled: false }

describe('SetupPage', () => {
  it('starts with the example text so a first visit needs no typing', async () => {
    mockApi({ 'GET /api/setup': () => jsonResponse(EMPTY_SETUP) })

    await renderSetup()

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
    await renderSetup()

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
    await renderSetup()

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
    await renderSetup()
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
    await renderSetup()
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
    await renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await user.click(await screen.findByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('priority must be between 1 and 5')
    expect(screen.queryByText('Today screen')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('disables Save and explains why once the text changed after a Parse', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
    })
    await renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))
    expect(await screen.findByRole('button', { name: 'Save' })).toBeEnabled()

    await user.type(screen.getByLabelText('Goals'), ' more')

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByText('The text changed since the last Parse. Press Parse to apply your edits.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
    expect(screen.queryByText(/The text changed since the last Parse/)).not.toBeInTheDocument()
  })

  it('also treats an edit of the constraints box as stale', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
    })
    await renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))
    await screen.findByRole('button', { name: 'Save' })

    await user.type(screen.getByLabelText('Constraints (one per line)'), 'x')

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('is stale when the text is edited while a Parse is still running', async () => {
    const user = userEvent.setup()
    let finish: (response: Response) => void = () => {}
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => new Promise<Response>((resolve) => (finish = resolve)),
    })
    await renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await user.type(screen.getByLabelText('Goals'), ' more')
    finish(jsonResponse(PARSED))

    expect(await screen.findByText(/The text changed since the last Parse/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('keeps the example and an enabled Parse on a first visit, even after the setup answer arrived', async () => {
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'GET /api/config': () => jsonResponse({ aiEnabled: false, ragEnabled: false }),
    })

    await renderSetup()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await act(async () => {})

    expect((screen.getByLabelText('Goals') as HTMLTextAreaElement).value).toContain('Claude certification')
    expect(screen.getByRole('button', { name: 'Parse' })).toBeEnabled()
  })

  it('cannot save a parse that found no goals', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse({ ...EMPTY_SETUP, warnings: ['Unrecognized line: ???'] }),
    })
    await renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByText('Unrecognized line: ???')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled())
  })

  it('marks a shown preview stale until Parse is pressed again', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
    })
    await renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))
    expect(await screen.findByRole('button', { name: 'Save' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Use example text' }))

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByText('The text changed since the last Parse. Press Parse to apply your edits.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())
    expect(screen.queryByText(/The text changed since the last Parse/)).not.toBeInTheDocument()
  })

  it('restores the full example goals after they were replaced', async () => {
    const user = userEvent.setup()
    mockApi({ 'GET /api/setup': () => jsonResponse(EMPTY_SETUP) })
    await renderSetup()
    const goals = screen.getByLabelText('Goals') as HTMLTextAreaElement
    await user.clear(goals)
    await user.type(goals, 'Hobbies - padel')
    expect(goals.value).toBe('Hobbies - padel')

    await user.click(screen.getByRole('button', { name: 'Use example text' }))

    expect(goals.value).toBe(EXAMPLE_GOALS)
  })

  describe('with a saved setup', () => {
    it('shows it as an editor without the text boxes', async () => {
      mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'GET /api/config': () => jsonResponse(AI_OFF) })

      await renderSetup()

      expect(screen.queryByLabelText('Goals')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Parse' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Remove Gym' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
      expect(screen.queryByRole('button', { name: 'Discard changes' })).not.toBeInTheDocument()
      expect(
        screen.getByText('Days that are already planned keep their plan. Days that are not planned yet use this setup.'),
      ).toBeInTheDocument()
    })

    it('saves a removed goal without its quota, then opens Today', async () => {
      const user = userEvent.setup()
      const fetchMock = mockApi({
        'GET /api/setup': () => jsonResponse(SAVED),
        'GET /api/config': () => jsonResponse(AI_OFF),
        'PUT /api/setup': () => jsonResponse(SAVED),
      })
      await renderSetup()

      await user.click(screen.getByRole('button', { name: 'Remove Gym' }))
      await user.click(screen.getByRole('button', { name: 'Save' }))

      expect(await screen.findByText('Today screen')).toBeInTheDocument()
      const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
      expect(JSON.parse(put[1]!.body as string)).toEqual({
        categories: [SAVED.categories[0]],
        goals: [SAVED.goals[0]],
        constraints: [SAVED.constraints[0]],
      })
    })

    it('discards changes', async () => {
      const user = userEvent.setup()
      mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'GET /api/config': () => jsonResponse(AI_OFF) })
      await renderSetup()

      await user.selectOptions(screen.getByLabelText('Priority for Gym'), '5')
      await user.click(screen.getByRole('button', { name: 'Discard changes' }))

      expect(screen.getByLabelText('Priority for Gym')).toHaveValue('3')
      expect(screen.queryByRole('button', { name: 'Discard changes' })).not.toBeInTheDocument()
    })

    it('puts an emptied number back on Discard changes', async () => {
      const user = userEvent.setup()
      mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'GET /api/config': () => jsonResponse(AI_OFF) })
      await renderSetup()

      await user.clear(screen.getByLabelText('Times per week for Gym'))
      await user.click(screen.getByRole('button', { name: 'Discard changes' }))

      expect(screen.getByLabelText('Times per week for Gym')).toHaveValue('4')
    })

    it('blocks Save while a field is invalid', async () => {
      const user = userEvent.setup()
      mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'GET /api/config': () => jsonResponse(AI_OFF) })
      await renderSetup()

      await user.clear(screen.getByLabelText('Times per week for Gym'))

      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    })

    it('keeps the edits when saving fails', async () => {
      const user = userEvent.setup()
      mockApi({
        'GET /api/setup': () => jsonResponse(SAVED),
        'GET /api/config': () => jsonResponse(AI_OFF),
        'PUT /api/setup': () => problemResponse(400, 'Invalid request', 'label must be at most 255 characters'),
      })
      await renderSetup()

      await user.click(screen.getByRole('button', { name: 'Remove Gym' }))
      await user.click(screen.getByRole('button', { name: 'Save' }))

      expect(await screen.findByText('label must be at most 255 characters')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Remove Gym' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
    })

    it('adds goals from the brain dump and lists what it skipped', async () => {
      const user = userEvent.setup()
      const azure = { ...SAVED.goals[0], id: 'azure', name: 'Azure AI', deadline: null }
      const gym = { ...SAVED.goals[1], id: 'gym', priority: 1 }
      const fetchMock = mockApi({
        'GET /api/setup': () => jsonResponse(SAVED),
        'GET /api/config': () => jsonResponse({ aiEnabled: true, ragEnabled: false }),
        'POST /api/setup/extract': () =>
          jsonResponse({ categories: [], goals: [azure, gym], constraints: [], warnings: [] }),
        'PUT /api/setup': () => jsonResponse(SAVED),
      })
      await renderSetup()

      await user.type(await screen.findByLabelText('Describe goals to add in your own words'), 'azure and gym')
      await user.click(screen.getByRole('button', { name: 'Add from text' }))

      const azureRow = (await screen.findByText('Azure AI', { selector: '.goal-name' })).closest('li')!
      expect(within(azureRow).getByText('new')).toBeInTheDocument()
      expect(screen.getByText('Skipped goal "Gym": it already exists.')).toBeInTheDocument()
      expect(screen.getByLabelText('Describe goals to add in your own words')).toHaveValue('')

      await user.click(screen.getByRole('button', { name: 'Save' }))
      await screen.findByText('Today screen')
      const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
      const sent = JSON.parse(put[1]!.body as string)
      expect(sent.goals.map((goal: { id: string }) => goal.id)).toEqual([
        'career-claude-certification',
        'health-gym',
        'career-azure-ai',
      ])
      expect(sent.goals[1]).toEqual(SAVED.goals[1])
    })

    it('keeps adding goals after Discard changes removed the chosen category', async () => {
      const user = userEvent.setup()
      mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'GET /api/config': () => jsonResponse(AI_OFF) })
      await renderSetup()
      await user.type(screen.getByLabelText('New goal name'), 'Guitar')
      await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'New category…')
      await user.type(screen.getByLabelText('New category name'), 'Music')
      await user.click(screen.getByRole('button', { name: 'Add goal' }))
      await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'Music')

      await user.click(screen.getByRole('button', { name: 'Discard changes' }))
      await user.type(screen.getByLabelText('New goal name'), 'Azure AI')
      await user.click(screen.getByRole('button', { name: 'Add goal' }))

      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      const career = screen.getByRole('heading', { name: 'Career' }).parentElement!
      expect(within(career).getByText('Azure AI')).toBeInTheDocument()
    })

    it('cannot save a setup without goals', async () => {
      const user = userEvent.setup()
      mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'GET /api/config': () => jsonResponse(AI_OFF) })
      await renderSetup()

      await user.click(screen.getByRole('button', { name: 'Remove Claude certification' }))
      await user.click(screen.getByRole('button', { name: 'Remove Gym' }))

      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
      expect(screen.getByText('Keep at least one goal to save.')).toBeInTheDocument()
    })

    it('shows the error when adding from text fails', async () => {
      const user = userEvent.setup()
      mockApi({
        'GET /api/setup': () => jsonResponse(SAVED),
        'GET /api/config': () => jsonResponse({ aiEnabled: true, ragEnabled: false }),
        'POST /api/setup/extract': () =>
          problemResponse(502, 'Assistant unavailable', 'The assistant is unavailable right now'),
      })
      await renderSetup()

      await user.type(await screen.findByLabelText('Describe goals to add in your own words'), 'chess')
      await user.click(screen.getByRole('button', { name: 'Add from text' }))

      expect(await screen.findByText('The assistant is unavailable right now')).toBeInTheDocument()
    })
  })

  it('opens the first-visit flow when the saved setup cannot be loaded', async () => {
    mockApi({ 'GET /api/setup': () => problemResponse(500, 'Server error', 'boom') })

    await renderSetup()

    expect(screen.getByLabelText('Goals')).toHaveValue(EXAMPLE_GOALS)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  describe('brain dump', () => {
    const AI_ON = { aiEnabled: true, ragEnabled: false }

    it('is hidden when the assistant is not configured', async () => {
      mockApi({
        'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
        'GET /api/config': () => jsonResponse({ aiEnabled: false, ragEnabled: false }),
      })

      await renderSetup()

      await waitFor(() => expect(screen.getByLabelText('Goals')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: 'Extract goals and constraints' })).not.toBeInTheDocument()
    })

    it('extracts into the preview and sends the text', async () => {
      const user = userEvent.setup()
      const fetchMock = mockApi({
        'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
        'GET /api/config': () => jsonResponse(AI_ON),
        'POST /api/setup/extract': () => jsonResponse(PARSED),
      })
      await renderSetup()
      const button = await screen.findByRole('button', { name: 'Extract goals and constraints' })
      expect(button).toBeDisabled()

      await user.type(screen.getByLabelText(/in your own words/), 'I want to gym four times a week')
      await user.click(button)

      expect(await screen.findByText('Extracted from your text (not saved yet)')).toBeInTheDocument()
      expect(screen.getByText('Claude certification')).toBeInTheDocument()
      const call = fetchMock.mock.calls.find(([path]) => path === '/api/setup/extract')!
      expect(JSON.parse(call[1]!.body as string)).toEqual({ text: 'I want to gym four times a week' })
      expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
    })

    it('shows the error when extraction fails', async () => {
      const user = userEvent.setup()
      mockApi({
        'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
        'GET /api/config': () => jsonResponse(AI_ON),
        'POST /api/setup/extract': () => problemResponse(502, 'Assistant unavailable', 'The assistant is unavailable right now'),
      })
      await renderSetup()
      await user.type(await screen.findByLabelText(/in your own words/), 'something')

      await user.click(screen.getByRole('button', { name: 'Extract goals and constraints' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('The assistant is unavailable right now')
    })
  })
})
