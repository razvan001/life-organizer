import type { BlockDto, PlanEventRequest } from '../api/types'
import { BlockCard } from './BlockCard'

interface Props {
  blocks: BlockDto[]
  busy: boolean
  onAction: (event: PlanEventRequest) => void
}

/** Blocks in time order. "HH:mm" strings sort correctly as text; the sort is stable for equal starts. */
export function Timeline({ blocks, busy, onAction }: Props) {
  const sorted = [...blocks].sort((a, b) => a.start.localeCompare(b.start))
  return (
    <ol className="timeline">
      {sorted.map((block) => (
        <BlockCard key={block.id} block={block} busy={busy} onAction={onAction} />
      ))}
    </ol>
  )
}
