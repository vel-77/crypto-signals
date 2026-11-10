import type { Touch } from '../types'
import { useTimeZone, zoneLabel } from '../timezone'
import { duration, percent, price, timestamp } from './format'

/** The evidence behind a signal: when each separate test happened and how far it was rejected. */
export function TouchTable({ touches }: { touches: Touch[] }) {
  const { timeZone } = useTimeZone()
  if (touches.length === 0) {
    return <div className="empty">No touches recorded.</div>
  }
  return (
    <table>
      <thead>
        <tr>
          <th>#</th>
          <th title="Opening time of the candle, as labelled on the chart">Candle ({zoneLabel(timeZone)})</th>
          <th>Price</th>
          <th>Distance</th>
          <th>Gap</th>
          <th>Since previous</th>
          <th>Rejection</th>
          <th>State</th>
        </tr>
      </thead>
      <tbody>
        {touches.map((touch) => (
          <tr key={touch.touchNumber} style={{ cursor: 'default' }}>
            <td className="num">{touch.touchNumber}</td>
            <td className="num">{timestamp(touch.touchAt, timeZone)}</td>
            <td className="num">{price(touch.price)}</td>
            <td className="num dim">{percent(touch.distancePercent, 3)}</td>
            <td className="num dim">
              {touch.candlesSincePrevious < 0 ? '—' : `${touch.candlesSincePrevious} candles`}
            </td>
            <td className="num dim">
              {touch.millisSincePrevious < 0 ? '—' : duration(touch.millisSincePrevious)}
            </td>
            <td className="num dim">{percent(touch.rejectionPercent, 2)}</td>
            <td className="faint">{touch.closed ? 'closed' : 'open'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
