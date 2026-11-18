// End-of-run output (M2.4a): k6 calls handleSummary(data) once, after teardown. Each scenario passes the
// lines it wants; this writes them to the run folder (mounted at /out by scripts/k6.sh) and stdout:
//   summary.json  every metric, as k6 reports it
//   summary.txt   the short report below
//   result.env    KEY=value lines that k6.sh reads (Git Bash has no jq)

function value(data, metric, stat) {
  const m = data.metrics[metric];
  if (!m || m.values[stat] === undefined) {
    return null;
  }
  return m.values[stat];
}

export function num(data, metric, stat = 'count') {
  const v = value(data, metric, stat);
  return v === null ? 0 : v;
}

function fmt(v, digits = 1) {
  return v === null ? '-' : Number(v).toFixed(digits);
}

/** "p50 / p95 / p99 / max (n)" of a Trend in ms, or "-" without samples. */
export function latency(data, metric) {
  if (!data.metrics[metric]) {
    return '-';
  }
  return `p50 ${fmt(value(data, metric, 'med'))} / p95 ${fmt(value(data, metric, 'p(95)'))} / `
    + `p99 ${fmt(value(data, metric, 'p(99)'))} / max ${fmt(value(data, metric, 'max'))} ms `
    + `(${num(data, metric, 'count')})`;
}

/** Every threshold with ok/FAILED, and whether all passed. */
function thresholds(data) {
  const lines = [];
  let allOk = true;
  for (const [name, metric] of Object.entries(data.metrics)) {
    for (const [expr, result] of Object.entries(metric.thresholds || {})) {
      lines.push(`  ${result.ok ? 'ok    ' : 'FAILED'} ${name}: ${expr}`);
      allOk = allOk && result.ok;
    }
  }
  return { lines, allOk };
}

/**
 * @param title  first line of the report
 * @param lines  the scenario's report lines
 * @param result KEY=value pairs for result.env (THRESHOLDS_OK is added)
 */
export function writeSummary(data, title, lines, result) {
  const t = thresholds(data);
  const text = [
    `=== ${title} ===`,
    ...lines,
    '',
    'Thresholds (correctness only, D-024):',
    ...t.lines,
    `Result: ${t.allOk ? 'PASS' : 'FAIL'} (k6 thresholds; k6.sh adds the database check)`,
    '',
  ].join('\n');
  const env = Object.entries({ ...result, THRESHOLDS_OK: t.allOk ? 1 : 0 })
    .map(([k, v]) => `${k}=${v}`).join('\n');
  return {
    stdout: `\n${text}`,
    '/out/summary.json': JSON.stringify(data, null, 2),
    '/out/summary.txt': text,
    '/out/result.env': `${env}\n`,
  };
}
