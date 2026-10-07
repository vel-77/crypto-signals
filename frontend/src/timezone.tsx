import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * Per-viewer time zone.
 *
 * <p>The server stores and serves every timestamp in UTC; converting for display is purely the
 * viewer's business. Each person's choice is kept in their own browser, so two people watching the
 * same server each see their own local time. The default is the browser's own zone.
 */

const STORAGE_KEY = 'crypto-signals.timezone'

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/** Every zone the browser knows, with UTC and the browser's own zone first. */
export function allTimeZones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
  let zones: string[] = []
  try {
    zones = intl.supportedValuesOf ? intl.supportedValuesOf('timeZone') : []
  } catch {
    zones = []
  }
  if (zones.length === 0) {
    zones = ['Europe/London', 'Europe/Berlin', 'Europe/Moscow', 'Asia/Yerevan', 'Asia/Dubai',
      'Asia/Tbilisi', 'Asia/Tokyo', 'Asia/Singapore', 'America/New_York', 'America/Los_Angeles']
  }
  const first = ['UTC', browserTimeZone()]
  return [...new Set([...first, ...zones.filter((z) => !first.includes(z))])]
}

function isValidZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone })
    return true
  } catch {
    return false
  }
}

const partFormatters = new Map<string, Intl.DateTimeFormat>()

function partFormatter(zone: string): Intl.DateTimeFormat {
  let f = partFormatters.get(zone)
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: zone, hourCycle: 'h23',
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
    partFormatters.set(zone, f)
  }
  return f
}

/** Wall-clock fields of an instant in a zone. */
function wallClock(epochMillis: number, zone: string) {
  const out: Record<string, number> = {}
  for (const p of partFormatter(zone).formatToParts(new Date(epochMillis))) {
    if (p.type !== 'literal') out[p.type] = Number(p.value)
  }
  return out as { year: number; month: number; day: number; hour: number; minute: number; second: number }
}

const pad = (n: number) => String(n).padStart(2, '0')

/** "2026-09-20 15:45" in the given zone — minute precision, which is what a candle carries. */
export function formatInZone(epochMillis: number, zone: string): string {
  const w = wallClock(epochMillis, zone)
  return `${w.year}-${pad(w.month)}-${pad(w.day)} ${pad(w.hour)}:${pad(w.minute)}`
}

/** The zone's offset from UTC at that instant, in milliseconds (DST-aware). */
export function zoneOffsetMillis(epochMillis: number, zone: string): number {
  const w = wallClock(epochMillis, zone)
  const asUtc = Date.UTC(w.year, w.month - 1, w.day, w.hour, w.minute, w.second)
  return asUtc - Math.floor(epochMillis / 1000) * 1000
}

/**
 * The charting library lays out its time axis in UTC. Shifting each timestamp by the zone's offset
 * makes the axis, the crosshair label and the day boundaries all read in the viewer's local time —
 * the approach the library's own documentation recommends.
 */
export function toChartSeconds(epochMillis: number, zone: string): number {
  return Math.floor((epochMillis + zoneOffsetMillis(epochMillis, zone)) / 1000)
}

/** Short label such as "UTC" or "GMT+4". */
export function zoneLabel(zone: string): string {
  if (zone === 'UTC') return 'UTC'
  try {
    const part = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'shortOffset' })
      .formatToParts(new Date())
      .find((p) => p.type === 'timeZoneName')
    return part?.value ?? zone
  } catch {
    return zone
  }
}

interface TimeZoneState {
  timeZone: string
  setTimeZone: (zone: string) => void
}

const TimeZoneContext = createContext<TimeZoneState>({ timeZone: 'UTC', setTimeZone: () => {} })

export function TimeZoneProvider({ children }: { children: ReactNode }) {
  const [timeZone, setZone] = useState<string>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved && isValidZone(saved)) return saved
    } catch {
      // Storage unavailable (private window, blocked site data): fall back to the browser zone.
    }
    return browserTimeZone()
  })

  const value = useMemo<TimeZoneState>(() => ({
    timeZone,
    setTimeZone: (zone: string) => {
      if (!isValidZone(zone)) return
      setZone(zone)
      try {
        localStorage.setItem(STORAGE_KEY, zone)
      } catch {
        // Not persisted; still applied for this visit.
      }
    },
  }), [timeZone])

  return <TimeZoneContext.Provider value={value}>{children}</TimeZoneContext.Provider>
}

export function useTimeZone(): TimeZoneState {
  return useContext(TimeZoneContext)
}
