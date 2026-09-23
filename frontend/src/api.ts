import type { Candle, Level, Page, Signal } from './types'

const BASE = import.meta.env.VITE_API_BASE ?? '/api'

/** Drops empty filter values so the backend sees an absent parameter rather than a blank one. */
function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '' && value !== null) {
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

export const api = {
  signals: (f: Record<string, string | number | undefined>) =>
    get<Page<Signal>>(`/signals${query(f)}`),

  signal: (id: string) => get<Signal>(`/signals/${id}`),

  levels: (f: Record<string, string | number | undefined>) =>
    get<Page<Level>>(`/levels${query(f)}`),

  level: (id: string) => get<Level>(`/levels/${id}`),

  candles: (symbol: string, timeframe: string, limit = 500) =>
    get<Candle[]>(`/candles${query({ symbol, timeframe, limit })}`),

  config: () => get<Record<string, unknown>>('/config'),

  status: () => get<Record<string, unknown>>('/status'),
}
