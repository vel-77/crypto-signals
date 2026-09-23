export type LevelType = 'SUPPORT' | 'RESISTANCE'
export type LevelStatus = 'ACTIVE' | 'BROKEN' | 'EXPIRED'
export type SignalStatus = 'ACTIVE' | 'INVALIDATED' | 'EXPIRED'

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

export interface Filters {
  symbol: string
  timeframe: string
  type: string
  status: string
}
