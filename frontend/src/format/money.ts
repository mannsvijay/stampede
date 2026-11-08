// Money arrives as integer paise (1 rupee = 100 paise); it is only divided for display.
// en-IN gives Indian digit grouping: 12,34,567.
const wholeRupees = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
})
const withPaise = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

/** 450000 → "₹4,500"; 123450 → "₹1,234.50". */
export function formatPaise(paise: number): string {
  if (!Number.isSafeInteger(paise)) {
    throw new RangeError(`Not a whole number of paise: ${paise}`)
  }
  return paise % 100 === 0 ? wholeRupees.format(paise / 100) : withPaise.format(paise / 100)
}
