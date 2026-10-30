import { useCallback, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { MemoryResult, NoteDto } from '../api/types'
import { useApi } from '../api/useApi'
import { ChangeToast } from '../components/ChangeToast'
import './NotesPage.css'

interface Draft {
  id: number | null
  title: string
  body: string
}

const EMPTY: Draft = { id: null, title: '', body: '' }

// Same limits the backend enforces (NoteService).
const TITLE_MAX = 255
const BODY_MAX = 500

/** Which fields the user has left at least once; a "required" message only shows for those. */
interface Touched {
  title: boolean
  body: boolean
}

const UNTOUCHED: Touched = { title: false, body: false }

const STICKY_COLORS = ['yellow', 'pink', 'green'] as const
const STICKY_TILTS = 4

/** A note keeps its colour and tilt: both come from its id, not from its position in the list. */
function stickyClasses(id: number, open: boolean): string {
  const color = STICKY_COLORS[id % STICKY_COLORS.length]
  return `sticky sticky-${color} sticky-tilt-${id % STICKY_TILTS}${open ? ' selected' : ''}`
}

function toDraft(note: NoteDto): Draft {
  return { id: note.id, title: note.title, body: note.body }
}

/** Notes you write yourself, plus semantic search over them and over past days. */
export function NotesPage() {
  const { data: notes, error: loadError, refetch } = useApi(api.listNotes)
  const [draft, setDraft] = useState<Draft>(EMPTY)
  const [saved, setSaved] = useState<Draft>(EMPTY)
  const [touched, setTouched] = useState<Touched>(UNTOUCHED)
  // Confirmation of the last save or delete, shown in the same toast the Today page uses.
  const [notice, setNotice] = useState<string[]>([])
  // Stable, so the toast's auto-hide timer is not restarted on every render.
  const dismissNotice = useCallback(() => setNotice([]), [])
  const titleMissing = draft.title.trim() === ''
  const bodyMissing = draft.body.trim() === ''
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<MemoryResult[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  function search() {
    return run(async () => setResults(await api.searchMemory(query.trim())))
  }

  function isDirty() {
    return draft.title !== saved.title || draft.body !== saved.body
  }

  function replaceDraft(next: Draft) {
    if (isDirty() && !window.confirm('Discard unsaved changes?')) return
    setDraft(next)
    setSaved(next)
    setTouched(UNTOUCHED)
  }

  function save() {
    return run(async () => {
      const request = { title: draft.title, body: draft.body }
      const creating = draft.id === null
      const savedNote = creating ? await api.createNote(request) : await api.updateNote(draft.id!, request)
      // A new note goes to the list and the form is cleared for the next one, so a second Save never
      // silently overwrites the first. An edited note stays open.
      const next = creating ? EMPTY : toDraft(savedNote)
      setDraft(next)
      setSaved(next)
      setTouched(UNTOUCHED)
      setNotice([`Note "${savedNote.title}" ${creating ? 'saved' : 'updated'}`])
      refetch()
    })
  }

  function remove() {
    if (draft.id === null || !window.confirm('Delete this note?')) return
    const id = draft.id
    const title = saved.title
    return run(async () => {
      await api.deleteNote(id)
      setDraft(EMPTY)
      setSaved(EMPTY)
      setTouched(UNTOUCHED)
      setNotice([`Note "${title}" deleted`])
      refetch()
    })
  }

  function open(id: string) {
    const note = notes?.find((candidate) => String(candidate.id) === id)
    if (note) {
      replaceDraft(toDraft(note))
    } else {
      setError('That note no longer exists.')
    }
  }

  return (
    <section>
      <h2>Notes</h2>

      <form
        role="search"
        className="memory-search"
        onSubmit={(event) => {
          event.preventDefault()
          search()
        }}
      >
        {/* the hint lives in the placeholder; the label stays for screen readers */}
        <label htmlFor="memory-query" className="visually-hidden">
          Search your notes and past days
        </label>
        <div className="search-field">
          <svg className="search-icon" aria-hidden="true" viewBox="0 0 24 24" width="18" height="18">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            id="memory-query"
            type="search"
            maxLength={500}
            placeholder="Search your notes and past days"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <button className="btn-primary" type="submit" disabled={busy || query.trim() === ''}>
          Search
        </button>
      </form>
      {error && <p role="alert">{error}</p>}

      {results && results.length === 0 && <p className="muted">Nothing found.</p>}
      {results && results.length > 0 && (
        <div className="card">
          <ul className="memory-results" aria-label="Search results">
            {results.map((result) => (
              <li key={`${result.sourceType}-${result.sourceId}-${result.snippet}`}>
                <span className="badge">{result.sourceType === 'NOTE' ? 'Note' : 'Day'}</span>{' '}
                <strong>{result.title}</strong> <span className="muted">{result.score.toFixed(2)}</span>
                <p>{result.snippet}</p>
                {result.sourceType === 'NOTE' && (
                  <button className="btn-small" type="button" disabled={busy} onClick={() => open(result.sourceId)}>
                    Open
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      <h3>Your notes</h3>
      {loadError != null && notes === null && <p role="alert">{errorMessage(loadError)}</p>}
      {notes && notes.length === 0 && <p className="muted">No notes yet.</p>}
      {notes && notes.length > 0 && (
        <ul className="sticky-board" aria-label="Your notes">
          {notes.map((note) => (
            <li key={note.id} className={stickyClasses(note.id, note.id === draft.id)}>
              {/* the accessible name is the title alone; the preview and date are there to look at */}
              <button
                type="button"
                className="sticky-card"
                aria-label={note.title}
                disabled={busy}
                aria-current={note.id === draft.id ? 'true' : undefined}
                onClick={() => replaceDraft(toDraft(note))}
              >
                <span className="sticky-pin" aria-hidden="true" />
                <span className="sticky-title">{note.title}</span>
                <span className="sticky-body">{note.body}</span>
                <span className="sticky-date">{new Date(note.updatedAt).toLocaleDateString()}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="card">
        <h3>{draft.id === null ? 'New note' : 'Edit note'}</h3>
        <div className="field">
          <label htmlFor="note-title">Title</label>
          <input
            id="note-title"
            type="text"
            maxLength={TITLE_MAX}
            value={draft.title}
            disabled={busy}
            aria-invalid={touched.title && titleMissing}
            aria-describedby="note-title-help"
            onChange={(e) => setDraft({ ...draft, title: e.target.value })}
            onBlur={() => setTouched({ ...touched, title: true })}
          />
          <div id="note-title-help" className="field-help">
            {touched.title && titleMissing && <span className="field-error">Title is required.</span>}
            <span className="muted counter">
              {draft.title.length} / {TITLE_MAX}
            </span>
          </div>
        </div>
        <div className="field">
          <label htmlFor="note-body">Body</label>
          <textarea
            id="note-body"
            rows={6}
            maxLength={BODY_MAX}
            value={draft.body}
            disabled={busy}
            aria-invalid={touched.body && bodyMissing}
            aria-describedby="note-body-help"
            onChange={(e) => setDraft({ ...draft, body: e.target.value })}
            onBlur={() => setTouched({ ...touched, body: true })}
          />
          <div id="note-body-help" className="field-help">
            {touched.body && bodyMissing && <span className="field-error">Body is required.</span>}
            <span className="muted counter">
              {draft.body.length} / {BODY_MAX}
            </span>
          </div>
        </div>
        <button className="btn-primary" type="button" disabled={busy || titleMissing || bodyMissing} onClick={save}>
          Save note
        </button>{' '}
        {/* empties the form; on an open note it also closes it, so the form is back to a new note */}
        <button
          type="button"
          disabled={busy || (draft.id === null && draft.title === '' && draft.body === '')}
          onClick={() => replaceDraft(EMPTY)}
        >
          Clear
        </button>{' '}
        {draft.id !== null && (
          <button className="btn-danger" type="button" disabled={busy} onClick={remove}>
            Delete note
          </button>
        )}
      </div>
      <ChangeToast changes={notice} onDismiss={dismissNotice} />
    </section>
  )
}
