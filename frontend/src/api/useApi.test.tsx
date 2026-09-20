import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { useApi } from './useApi'

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  const promise = new Promise<T>((done) => (resolve = done))
  return { promise, resolve }
}

function Probe({ load }: { load: () => Promise<string> }) {
  const { data, loading } = useApi(load)
  return <p>{loading ? 'loading' : data}</p>
}

describe('useApi', () => {
  it('starts loading, then exposes the data', async () => {
    const load = vi.fn().mockResolvedValue('plan')

    const { result } = renderHook(() => useApi(load))

    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toBe('plan')
    expect(result.current.error).toBeNull()
  })

  it('exposes the error and no data when the call fails', async () => {
    const failure = new Error('boom')
    const load = vi.fn().mockRejectedValue(failure)

    const { result } = renderHook(() => useApi(load))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe(failure)
    expect(result.current.data).toBeNull()
  })

  it('keeps the old data and exposes the error when a refetch fails', async () => {
    const failure = new Error('offline')
    const load = vi.fn().mockResolvedValueOnce('first').mockRejectedValueOnce(failure)
    const { result } = renderHook(() => useApi(load))
    await waitFor(() => expect(result.current.data).toBe('first'))

    act(() => result.current.refetch())

    await waitFor(() => expect(result.current.error).toBe(failure))
    expect(result.current.data).toBe('first')
    expect(result.current.loading).toBe(false)
  })

  it('a successful refetch after a failed one clears the error and shows the new data', async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce('first')
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce('third')
    const { result } = renderHook(() => useApi(load))
    await waitFor(() => expect(result.current.data).toBe('first'))
    act(() => result.current.refetch())
    await waitFor(() => expect(result.current.error).not.toBeNull())

    act(() => result.current.refetch())

    await waitFor(() => expect(result.current.data).toBe('third'))
    expect(result.current.error).toBeNull()
  })

  it('setData replaces the data without another request', async () => {
    const load = vi.fn().mockResolvedValue('old')
    const { result } = renderHook(() => useApi(load))
    await waitFor(() => expect(result.current.data).toBe('old'))

    act(() => result.current.setData('new'))

    expect(result.current.data).toBe('new')
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('a late load never overwrites data that setData put in after the load started', async () => {
    const late = deferred<string>()
    const load = vi.fn().mockReturnValue(late.promise)
    const { result } = renderHook(() => useApi(load))

    act(() => result.current.setData('action result'))
    await act(async () => {
      late.resolve('old')
    })

    expect(result.current.data).toBe('action result')
    expect(result.current.loading).toBe(false)
  })

  it('refetch loads again', async () => {
    const load = vi.fn().mockResolvedValueOnce('first').mockResolvedValueOnce('second')
    const { result } = renderHook(() => useApi(load))
    await waitFor(() => expect(result.current.data).toBe('first'))

    act(() => result.current.refetch())

    await waitFor(() => expect(result.current.data).toBe('second'))
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('under StrictMode (two loads), keeps the answer of the second load even if the first arrives last', async () => {
    const first = deferred<string>()
    const second = deferred<string>()
    const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    // render() rather than renderHook(): only render() reliably applies the StrictMode double effect
    render(
      <StrictMode>
        <Probe load={load} />
      </StrictMode>,
    )
    expect(load).toHaveBeenCalledTimes(2)
    await act(async () => {
      second.resolve('fresh')
      first.resolve('stale')
    })

    expect(await screen.findByText('fresh')).toBeInTheDocument()
    expect(screen.queryByText('stale')).not.toBeInTheDocument()
  })
})
