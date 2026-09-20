import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import { Timeline } from './Timeline'

describe('Timeline', () => {
  it('lists blocks in start-time order whatever order they arrive in', () => {
    const blocks = [
      makeBlock({ id: 'c', start: '18:00', title: 'Evening' }),
      makeBlock({ id: 'a', start: '07:00', title: 'Morning' }),
      makeBlock({ id: 'b', start: '08:30', title: 'Midday' }),
    ]

    render(<Timeline blocks={blocks} busy={false} onAction={vi.fn()} />)

    const titles = screen.getAllByRole('listitem').map((item) => item.querySelector('.block-title')?.textContent)
    expect(titles).toEqual(['Morning', 'Midday', 'Evening'])
  })

  it('does not reorder the array it was given', () => {
    const blocks = [makeBlock({ id: 'b', start: '18:00' }), makeBlock({ id: 'a', start: '07:00' })]

    render(<Timeline blocks={blocks} busy={false} onAction={vi.fn()} />)

    expect(blocks.map((block) => block.id)).toEqual(['b', 'a'])
  })
})
