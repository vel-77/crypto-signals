import type { Filters as FilterState } from '../types'
import { MultiSelect, type Option } from './MultiSelect'

interface Props {
  value: FilterState
  onChange: (next: FilterState) => void
  onRefresh: () => void
  symbols: string[]
  timeframes: string[]
  statusOptions: string[]
  touchOptions: string[]
}

const TYPES: Option[] = [
  { value: 'RESISTANCE', label: 'resistance' },
  { value: 'SUPPORT', label: 'support' },
]

const asOptions = (values: string[], lower = false): Option[] =>
  values.map((v) => ({ value: v, label: lower ? v.toLowerCase() : v }))

/** Every filter accepts several values; values within a filter are alternatives. */
export function Filters({ value, onChange, onRefresh, symbols, timeframes, statusOptions, touchOptions }: Props) {
  const set = (key: keyof FilterState) => (next: string[]) => onChange({ ...value, [key]: next })
  const anyActive = Object.values(value).some((list) => list.length > 0)

  return (
    <div className="filters">
      <MultiSelect id="f-symbol" label="Symbol" options={asOptions(symbols)}
                   selected={value.symbols} onChange={set('symbols')} />
      <MultiSelect id="f-tf" label="Timeframe" options={asOptions(timeframes)}
                   selected={value.timeframes} onChange={set('timeframes')} />
      <MultiSelect id="f-type" label="Type" options={TYPES}
                   selected={value.types} onChange={set('types')} />
      <MultiSelect id="f-status" label="Status" options={asOptions(statusOptions, true)}
                   selected={value.statuses} onChange={set('statuses')} />
      <MultiSelect id="f-touches" label="Touches" options={asOptions(touchOptions)}
                   selected={value.touches} onChange={set('touches')} />
      <button className="btn" onClick={onRefresh}>Refresh</button>
      {anyActive && (
        <button className="btn btn-quiet"
                onClick={() => onChange({ symbols: [], timeframes: [], types: [], statuses: [], touches: [] })}>
          Clear filters
        </button>
      )}
    </div>
  )
}
