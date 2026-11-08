// Generates src/api/schema.d.ts from the committed docs/openapi.json (D-010, D-011).
//
//   npm run gen:api     regenerate and write the file
//   npm run check:api   regenerate in memory and fail if it differs from the committed file (CI)
//
// Both commands use this one script, so "check" compares exactly what "gen" would write.
import { readFile, writeFile } from 'node:fs/promises'

import openapiTS, { astToString } from 'openapi-typescript'

const spec = new URL('../../docs/openapi.json', import.meta.url)
const output = new URL('../src/api/schema.d.ts', import.meta.url)
const header =
  '// Generated from docs/openapi.json by `npm run gen:api` (openapi-typescript). Do not edit.\n\n'

const generated = header + astToString(await openapiTS(spec))

if (process.argv.includes('--check')) {
  const committed = await readFile(output, 'utf8').catch(() => null)
  // Ignore line endings: git may check the file out with CRLF on Windows.
  const normalise = (text) => text.replace(/\r\n/g, '\n')
  if (committed === null || normalise(committed) !== normalise(generated)) {
    console.error(
      'src/api/schema.d.ts is out of date with docs/openapi.json. Run `npm run gen:api` and commit the result.',
    )
    process.exit(1)
  }
  console.log('src/api/schema.d.ts matches docs/openapi.json.')
} else {
  await writeFile(output, generated)
  console.log('Wrote src/api/schema.d.ts')
}
