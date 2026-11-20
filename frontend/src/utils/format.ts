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

/**
 * M4 双列表用的窄列格式：默认 3 位小数，去掉尾零，不出现科学计数法。
 *
 * <p>与 {@link formatNumber} 的差别只有两点：位数更少（左栏只有约 60px），
 * 以及小到会被 3 位小数抹平的值（`0.0005`）自动多留几位，不写成 `5.00e-4`。
 */
export function formatShort(value: number | null | undefined, digits = 3): string {
  if (value == null || !Number.isFinite(value)) {
    return '—'
  }
  let decimals = digits
  while (decimals < 12 && Number(value.toFixed(decimals)) === 0 && value !== 0) {
    decimals += 1
  }
  const text = value.toFixed(decimals)
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
