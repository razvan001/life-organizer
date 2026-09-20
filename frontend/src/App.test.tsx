import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { TODAY } from './test/fixtures'
import { jsonResponse, mockApi } from './test/mockApi'

afterEach(() => vi.unstubAllGlobals())

describe('App', () => {
  it('shows Today at / with a link to Setup', async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: "Today's plan" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Setup' })).toHaveAttribute('href', '/setup')
    expect(screen.getByRole('link', { name: 'Today' })).toHaveAttribute('href', '/')
  })

  it('shows Setup at /setup', () => {
    mockApi({ 'GET /api/setup': () => jsonResponse({ categories: [], goals: [], constraints: [] }) })

    render(
      <MemoryRouter initialEntries={['/setup']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Setup' })).toBeInTheDocument()
  })
})
