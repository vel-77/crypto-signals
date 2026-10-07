import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ColorType, CrosshairMode, LineStyle, createChart,
  type IChartApi, type ISeriesApi, type Logical, type MouseEventParams, type UTCTimestamp,
} from 'lightweight-charts'
import { api } from '../api'
import type { LevelType, Touch } from '../types'
import { toChartSeconds, useTimeZone } from '../timezone'
import { chartPrecision, duration, price as fmtPrice, TIMEFRAME_MILLIS } from './format'

/** The level drawn on top of the market: line, tolerance zone and numbered touches. */
export interface LevelOverlay {
  price: number
  zoneLower: number
  zoneUpper: number
  type: LevelType
  touches: Touch[]
}

interface Props {
  symbol: string
  timeframe: string
  level?: LevelOverlay | null
}

interface Bar {
  openTime: number
  open: number
  high: number
  low: number
  close: number
}

interface Point {
  logical: number
  price: number
}

type Feed = 'connecting' | 'live' | 'reconnecting' | 'stored'

/**
 * Browsers read candles straight from Binance's public market data — history over REST, then every
 * tick over a websocket — so the chart moves in real time without the server having to relay
 * anything. Both endpoints allow cross-origin access and need no key. If Binance is unreachable
 * from the viewer's network, the chart falls back to the candles stored on our server, which are
 * complete but only update as candles close.
 */
const BINANCE_REST = 'https://api.binance.com/api/v3/klines'
const BINANCE_WS = 'wss://stream.binance.com:9443/ws'

/** Binance pushes a kline update about every two seconds; this much silence means the socket is dead. */
const STALE_MILLIS = 30_000

const COLOR: Record<LevelType, string> = { RESISTANCE: '#f4736b', SUPPORT: '#4fc7a1' }

async function loadHistory(symbol: string, timeframe: string): Promise<{ bars: Bar[]; live: boolean }> {
  try {
    const response = await fetch(`${BINANCE_REST}?symbol=${symbol}&interval=${timeframe}&limit=1000`)
    if (!response.ok) throw new Error(`Binance ${response.status}`)
    const rows = (await response.json()) as unknown[][]
    return {
      live: true,
      bars: rows.map((r) => ({
        openTime: Number(r[0]), open: Number(r[1]), high: Number(r[2]), low: Number(r[3]), close: Number(r[4]),
      })),
    }
  } catch {
    const stored = await api.candles(symbol, timeframe, 1000)
    return {
      live: false,
      bars: stored.map((c) => ({
        openTime: Date.parse(c.openTime), open: c.open, high: c.high, low: c.low, close: c.close,
      })),
    }
  }
}

