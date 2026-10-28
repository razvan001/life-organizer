// Ids follow the backend's Slug.of exactly, so a goal added here gets the id the text parser would have made.

export function slug(text: string): string {
  const result = text
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return result === '' ? 'item' : result
}

export function categoryIdFor(name: string): string {
  return slug(name)
}

export function goalIdFor(categoryId: string, goalName: string): string {
  return `${categoryId}-${slug(goalName)}`
}
