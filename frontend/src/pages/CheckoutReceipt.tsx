import { formatPaise } from '../format/money'
import styles from './CheckoutReceipt.module.css'

export interface ReceiptLine {
  seatId: string
  name: string
  tierName: string
  pricePaise: number
}

interface CheckoutReceiptProps {
  lines: ReceiptLine[]
  busy: boolean
  /** After a SEAT_TAKEN or network error, paying again for the same hold is the next step. */
  retry: boolean
  onPay: () => void
  onBack: () => void
}

/**
 * PRD 8.6, plain for now: a single-column, receipt-style summary in mono with dotted leaders. The
 * saga timeline over SSE comes with M3; the fake payment of M1.5 answers in one request.
 */
export function CheckoutReceipt({ lines, busy, retry, onPay, onBack }: CheckoutReceiptProps) {
  const total = lines.reduce((sum, line) => sum + line.pricePaise, 0)
  const payLabel = busy ? 'Paying…' : retry ? 'Retry' : `Pay ${formatPaise(total)}`

  return (
    <section aria-labelledby="receipt-heading" className={styles.receipt}>
      <h2 id="receipt-heading" className="label">
        Checkout
      </h2>
      <ul className={styles.lines} aria-label="Receipt">
        {lines.map((line) => (
          <li key={line.seatId} className={styles.line}>
            <span>
              {line.name} <span className={styles.tier}>{line.tierName}</span>
            </span>
            <span className={styles.leader} aria-hidden="true" />
            <span>{formatPaise(line.pricePaise)}</span>
          </li>
        ))}
      </ul>
      <p className={`${styles.line} ${styles.total}`} data-testid="receipt-total">
        <span>Total</span>
        <span className={styles.leader} aria-hidden="true" />
        <span>{formatPaise(total)}</span>
      </p>
      <p className={styles.note}>Test payment: no real money is taken.</p>
      <div className={styles.actions}>
        <button type="button" onClick={onPay} disabled={busy}>
          {payLabel}
        </button>
        <button type="button" onClick={onBack} disabled={busy}>
          Back
        </button>
      </div>
    </section>
  )
}
