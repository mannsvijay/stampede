import { Link, Outlet } from 'react-router'

import styles from './Layout.module.css'

export function Layout() {
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <Link to="/" className={styles.brand}>
          STAMPEDE
        </Link>
        <span className="label">Departures</span>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  )
}
