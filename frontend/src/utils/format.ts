/** 展示层格式化：与原型 fmt 保持相同的去尾零规则（不参与计算）。 */

export function formatNumber(value: number | null | undefined, digits = 4): string {
  if (value == null || !Number.isFinite(value)) {
    return '—'
  }
  if (value !== 0 && Math.abs(value) < 1e-3) {
    return value.toExponential(2)
  }
  const text = value.toFixed(digits)
  return text.includes('.') ? text.replace(/0+$/, '').replace(/\.$/, '') : text
}

export function formatPercent(value: number | null | undefined, digits = 0): string {
  if (value == null || !Number.isFinite(value)) {
    return '—'
  }
  return `${value.toFixed(digits)}%`
}

/** 非负数；空值保持 null，便于「留空即自动」。 */
export function positiveOrNull(value: number | null | undefined): number | null {
  return value != null && Number.isFinite(value) && value > 0 ? value : null
}
