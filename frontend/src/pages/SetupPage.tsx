import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import { useApi } from '../api/useApi'
import type { SetupParseResponse, SetupPayload } from '../api/types'
import { SetupEditor } from '../components/SetupEditor'
import { SetupPreview } from '../components/SetupPreview'
import { type DraftSetup, hasProblems, isChanged, toDraft, toPayload } from '../setup/editSetup'
import { EXAMPLE_CONSTRAINTS, EXAMPLE_GOALS } from '../setup/example'
import { mergeExtraction } from '../setup/mergeExtraction'

const PLANNED_DAYS_NOTE = 'Days that are already planned keep their plan. Days that are not planned yet use this setup.'

/**
 * Without a saved setup, the user pastes or describes one and saves it (FirstSetup). Once one is saved, the page
 * is an editor for it (SavedSetup): no text boxes, every change in place, one Save.
 */
export function SetupPage() {
  // undefined while loading, null when there is nothing saved (or it could not be loaded)
  const [saved, setSaved] = useState<SetupPayload | null | undefined>(undefined)
  const { data: config } = useApi(api.getConfig)
  const aiEnabled = config?.aiEnabled ?? false

  // A failure here is not worth an error message: the first-visit flow reports an unreachable backend the moment
  // Parse is used.
  useEffect(() => {
    let cancelled = false
    api.getSetup().then(
      (setup) => {
        if (!cancelled) setSaved(setup.goals.length > 0 ? setup : null)
      },
      () => {
        if (!cancelled) setSaved(null)
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <section>
      <h2>Setup</h2>
      {saved === undefined && <p className="muted">Loading…</p>}
      {saved === null && <FirstSetup aiEnabled={aiEnabled} />}
      {saved && <SavedSetup saved={saved} aiEnabled={aiEnabled} />}
    </section>
  )
}

function FirstSetup({ aiEnabled }: { aiEnabled: boolean }) {
  const navigate = useNavigate()
  const [goalsText, setGoalsText] = useState(EXAMPLE_GOALS)
  const [constraintsText, setConstraintsText] = useState(EXAMPLE_CONSTRAINTS)
  const [preview, setPreview] = useState<SetupParseResponse | null>(null)
  const [previewTitle, setPreviewTitle] = useState('')
  const [dumpText, setDumpText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // True when a text box was edited after the last Parse: the preview no longer matches the text.
  const [stale, setStale] = useState(false)
  // Counts edits of either text box. A ref, not state: changing it must not re-render the page.
  const textEdits = useRef(0)

  // Every change of the text goes through here: it marks a shown preview as no longer matching the text.
  function markTextEdited() {
    textEdits.current++
    if (preview) setStale(true)
  }

  function editGoals(text: string) {
    markTextEdited()
    setGoalsText(text)
  }

  function editConstraints(text: string) {
    markTextEdited()
    setConstraintsText(text)
  }

  // Local only: the saved setup changes when Parse and then Save are pressed.
  function useExample() {
    markTextEdited()
    setGoalsText(EXAMPLE_GOALS)
    setConstraintsText(EXAMPLE_CONSTRAINTS)
  }

  async function parse() {
    const editsAtStart = textEdits.current
    setBusy(true)
    setError(null)
    try {
      setPreview(await api.parseSetup({ goalsText, constraintsText }))
      setPreviewTitle('Parsed setup (not saved yet)')
      // If the user kept typing while the request ran, the preview belongs to older text.
      setStale(textEdits.current !== editsAtStart)
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  // A second way to fill the preview, next to Parse. Nothing is saved until Save.
  async function extract() {
    setBusy(true)
    setError(null)
    try {
      setPreview(await api.extractSetup({ text: dumpText }))
      setPreviewTitle('Extracted from your text (not saved yet)')
      setStale(false)
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    if (!preview) return
    setBusy(true)
    setError(null)
    try {
      await api.saveSetup({
        categories: preview.categories,
        goals: preview.goals,
        constraints: preview.constraints,
      })
      navigate('/')
    } catch (failure) {
      setError(errorMessage(failure))
      setBusy(false)
    }
  }

  return (
    <>
      <div className="field">
        <label htmlFor="goals-text">Goals</label>
        <textarea id="goals-text" rows={16} value={goalsText} onChange={(event) => editGoals(event.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="constraints-text">Constraints (one per line)</label>
        <textarea
          id="constraints-text"
          rows={7}
          value={constraintsText}
          onChange={(event) => editConstraints(event.target.value)}
        />
      </div>

      <button type="button" disabled={busy || goalsText.trim() === ''} onClick={parse}>
        Parse
      </button>{' '}
      <button type="button" disabled={busy} onClick={useExample}>
        Use example text
      </button>

      {aiEnabled && (
        <div className="field">
          <label htmlFor="brain-dump">Or describe your goals and constraints in your own words</label>
          <textarea
            id="brain-dump"
            rows={6}
            maxLength={4000}
            value={dumpText}
            onChange={(event) => setDumpText(event.target.value)}
          />
          <button type="button" disabled={busy || dumpText.trim() === ''} onClick={extract}>
            Extract goals and constraints
          </button>
        </div>
      )}
      {error && <p role="alert">{error}</p>}

      {preview && (
        <>
          <h3>{previewTitle}</h3>
          <SetupPreview value={preview} onChange={setPreview} />
          <p className="muted">{PLANNED_DAYS_NOTE}</p>
          {stale && (
            <p className="muted">The text changed since the last Parse. Press Parse to apply your edits.</p>
          )}
          <button type="button" disabled={busy || stale || preview.goals.length === 0} onClick={save}>
            Save
          </button>
        </>
      )}
    </>
  )
}

function SavedSetup({ saved, aiEnabled }: { saved: SetupPayload; aiEnabled: boolean }) {
  const navigate = useNavigate()
  const [draft, setDraft] = useState<DraftSetup>(() => toDraft(saved))
  const [newGoalIds, setNewGoalIds] = useState<string[]>([])
  const [warnings, setWarnings] = useState<string[]>([])
  const [dumpText, setDumpText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function discard() {
    setDraft(toDraft(saved))
    setNewGoalIds([])
    setWarnings([])
    setError(null)
  }

  // Adds to the setup on screen; never replaces it. Nothing is saved until Save.
  async function addFromText() {
    setBusy(true)
    setError(null)
    try {
      const result = mergeExtraction(draft, await api.extractSetup({ text: dumpText }))
      setDraft(result.draft)
      setNewGoalIds((ids) => [...ids, ...result.addedGoalIds])
      setWarnings(result.warnings)
      setDumpText('')
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
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
        warnings={warnings}
        disabled={busy}
      />

      {aiEnabled && (
        <div className="field">
          <label htmlFor="brain-dump">Describe goals to add in your own words</label>
          <textarea
            id="brain-dump"
            rows={4}
            maxLength={4000}
            value={dumpText}
            disabled={busy}
            onChange={(event) => setDumpText(event.target.value)}
          />
          <button type="button" disabled={busy || dumpText.trim() === ''} onClick={addFromText}>
            Add from text
          </button>
        </div>
      )}
      {error && <p role="alert">{error}</p>}

      <p className="muted">{PLANNED_DAYS_NOTE}</p>
      {/* without goals the next visit would open the first-visit flow and hide the saved constraints */}
      {draft.goals.length === 0 && <p className="muted">Keep at least one goal to save.</p>}
      <button type="button" disabled={busy || hasProblems(draft) || draft.goals.length === 0} onClick={save}>
        Save
      </button>
      {isChanged(draft, saved) && (
        <>
          {' '}
          <button type="button" disabled={busy} onClick={discard}>
            Discard changes
          </button>
        </>
      )}
    </>
  )
}
