import { useCallback, useEffect, useState } from 'react'

interface State<T> {
  data: T | null
  error: unknown
  loading: boolean
}

/**
 * Runs `load` when the component mounts and exposes {data, loading, error}.
 * `load` must be a stable function (for example `api.getToday`), not one created on every render.
 * `setData` lets a page replace the data after an action without fetching again.
 */
export function useApi<T>(load: () => Promise<T>) {
  const [state, setState] = useState<State<T>>({ data: null, error: null, loading: true })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    // `cancelled` drops the answer of a request whose component already unmounted or re-ran the effect
    // (React <StrictMode> runs every effect twice in development).
    let cancelled = false
    setState((current) => ({ ...current, error: null, loading: true }))
    load().then(
      (data) => {
        if (!cancelled) setState({ data, error: null, loading: false })
      },
      (error: unknown) => {
        if (!cancelled) setState({ data: null, error, loading: false })
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, attempt])

  const refetch = useCallback(() => setAttempt((n) => n + 1), [])
  const setData = useCallback((data: T) => setState({ data, error: null, loading: false }), [])

  return { ...state, refetch, setData }
}
