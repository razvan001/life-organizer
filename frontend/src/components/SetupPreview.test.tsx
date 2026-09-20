import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PARSED } from '../test/fixtures'
import { SetupPreview } from './SetupPreview'

afterEach(() => vi.restoreAllMocks())

describe('SetupPreview', () => {
  it('lists two identical warnings as two items without a duplicate-key error', () => {
    // React reports duplicate list keys through console.error
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(<SetupPreview value={{ ...PARSED, warnings: ['Unrecognized line: ???', 'Unrecognized line: ???'] }} onChange={vi.fn()} />)

    expect(screen.getAllByText('Unrecognized line: ???')).toHaveLength(2)
    expect(consoleError).not.toHaveBeenCalled()
  })
})
