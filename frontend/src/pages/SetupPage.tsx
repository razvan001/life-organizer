import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { SetupPayload } from '../api/types'
import { SetupEditor } from '../components/SetupEditor'
import { type DraftSetup, EMPTY_SETUP, hasProblems, isChanged, toDraft, toPayload } from '../setup/editSetup'

const PLANNED_DAYS_NOTE = 'Days that are already planned keep their plan. Days that are not planned yet use this setup.'

/** The setup as one editor: goals with their hours per week, and fixed blocks. Empty on a first visit. */
export function SetupPage() {
  // undefined while loading
  const [saved, setSaved] = useState<SetupPayload | undefined>(undefined)

  // A failure here is not worth an error message: the page starts empty, and Save reports an unreachable backend.
  useEffect(() => {
    let cancelled = false
    api.getSetup().then(
      (setup) => {
        if (!cancelled) setSaved(setup)
      },
      () => {
        if (!cancelled) setSaved(EMPTY_SETUP)
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <section>
      <h2>Setup</h2>
      {saved === undefined ? <p className="muted">Loading…</p> : <SetupForm saved={saved} />}
    </section>
  )
}

function SetupForm({ saved }: { saved: SetupPayload }) {
  const navigate = useNavigate()
  const [draft, setDraft] = useState<DraftSetup>(() => toDraft(saved))
  const [newGoalIds, setNewGoalIds] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function discard() {
    setDraft(toDraft(saved))
    setNewGoalIds([])
    setError(null)
  }

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await api.saveSetup(toPayload(draft))
      navigate('/')
    } catch (failure) {
      setError(errorMessage(failure))
      setBusy(false)
    }
  }

  return (
    <>
      <SetupEditor
        draft={draft}
        onChange={setDraft}
        onGoalAdded={(goalId) => setNewGoalIds((ids) => [...ids, goalId])}
        newGoalIds={newGoalIds}
        disabled={busy}
      />
      {error && <p role="alert">{error}</p>}

      <p className="muted">{PLANNED_DAYS_NOTE}</p>
      {/* without goals there is nothing to plan */}
      {draft.goals.length === 0 && <p className="muted">Add at least one goal to save.</p>}
      <button
        type="button"
        className="btn-primary"
        disabled={busy || hasProblems(draft) || draft.goals.length === 0}
        onClick={save}
      >
        Save
      </button>
      {isChanged(draft, saved) && (
        <>
          {' '}
          <button type="button" className="btn-danger" disabled={busy} onClick={discard}>
            Discard changes
          </button>
        </>
      )}
    </>
  )
}
