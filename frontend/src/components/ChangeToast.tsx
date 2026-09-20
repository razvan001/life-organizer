import { useEffect } from 'react'

interface Props {
  changes: string[]
  onDismiss: () => void
}

export const TOAST_MILLIS = 6000

/** Shows what the last action changed, then hides itself. For an empty list only an invisible live region remains. */
export function ChangeToast({ changes, onDismiss }: Props) {
  const visible = changes.length > 0

  useEffect(() => {
    if (!visible) return
    const timer = setTimeout(onDismiss, TOAST_MILLIS)
    return () => clearTimeout(timer)
  }, [changes, visible, onDismiss])

  // The live region stays in the page even when empty: screen readers announce text that is added
  // to an existing region, but often stay silent about a region that appears together with its text.
  return (
    <div role="status" aria-live="polite" className={visible ? 'toast' : 'visually-hidden'}>
      {visible && (
        <>
          <ul>
            {changes.map((change, index) => (
              <li key={index}>{change}</li>
            ))}
          </ul>
          <button type="button" onClick={onDismiss} aria-label="Dismiss">
            ×
          </button>
        </>
      )}
    </div>
  )
}
