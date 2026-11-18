import type { Candle, Level, Page, Signal, WatchList } from './types'

const BASE = import.meta.env.VITE_API_BASE ?? '/api'

export type QueryValue = string | number | string[] | undefined

/**
 * Drops empty filter values so the backend sees an absent parameter rather than a blank one.
 * A list becomes one comma-separated value, which the API treats as "any of these".
 */
function query(params: Record<string, QueryValue>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) {
      if (value.length > 0) search.set(key, value.join(','))
    } else {
      search.set(key, String(value))
    }
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}

async function get<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE}${path}`)
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`
    try {
      const body = await response.json()
      if (body?.message) message = body.message
    } catch {
      // Body was not the structured error shape; the status line is the best we have.
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

/**
 * Every list, chart, status and config call names the market it is about (BINANCE_FUTURES or
 * BINANCE); without one the server answers for the first market it runs.
 */
export const api = {
  signals: (f: Record<string, QueryValue>) =>
    get<Page<Signal>>(`/signals${query(f)}`),

  signal: (id: string) => get<Signal>(`/signals/${id}`),

  levels: (f: Record<string, QueryValue>) =>
    get<Page<Level>>(`/levels${query(f)}`),

  level: (id: string) => get<Level>(`/levels/${id}`),

  candles: (symbol: string, timeframe: string, limit = 500, exchange?: string) =>
    get<Candle[]>(`/candles${query({ exchange, symbol, timeframe, limit })}`),

  config: (exchange?: string) => get<Record<string, unknown>>(`/config${query({ exchange })}`),

  status: (exchange?: string) => get<Record<string, unknown>>(`/status${query({ exchange })}`),

  watchList: (exchange?: string) => get<WatchList>(`/watchlist${query({ exchange })}`),
}
