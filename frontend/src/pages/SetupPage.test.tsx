import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SAVED } from '../test/fixtures'
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

function sentSetup(fetchMock: ReturnType<typeof mockApi>) {
  const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
  return JSON.parse(put[1]!.body as string)
}

describe('SetupPage: first visit', () => {
  it('shows the goal form and the fixed blocks, with no text boxes', async () => {
    mockApi({ 'GET /api/setup': () => jsonResponse(EMPTY_SETUP) })

    await renderSetup()

    expect(screen.getByRole('heading', { name: 'Goals' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Fixed blocks' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /Goals|Constraints|own words/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Parse' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByText('Add at least one goal to save.')).toBeInTheDocument()
  })

  it('saves a goal with its hours, then opens Today', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'PUT /api/setup': () => jsonResponse(SAVED),
    })
    await renderSetup()

    await user.type(screen.getByLabelText('New goal name'), 'Gym')
    await user.type(screen.getByLabelText('New category name'), 'Health')
    await user.type(screen.getByLabelText('Hours per week for the new goal'), '3')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Today screen')).toBeInTheDocument()
    expect(sentSetup(fetchMock)).toEqual({
      categories: [{ id: 'health', name: 'Health' }],
      goals: [
        {
          id: 'health-gym',
          categoryId: 'health',
          name: 'Gym',
          minutesPerWeek: 180,
          defaultBlockMinutes: 60,
          energy: 'MEDIUM',
          preferredTime: 'ANY',
        },
      ],
      constraints: [],
    })
  })

  it('starts empty when the saved setup cannot be loaded', async () => {
    mockApi({ 'GET /api/setup': () => problemResponse(500, 'Server error', 'boom') })

    await renderSetup()

    expect(screen.getByText('No goals yet.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('SetupPage: saved setup', () => {
  it('shows it in the editor', async () => {
    mockApi({ 'GET /api/setup': () => jsonResponse(SAVED) })

    await renderSetup()

    expect(screen.getByLabelText('Hours per week for Gym')).toHaveValue('4')
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Discard changes' })).not.toBeInTheDocument()
    expect(
      screen.getByText('Days that are already planned keep their plan. Days that are not planned yet use this setup.'),
    ).toBeInTheDocument()
  })

  it('saves changed hours, then opens Today', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'PUT /api/setup': () => jsonResponse(SAVED) })
    await renderSetup()

    await user.clear(screen.getByLabelText('Hours per week for Gym'))
    await user.type(screen.getByLabelText('Hours per week for Gym'), '2.5')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Today screen')).toBeInTheDocument()
    expect(sentSetup(fetchMock).goals[1]).toEqual({ ...SAVED.goals[1], minutesPerWeek: 150 })
  })

  it('saves a removed goal and drops its empty category', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({ 'GET /api/setup': () => jsonResponse(SAVED), 'PUT /api/setup': () => jsonResponse(SAVED) })
    await renderSetup()

    await user.click(screen.getByRole('button', { name: 'Remove Gym' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByText('Today screen')
    expect(sentSetup(fetchMock)).toEqual({
      categories: [SAVED.categories[0]],
      goals: [SAVED.goals[0]],
      constraints: SAVED.constraints,
    })
  })

  it('discards changes, an emptied hours field included', async () => {
    const user = userEvent.setup()
    mockApi({ 'GET /api/setup': () => jsonResponse(SAVED) })
    await renderSetup()

    await user.clear(screen.getByLabelText('Hours per week for Gym'))
    await user.click(screen.getByRole('button', { name: 'Discard changes' }))

    expect(screen.getByLabelText('Hours per week for Gym')).toHaveValue('4')
    expect(screen.queryByRole('button', { name: 'Discard changes' })).not.toBeInTheDocument()
  })

  it('blocks Save while hours are invalid', async () => {
    const user = userEvent.setup()
    mockApi({ 'GET /api/setup': () => jsonResponse(SAVED) })
    await renderSetup()

    await user.clear(screen.getByLabelText('Hours per week for Gym'))
    await user.type(screen.getByLabelText('Hours per week for Gym'), '1.3')

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('keeps the edits when saving fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(SAVED),
      'PUT /api/setup': () => problemResponse(400, 'Invalid request', 'label must be at most 255 characters'),
    })
    await renderSetup()

    await user.click(screen.getByRole('button', { name: 'Remove Gym' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('label must be at most 255 characters')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove Gym' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('keeps adding goals after Discard changes removed the chosen category', async () => {
    const user = userEvent.setup()
    mockApi({ 'GET /api/setup': () => jsonResponse(SAVED) })
    await renderSetup()
    await user.type(screen.getByLabelText('New goal name'), 'Guitar')
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'New category…')
    await user.type(screen.getByLabelText('New category name'), 'Music')
    await user.type(screen.getByLabelText('Hours per week for the new goal'), '1')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))
    await user.selectOptions(screen.getByLabelText('Category for the new goal'), 'Music')

    await user.click(screen.getByRole('button', { name: 'Discard changes' }))
    await user.type(screen.getByLabelText('New goal name'), 'Azure AI')
    await user.type(screen.getByLabelText('Hours per week for the new goal'), '1')
    await user.click(screen.getByRole('button', { name: 'Add goal' }))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    const career = screen.getByRole('heading', { name: 'Career' }).parentElement!
    expect(within(career).getByText('Azure AI')).toBeInTheDocument()
  })

  it('cannot save a setup without goals', async () => {
    const user = userEvent.setup()
    mockApi({ 'GET /api/setup': () => jsonResponse(SAVED) })
    await renderSetup()

    await user.click(screen.getByRole('button', { name: 'Remove Claude certification' }))
    await user.click(screen.getByRole('button', { name: 'Remove Gym' }))

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByText('Add at least one goal to save.')).toBeInTheDocument()
  })
})
