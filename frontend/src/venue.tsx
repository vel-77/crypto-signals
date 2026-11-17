import { createContext, useContext } from 'react'

/**
 * Where the chart reads its candles in the browser: the same Binance market the server analyses
 * (GET /api/config, exchange.name), so the chart shows the prices the levels were found on.
 */
export interface ChartFeed {
  exchange: string
  /** For people, e.g. under the chart. */
  label: string
  /** REST klines endpoint; absent means the chart shows the server's stored candles only. */
  klines?: string
  /** Websocket base: `${stream}/${symbol}@kline_${timeframe}`. */
  stream?: string
}

const FEEDS: Record<string, ChartFeed> = {
  BINANCE: {
    exchange: 'BINANCE',
    label: 'Binance spot',
    klines: 'https://api.binance.com/api/v3/klines',
    stream: 'wss://stream.binance.com:9443/ws',
  },
  BINANCE_FUTURES: {
    exchange: 'BINANCE_FUTURES',
    label: 'Binance USDⓈ-M futures',
    klines: 'https://fapi.binance.com/fapi/v1/klines',
    // Futures market streams live under /market; the bare host accepts sockets but sends nothing.
    stream: 'wss://fstream.binance.com/market/ws',
  },
}

/** The feed for an exchange name; null while the name is not known yet. */
export function feedFor(exchange: string | undefined): ChartFeed | null {
  if (!exchange) return null
  return FEEDS[exchange] ?? { exchange, label: exchange }
}

export const ChartFeedContext = createContext<ChartFeed | null>(null)

/** The running exchange's chart feed, or null until the server has said which exchange it is. */
export function useChartFeed(): ChartFeed | null {
  return useContext(ChartFeedContext)
}
