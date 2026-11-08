import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <>
      <p role="alert">No such page.</p>
      <Link to="/">Back to departures</Link>
    </>
  )
}
