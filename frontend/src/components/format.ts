import { formatInZone } from '../timezone'

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

/** Decimals a chart axis needs to show a price meaningfully: 2 for BTC, 7 for DOGE. */
export function chartPrecision(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 2
  return Math.max(2, Math.min(8, 5 - Math.floor(Math.log10(value))))
}

/** An ISO instant shown in the viewer's time zone. */
export function timestamp(iso: string | null | undefined, zone: string): string {
  if (!iso) return '—'
  return formatInZone(Date.parse(iso), zone)
}

/**
 * When something found on a candle became known: that candle's close. The server records events
 * by the open time of their candle, which is where the chart draws them; but a signal on the 14:00
 * 1h candle only fires at 15:00, and showing 14:00 as "detected" reads like a clock an hour off.
 */
export function knownAt(iso: string | null | undefined, timeframe: string, zone: string): string {
  if (!iso) return '—'
  return formatInZone(Date.parse(iso) + (TIMEFRAME_MILLIS[timeframe] ?? 0), zone)
}

export function percent(value: number, digits = 2): string {
  return `${value.toFixed(digits)}%`
}

export function duration(millis: number): string {
  if (millis < 0) return '—'
  const minutes = Math.round(millis / 60000)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours < 48) return rest ? `${hours}h ${rest}m` : `${hours}h`
  const days = Math.floor(hours / 24)
  const hrs = hours % 24
  return hrs ? `${days}d ${hrs}h` : `${days}d`
}

export const TIMEFRAME_MILLIS: Record<string, number> = {
  '1m': 60_000, '3m': 180_000, '5m': 300_000, '15m': 900_000, '30m': 1_800_000,
  '1h': 3_600_000, '2h': 7_200_000, '4h': 14_400_000, '6h': 21_600_000,
  '12h': 43_200_000, '1d': 86_400_000, '3d': 259_200_000, '1w': 604_800_000,
}
