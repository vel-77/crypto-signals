import { useEffect, useRef, useState } from 'react'

export interface Option {
  value: string
  label: string
}

interface Props {
  id: string
  label: string
  options: Option[]
  selected: string[]
  onChange: (next: string[]) => void
}

/**
 * Dropdown with checkboxes: pick any number of values, or none for "all".
 *
 * <p>A native multiple-select needs Ctrl-click and shows a fixed-height box, which nobody discovers;
 * this behaves like a normal dropdown that simply lets more than one option stay ticked.
 */
export function MultiSelect({ id, label, options, selected, onChange }: Props) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  // Only values that exist among the options count; a stale saved value never shows as selected.
  const active = selected.filter((v) => options.some((o) => o.value === v))

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const toggle = (value: string) =>
    onChange(active.includes(value) ? active.filter((v) => v !== value) : [...active, value])

  const summary =
    active.length === 0
      ? 'all'
      : active.length <= 2
        ? options.filter((o) => active.includes(o.value)).map((o) => o.label).join(', ')
        : `${active.length} selected`

  return (
    <div className="field multiselect" ref={rootRef}>
      <label htmlFor={id}>{label}</label>
      <button
        id={id}
        type="button"
        className={`ms-button ${active.length ? 'ms-active' : ''}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="ms-summary">{summary}</span>
        <span className="ms-caret">▾</span>
      </button>
      {open && (
        <div className="ms-panel" role="listbox" aria-multiselectable="true">
          <button type="button" className="ms-clear" onClick={() => onChange([])}>
            {active.length ? 'Clear — show all' : 'Showing all'}
          </button>
          {options.map((o) => (
            <label key={o.value} className="ms-option">
              <input type="checkbox" checked={active.includes(o.value)} onChange={() => toggle(o.value)} />
              <span>{o.label}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  )
}