export function LiveChart({ symbol, timeframe, level }: Props) {
  const { timeZone } = useTimeZone()
  const containerRef = useRef<HTMLDivElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const labelRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const levelRef = useRef(level)
  levelRef.current = level

  const [feed, setFeed] = useState<Feed>('connecting')
  const [lastPrice, setLastPrice] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [measuring, setMeasuring] = useState(false)
  const measuringRef = useRef(false)
  const anchor = useRef<Point | null>(null)
  const end = useRef<Point | null>(null)
  const fixed = useRef(false)
  /** Decimals the price axis uses for this market; the measurement reads the same way. */
  const precisionRef = useRef(2)

  const tfMillis = TIMEFRAME_MILLIS[timeframe] ?? 60_000

  // A stable identity for the overlay: the parent passes a new object on every render, and
  // rebuilding the chart for that would reset the view and reconnect the socket each time.
  const levelKey = level
    ? `${level.type}|${level.price}|${level.zoneLower}|${level.zoneUpper}|${level.touches.map((t) => t.touchAt).join(',')}`
    : ''

  /** Positions the measurement box over the two chosen points; hides it when there is nothing to show. */
  const drawMeasure = useCallback(() => {
    const box = boxRef.current
    const label = labelRef.current
    const chart = chartRef.current
    const series = seriesRef.current
    if (!box || !label || !chart || !series) return
    const a = anchor.current
    const b = end.current
    if (!measuringRef.current || !a || !b) {
      box.style.display = 'none'
      return
    }
    const x1 = chart.timeScale().logicalToCoordinate(a.logical as Logical)
    const x2 = chart.timeScale().logicalToCoordinate(b.logical as Logical)
    const y1 = series.priceToCoordinate(a.price)
    const y2 = series.priceToCoordinate(b.price)
    if (x1 === null || x2 === null || y1 === null || y2 === null) {
      box.style.display = 'none'
      return
    }
    const up = b.price >= a.price
    box.style.display = 'block'
    box.style.left = `${Math.min(x1, x2)}px`
    box.style.top = `${Math.min(y1, y2)}px`
    box.style.width = `${Math.max(1, Math.abs(x2 - x1))}px`
    box.style.height = `${Math.max(1, Math.abs(y2 - y1))}px`
    box.className = `measure-box ${up ? 'measure-up' : 'measure-down'}`

    const delta = b.price - a.price
    const pct = (delta / a.price) * 100
    const bars = Math.round(b.logical - a.logical)
    label.textContent =
      `${delta >= 0 ? '+' : '−'}${Math.abs(delta).toFixed(precisionRef.current)}  (${pct >= 0 ? '+' : '−'}${Math.abs(pct).toFixed(2)}%)` +
      `   ${Math.abs(bars)} bars, ${duration(Math.abs(bars) * tfMillis)}`
    // Label sits beyond the end point, above for a rise and below for a fall.
    label.style.left = `${x2 >= x1 ? Math.abs(x2 - x1) : 0}px`
    label.style.top = up ? '-26px' : `${Math.abs(y2 - y1) + 6}px`
    label.style.transform = x2 >= x1 ? 'translateX(-100%)' : 'none'
  }, [tfMillis])

  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    let disposed = false
    let socket: WebSocket | null = null
    let retryTimer: number | undefined
    let backoff = 1_000
    let lastMessageAt = Date.now()
    setError(null)
    setFeed('connecting')
    setLastPrice(null)
    anchor.current = null
    end.current = null
    fixed.current = false

    const chart = createChart(container, {
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: '#8a99ad',
        fontFamily: 'ui-monospace, Menlo, monospace',
        fontSize: 11,
      },
      grid: { vertLines: { color: '#1f2a37' }, horzLines: { color: '#1f2a37' } },
      rightPriceScale: { borderColor: '#1f2a37' },
      timeScale: { borderColor: '#1f2a37', timeVisible: true, secondsVisible: false, rightOffset: 6 },
      crosshair: { mode: CrosshairMode.Normal },
      autoSize: true,
    })
    const series = chart.addCandlestickSeries({
      upColor: '#4fc7a1', downColor: '#f4736b',
      borderUpColor: '#4fc7a1', borderDownColor: '#f4736b',
      wickUpColor: '#4fc7a1', wickDownColor: '#f4736b',
    })
    chartRef.current = chart
    seriesRef.current = series

    const overlay = levelRef.current
    if (overlay) {
      const accent = COLOR[overlay.type]
      series.createPriceLine({
        price: overlay.price, color: accent, lineWidth: 2, lineStyle: LineStyle.Solid,
        axisLabelVisible: true, title: overlay.type === 'RESISTANCE' ? 'resistance' : 'support',
      })
      for (const bound of [overlay.zoneUpper, overlay.zoneLower]) {
        series.createPriceLine({
          price: bound, color: `${accent}66`, lineWidth: 1, lineStyle: LineStyle.Dashed,
          axisLabelVisible: false, title: '',
        })
      }
    }

    const toPoint = (b: Bar) => ({
      time: toChartSeconds(b.openTime, timeZone) as UTCTimestamp,
      open: b.open, high: b.high, low: b.low, close: b.close,
    })

    const connect = () => {
      if (disposed) return
      socket = new WebSocket(`${BINANCE_WS}/${symbol.toLowerCase()}@kline_${timeframe}`)
      socket.onopen = () => {
        backoff = 1_000
        lastMessageAt = Date.now()
        setFeed('live')
      }
      socket.onmessage = (event) => {
        lastMessageAt = Date.now()
        try {
          const k = JSON.parse(event.data as string).k
          if (!k) return
          series.update(toPoint({
            openTime: Number(k.t), open: Number(k.o), high: Number(k.h), low: Number(k.l), close: Number(k.c),
          }))
          setLastPrice(Number(k.c))
        } catch {
          // An unexpected message must never break the chart.
        }
      }
      socket.onclose = () => {
        if (disposed) return
        setFeed('reconnecting')
        retryTimer = window.setTimeout(connect, backoff)
        backoff = Math.min(backoff * 2, 30_000)
      }
      socket.onerror = () => socket?.close()
    }

    // The same failure the server suffered: a network drop can leave a socket open but silent.
    const watchdog = window.setInterval(() => {
      if (socket && socket.readyState === WebSocket.OPEN && Date.now() - lastMessageAt > STALE_MILLIS) {
        socket.close()
      }
    }, 10_000)

    loadHistory(symbol, timeframe)
      .then(({ bars, live }) => {
        if (disposed) return
        if (bars.length === 0) {
          setError('No candles available for this market yet.')
          return
        }
        const precision = chartPrecision(bars[bars.length - 1].close)
        precisionRef.current = precision
        series.applyOptions({ priceFormat: { type: 'price', precision, minMove: 1 / 10 ** precision } })
        series.setData(bars.map(toPoint))

        if (overlay) {
          // Markers must be sorted by time, or the library silently drops them.
          series.setMarkers(overlay.touches
            .map((t) => ({
              time: toChartSeconds(Date.parse(t.touchAt), timeZone) as UTCTimestamp,
              position: (overlay.type === 'RESISTANCE' ? 'aboveBar' : 'belowBar') as 'aboveBar' | 'belowBar',
              color: '#f0b429',
              shape: (overlay.type === 'RESISTANCE' ? 'arrowDown' : 'arrowUp') as 'arrowDown' | 'arrowUp',
              text: String(t.touchNumber),
            }))
            .sort((a, b) => (a.time as number) - (b.time as number)))
        }
        chart.timeScale().setVisibleLogicalRange({ from: Math.max(0, bars.length - 220), to: bars.length + 6 })
        setLastPrice(bars[bars.length - 1].close)
        if (live) {
          connect()
        } else {
          setFeed('stored')
        }
      })
      .catch((e: Error) => !disposed && setError(e.message))

    const onClick = (param: MouseEventParams) => {
      if (!measuringRef.current || !param.point || param.logical === undefined) return
      const p = series.coordinateToPrice(param.point.y)
      if (p === null) return
      const point = { logical: param.logical, price: p }
      if (!anchor.current || fixed.current) {
        anchor.current = point
        end.current = point
        fixed.current = false
      } else {
        end.current = point
        fixed.current = true
      }
      drawMeasure()
    }
    const onMove = (param: MouseEventParams) => {
      if (!measuringRef.current || !anchor.current || fixed.current || !param.point
          || param.logical === undefined) return
      const p = series.coordinateToPrice(param.point.y)
      if (p === null) return
      end.current = { logical: param.logical, price: p }
      drawMeasure()
    }
    chart.subscribeClick(onClick)
    chart.subscribeCrosshairMove(onMove)
    chart.timeScale().subscribeVisibleLogicalRangeChange(drawMeasure)

    return () => {
      disposed = true
      window.clearInterval(watchdog)
      if (retryTimer) window.clearTimeout(retryTimer)
      if (socket) {
        socket.onclose = null
        socket.close()
      }
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
    }
    // levelKey stands in for `level`, whose identity changes on every parent render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol, timeframe, timeZone, levelKey, drawMeasure])

  // Escape clears a measurement in progress.
  useEffect(() => {
    if (!measuring) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        anchor.current = null
        end.current = null
        fixed.current = false
        drawMeasure()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [measuring, drawMeasure])

  const toggleMeasure = () => {
    const next = !measuring
    measuringRef.current = next
    setMeasuring(next)
    anchor.current = null
    end.current = null
    fixed.current = false
    drawMeasure()
  }

  const feedLabel: Record<Feed, string> = {
    connecting: 'connecting…', live: 'live', reconnecting: 'reconnecting…', stored: 'stored data — not live',
  }

  return (
    <div className="chart-wrap">
      <div className="chart-toolbar">
        <span className="num chart-market">{symbol} · {timeframe}</span>
        {lastPrice !== null && <span className="num chart-last">{fmtPrice(lastPrice)}</span>}
        <span className={`pill feed-${feed}`}>{feedLabel[feed]}</span>
        <span className="toolbar-spacer" />
        <button className={`btn btn-small ${measuring ? 'btn-on' : ''}`} onClick={toggleMeasure}
                title="Click a start point, then an end point. Esc clears.">
          {measuring ? 'Measuring — click two points' : 'Measure'}
        </button>
        <button className="btn btn-small" onClick={() => chartRef.current?.timeScale().scrollToRealTime()}
                title="Jump back to the newest candle">
          Go to live
        </button>
      </div>
      {error && <div className="error">{error}</div>}
      <div className={`chart-box ${measuring ? 'is-measuring' : ''}`}>
        <div ref={containerRef} className="chart" />
        <div ref={boxRef} className="measure-box" style={{ display: 'none' }}>
          <div ref={labelRef} className="measure-label" />
        </div>
      </div>
      <div className="legend">
        {level && <span><i style={{ background: COLOR[level.type] }} />level</span>}
        {level && <span><i style={{ background: `${COLOR[level.type]}66` }} />tolerance zone</span>}
        {level && <span style={{ color: '#f0b429' }}>▲ numbered touches</span>}
        <span className="faint">times in {timeZone}</span>
      </div>
    </div>
  )
}
