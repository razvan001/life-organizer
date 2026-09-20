// The example from the design spec. It prefills the Setup page so a first visit works with one click.

export const EXAMPLE_GOALS = [
  'Goals',
  '├── Career',
  '│   ├── Claude certification',
  '│   ├── Azure AI',
  '│   └── System design',
  '├── Projects',
  '│   └── RAG showcase',
  '├── Health',
  '│   ├── Gym',
  '│   └── Padel / football',
  '├── Social',
  '│   └── Dating / events',
  '├── Finance',
  '│   └── Investing',
  '└── Hobbies',
  '    └── Chess',
].join('\n')

export const EXAMPLE_CONSTRAINTS = [
  'Work:       08:30-17:00',
  'Gym:        4x/week',
  'Sleep:      23:30-07:00',
  'Learning:   5h/week',
  'Side project: 4h/week',
  'Social:     2 evenings/week',
].join('\n')
