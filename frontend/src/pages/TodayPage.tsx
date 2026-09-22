import { useCallback, useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api, errorMessage, isSetupRequired } from '../api/client'
import type { ApplyEventsResponse, PlanEventRequest } from '../api/types'
import { useApi } from '../api/useApi'
import { AssistantBox } from '../components/AssistantBox'
import { ChangeToast } from '../components/ChangeToast'
import { Timeline } from '../components/Timeline'

/** How often a shown plan is compared with the calendar day. */
const DAY_CHECK_MILLIS = 60_000

/** The local calendar date as yyyy-MM-dd. (toISOString would give the UTC date, which can be another day.) */
function localDate(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

export function TodayPage() {
  const { data, loading, error, setData, refetch } = useApi(api.getToday)
  const { data: config } = useApi(api.getConfig)
  const [changes, setChanges] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const dismissToast = useCallback(() => setChanges([]), [])

  // A tab can stay open for days. Without this it would keep showing yesterday's plan after midnight,
  // and the buttons would act on yesterday's block ids. So load again when the user comes back to the
  // tab, and once a minute if the plan on screen is not for today's date.
  const shownDate = data?.date
  useEffect(() => {
    function onVisibilityChange() {
      if (document.visibilityState === 'visible') refetch()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    const timer = setInterval(() => {
      if (shownDate && shownDate !== localDate()) refetch()
    }, DAY_CHECK_MILLIS)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      clearInterval(timer)
    }
  }, [refetch, shownDate])

  async function act(event: PlanEventRequest) {
    if (busy) return // one request at a time
    setBusy(true)
    setActionError(null)
    try {
      const result = await api.applyEvent(event)
      setData({ date: result.date, blocks: result.blocks, warnings: result.warnings })
      setChanges(result.changes)
    } catch (failure) {
      setChanges([]) // an older success toast must not sit next to the new error
      setActionError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  function onAssistantApplied(result: ApplyEventsResponse) {
    setData({ date: result.date, blocks: result.blocks, warnings: result.warnings })
    setChanges(result.changes)
  }

  if (isSetupRequired(error)) {
    return <Navigate to="/setup" replace />
  }
  if (loading && !data) {
    return <p>Loading today's plan…</p>
  }
  if (!data) {
    return <p role="alert">{errorMessage(error)}</p>
  }

  return (
    <section>
      <h2>Today's plan</h2>
      <p className="muted">{data.date}</p>
      {error != null && (
        <p className="muted">Could not refresh the plan: {errorMessage(error)}. Showing the last loaded plan.</p>
      )}

      {data.warnings.length > 0 && (
        <ul className="warnings">
          {data.warnings.map((warning, index) => (
            <li key={`${index}-${warning}`}>{warning}</li>
          ))}
        </ul>
      )}

      <Timeline blocks={data.blocks} busy={busy} onAction={act} />
      <ChangeToast changes={changes} onDismiss={dismissToast} />
      {config?.aiEnabled && <AssistantBox currentBlocks={data.blocks} busy={busy} onApplied={onAssistantApplied} />}
      {actionError && (
        // fixed at the bottom of the window, where the user's eyes are after pressing a block's button
        <div className="toast toast-error" role="alert">
          <span>{actionError}</span>
          <button type="button" onClick={() => setActionError(null)} aria-label="Dismiss error">
            ×
          </button>
        </div>
      )}
    </section>
  )
}
