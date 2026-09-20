import { useEffect } from 'react'

interface Props {
  changes: string[]
  onDismiss: () => void
}

export const TOAST_MILLIS = 6000

/** Shows what the last action changed, then hides itself. Renders nothing for an empty list. */
export function ChangeToast({ changes, onDismiss }: Props) {
  const visible = changes.length > 0

  useEffect(() => {
    if (!visible) return
    const timer = setTimeout(onDismiss, TOAST_MILLIS)
    return () => clearTimeout(timer)
  }, [changes, visible, onDismiss])

  if (!visible) return null
  return (
    <div className="toast" role="status">
      <ul>
        {changes.map((change, index) => (
          <li key={index}>{change}</li>
        ))}
      </ul>
      <button type="button" onClick={onDismiss} aria-label="Dismiss">
        ×
      </button>
    </div>
  )
}
