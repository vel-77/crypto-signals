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
  touches?: Touch[]
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

/** Every filter holds several values; an empty list means "all". */
export interface Filters {
  symbols: string[]
  timeframes: string[]
  types: string[]
  statuses: string[]
  /** Exact touch counts such as "3", plus an open-ended bucket such as "8+". */
  touches: string[]
}
