export type LevelType = 'SUPPORT' | 'RESISTANCE'
export type LevelStatus = 'ACTIVE' | 'SUPERSEDED' | 'BROKEN' | 'EXPIRED'
export type SignalStatus = 'ACTIVE' | 'SUPERSEDED' | 'INVALIDATED' | 'EXPIRED'

export interface Touch {
  id: number
  touchNumber: number
  touchAt: string
  startedAt: string
  endedAt: string
  price: string
  levelPriceAtTouch: string
  distanceFromLevel: string
  distancePercent: number
  candlesSincePrevious: number
  millisSincePrevious: number
  rejectionPercent: number
  closed: boolean
}

export interface Breakout {
  levelId: string
  levelType: LevelType
  levelPrice: string
  thresholdPrice: string
  confirmationPrice: string
  confirmedAt: string
  confirmationCandles: number
  touchCountAtBreak: number
}

export interface Level {
  id: string
  symbol: string
  timeframe: string
  type: LevelType
  price: string
  zoneLower: string
  zoneUpper: string
  strength: number
  status: LevelStatus
  swingCount: number
  touchCount: number
  firstSeenAt: string
  lastTouchAt: string | null
  strategy: string
  configFingerprint: string
  supersededBy?: string | null
  supersededByPrice?: string | null
  supersededAt?: string | null
  /** NEARBY_LEVEL: grouped with a close level further out. PRICE_PASSED: price went through it. */
  supersededReason?: 'NEARBY_LEVEL' | 'PRICE_PASSED' | null
  touches?: Touch[]
  breakout?: Breakout | null
}

export interface Signal {
  id: string
  levelId: string
  symbol: string
  timeframe: string
  type: LevelType
  price: string
  zoneLower: string
  zoneUpper: string
  touchCount: number
  strength: number
  detectedAt: string
  status: SignalStatus
  historical: boolean
  notified: boolean
  nextPotential?: NextPotential | null
  touches?: Touch[]
}

/**
 * The nearest earlier swing low below a support signal (swing high above a resistance signal) that
 * is at least the configured distance away. `evaluated` with a null price means nothing qualified.
 */
export interface NextPotential {
  evaluated: boolean
  /** Absent (not just null) when nothing qualified: the API leaves out empty fields. */
  price?: string | null
  percent?: number | null
  swingAt?: string | null
}

/** The settings the next potential low / high is worked out with, from /api/config. */
export interface NextPotentialRule {
  minDistancePercent: number
  lookback: string
}

export interface Candle {
  openTime: string
  closeTime: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** GET /api/watchlist: the analysed coins and why each one is there. */
export interface WatchList {
  /** VOLUME: fixed coins plus the most traded others; FIXED: only the configured coins. */
  mode: 'VOLUME' | 'FIXED'
  source: 'FIXED' | 'STORED' | 'EXCHANGE'
  count: number
  symbols: WatchedSymbol[]
  rule?: VolumeRule
  refreshedAt?: string
  nextRefreshAt?: string
  lastError?: string
}

export interface WatchedSymbol {
  symbol: string
  source: 'FIXED' | 'VOLUME'
  /** Average traded value per day over the volume window, in the quote asset. */
  dailyVolume?: number
  selectedAt?: string
}

export interface VolumeRule {
  quoteAsset: string
  minDailyVolume: number
  window: string
  maxSymbols: number
  keepPercent: number
  minPriceRangePercent: number
  excludeTags: string[]
  excludeAssets: string[]
  refreshInterval: string
}

/** Every filter holds several values; an empty list means "all". */
export interface Filters {
  symbols: string[]
  timeframes: string[]
  types: string[]
  statuses: string[]
  /** Exact touch counts such as "3", plus an open-ended bucket such as "8+". */
  touches: string[]
}
