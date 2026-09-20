import { afterEach, describe, expect, it, vi } from 'vitest'
import { PARSED, TODAY } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { api, ApiError, errorMessage, isSetupRequired } from './client'

afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('returns the parsed JSON body of a successful call', async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })

    expect(await api.getToday()).toEqual(TODAY)
  })

  it('turns a problem+json answer into an ApiError', async () => {
    mockApi({
      'GET /api/plan/today': () => problemResponse(404, 'Setup required', 'Paste your goals first'),
    })

    const error = await api.getToday().catch((failure: unknown) => failure)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 404, title: 'Setup required', detail: 'Paste your goals first' })
    expect((error as ApiError).message).toBe('Paste your goals first')
  })

  it('falls back to the HTTP status text when the error body is not JSON', async () => {
    mockApi({
      'GET /api/plan/today': () => new Response('<html>bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }),
    })

    const error = await api.getToday().catch((failure: unknown) => failure)

    expect(error).toMatchObject({ status: 502, title: 'Bad Gateway', detail: '' })
  })

  it('posts an event as JSON', async () => {
    const fetchMock = mockApi({
      'POST /api/plan/today/events': () => jsonResponse({ ...TODAY, changes: ['Postponed Gym'] }),
    })

    const result = await api.applyEvent({ type: 'POSTPONE_BLOCK', blockId: 'b1', reason: 'tired' })

    expect(result.changes).toEqual(['Postponed Gym'])
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({ type: 'POSTPONE_BLOCK', blockId: 'b1', reason: 'tired' })
  })

  it('saves the setup with PUT and keeps a missing deadline as null', async () => {
    const fetchMock = mockApi({ 'PUT /api/setup': () => jsonResponse(PARSED) })
    const { warnings: _warnings, ...setup } = PARSED

    await api.saveSetup(setup)

    const sent = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(sent.goals[1].deadline).toBeNull()
  })
})

describe('isSetupRequired', () => {
  it('is true only for the 404 "Setup required" problem', () => {
    expect(isSetupRequired(new ApiError(404, 'Setup required', ''))).toBe(true)
    expect(isSetupRequired(new ApiError(404, 'Not Found', ''))).toBe(false)
    expect(isSetupRequired(new ApiError(500, 'Setup required', ''))).toBe(false)
    expect(isSetupRequired(new TypeError('Failed to fetch'))).toBe(false)
    expect(isSetupRequired(null)).toBe(false)
  })
})

describe('errorMessage', () => {
  it('shows the backend detail for an ApiError', () => {
    expect(errorMessage(new ApiError(400, 'Invalid request', 'priority must be between 1 and 5'))).toBe(
      'priority must be between 1 and 5',
    )
  })

  it('shows the backend detail of a real 500 problem body', () => {
    expect(errorMessage(new ApiError(500, 'Unexpected error', 'Something went wrong'))).toBe('Something went wrong')
  })

  it('falls back to the title when a client error has no detail', () => {
    expect(errorMessage(new ApiError(404, 'Not Found', ''))).toBe('Not Found')
  })

  it('explains an unreachable backend when the dev proxy answers 5xx with no problem body', () => {
    expect(errorMessage(new ApiError(500, 'Internal Server Error', ''))).toMatch(/backend/i)
  })

  it('explains an unreachable backend when fetch itself fails', () => {
    expect(errorMessage(new TypeError('Failed to fetch'))).toMatch(/backend/i)
  })
})
