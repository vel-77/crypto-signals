import { createContext, useContext } from 'react'

/** A market the server runs, as GET /api/config lists it under `exchanges`, the default first. */
export interface Venue {
  name: string
  label: string
  minDailyVolume?: number
}

/**
 * Where the chart reads its candles in the browser: the market picked in the header, which the
 * server also analyses, so the chart shows the prices the levels were found on.
 */
export interface ChartFeed {
  exchange: string
  /** For people, e.g. under the chart. */
  label: string
  /** One word for the header switch. */
  short: string
  /** REST klines endpoint; absent means the chart shows the server's stored candles only. */
  klines?: string
  /** Websocket base: `${stream}/${symbol}@kline_${timeframe}`. */
  stream?: string
}

const FEEDS: Record<string, ChartFeed> = {
  BINANCE: {
    exchange: 'BINANCE',
    label: 'Binance spot',
    short: 'Spot',
    klines: 'https://api.binance.com/api/v3/klines',
    stream: 'wss://stream.binance.com:9443/ws',
  },
  BINANCE_FUTURES: {
    exchange: 'BINANCE_FUTURES',
    label: 'Binance USDⓈ-M futures',
    short: 'Futures',
    klines: 'https://fapi.binance.com/fapi/v1/klines',
    // Futures market streams live under /market; the bare host accepts sockets but sends nothing.
    stream: 'wss://fstream.binance.com/market/ws',
  },
}

const MULTIPLIER_PREFIXES = ['1000000', '1000', '1M']

/**
 * A coin as another market spells it, among the coins that market watches: futures list a few
 * low-priced coins per 1000 (1000SHIBUSDT), spot lists them plainly (SHIBUSDT). Null when that
 * market does not watch it.
 */
export function spellingOn(symbol: string, watched: string[]): string | null {
  if (watched.includes(symbol)) return symbol
  for (const prefix of MULTIPLIER_PREFIXES) {
    const stripped = symbol.startsWith(prefix) ? symbol.slice(prefix.length) : null
    if (stripped && watched.includes(stripped)) return stripped
    if (watched.includes(prefix + symbol)) return prefix + symbol
  }
  return null
}

/** The feed for an exchange name; null while the name is not known yet. */
export function feedFor(exchange: string | undefined): ChartFeed | null {
  if (!exchange) return null
  return FEEDS[exchange] ?? { exchange, label: exchange, short: exchange }
}

/** "Futures" / "Spot" for the switch. */
export function shortName(venue: Venue): string {
  return FEEDS[venue.name]?.short ?? venue.label
}

const CHOICE_KEY = 'crypto-signals.exchange'

/** The market this browser last looked at, if any. */
export function savedVenue(): string | null {
  try {
    return localStorage.getItem(CHOICE_KEY)
  } catch {
    return null
  }
}

export function saveVenue(name: string) {
  try {
    localStorage.setItem(CHOICE_KEY, name)
  } catch {
    // Not remembered; still used for this visit.
  }
}

export const ChartFeedContext = createContext<ChartFeed | null>(null)

/** The picked market's chart feed, or null until the server has said which markets it runs. */
export function useChartFeed(): ChartFeed | null {
  return useContext(ChartFeedContext)
}
