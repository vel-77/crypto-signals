import { useEffect, useRef } from 'react'
import { createChart, ColorType, LineStyle, type IChartApi, type UTCTimestamp } from 'lightweight-charts'
import type { Candle, Touch } from '../types'

interface Props {
  candles: Candle[]
  levelPrice: number
  zoneLower: number
  zoneUpper: number
  touches: Touch[]
  type: 'SUPPORT' | 'RESISTANCE'
}

const COLOR = { RESISTANCE: '#f4736b', SUPPORT: '#4fc7a1' }

/**
 * Candles with the detected level drawn on top: the level itself as a solid line, the tolerance
 * zone as dashed bounds, and a numbered marker on each touch. That combination is what makes the
 * detection arguable — you can see at a glance whether the algorithm marked the same three tests a
 * person would have.
 */
export function LevelChart({ candles, levelPrice, zoneLower, zoneUpper, touches, type }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: '#8a99ad',
        fontFamily: 'ui-monospace, Menlo, monospace',
        fontSize: 11,
      },
      grid: {
        vertLines: { color: '#1f2a37' },
        horzLines: { color: '#1f2a37' },
      },
      rightPriceScale: { borderColor: '#1f2a37' },
      timeScale: { borderColor: '#1f2a37', timeVisible: true, secondsVisible: false },
      crosshair: { mode: 0 },
      autoSize: true,
    })
    chartRef.current = chart

    const series = chart.addCandlestickSeries({
      upColor: '#4fc7a1',
      downColor: '#f4736b',
      borderUpColor: '#4fc7a1',
      borderDownColor: '#f4736b',
      wickUpColor: '#4fc7a1',
      wickDownColor: '#f4736b',
    })

    series.setData(
      candles.map((c) => ({
        time: (Date.parse(c.openTime) / 1000) as UTCTimestamp,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      })),
    )

    const accent = COLOR[type]
    series.createPriceLine({
      price: levelPrice,
      color: accent,
      lineWidth: 2,
      lineStyle: LineStyle.Solid,
      axisLabelVisible: true,
      title: type === 'RESISTANCE' ? 'resistance' : 'support',
    })
    for (const bound of [zoneUpper, zoneLower]) {
      series.createPriceLine({
        price: bound,
        color: `${accent}66`,
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: false,
        title: '',
      })
    }

    // Markers must be sorted by time or the library silently drops them.
    const markers = touches
      .map((touch) => ({
        time: (Date.parse(touch.touchAt) / 1000) as UTCTimestamp,
        position: (type === 'RESISTANCE' ? 'aboveBar' : 'belowBar') as 'aboveBar' | 'belowBar',
        color: '#f0b429',
        shape: (type === 'RESISTANCE' ? 'arrowDown' : 'arrowUp') as 'arrowDown' | 'arrowUp',
        text: String(touch.touchNumber),
      }))
      .sort((a, b) => (a.time as number) - (b.time as number))
    series.setMarkers(markers)

    chart.timeScale().fitContent()

    return () => {
      chart.remove()
      chartRef.current = null
    }
  }, [candles, levelPrice, zoneLower, zoneUpper, touches, type])

  return (
    <div className="chart-wrap">
      <div ref={containerRef} className="chart" />
      <div className="legend">
        <span><i style={{ background: COLOR[type] }} />level</span>
        <span><i style={{ background: `${COLOR[type]}66` }} />tolerance zone</span>
        <span style={{ color: '#f0b429' }}>▲ numbered touches</span>
      </div>
    </div>
  )
}
