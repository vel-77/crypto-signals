/**
 * Mirrors the backend's price rendering: derive the decimal count from the magnitude so the same
 * component reads correctly for a $100,000 level and a $0.000001 one.
 */
export function price(value: string | number): string {
  const n = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(n) || n === 0) return String(value)
  const magnitude = Math.floor(Math.log10(Math.abs(n)))
  const decimals = Math.max(0, Math.min(12, 9 - magnitude - 1))
  return n.toLocaleString('en-US', { maximumFractionDigits: decimals })
}

export function timestamp(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toISOString().slice(0, 16).replace('T', ' ')
}

export function percent(value: number, digits = 2): string {
  return `${value.toFixed(digits)}%`
}

export function duration(millis: number): string {
  if (millis < 0) return '—'
  const minutes = Math.round(millis / 60000)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours}h`
  return `${Math.round(hours / 24)}d`
}
