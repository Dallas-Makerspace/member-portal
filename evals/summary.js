// Prints a per-case table from a promptfoo JSON output: node summary.js output/run.json [--failures]
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: node summary.js <output.json> [--failures]');
  process.exit(1);
}
const failuresOnly = process.argv.includes('--failures');
const results = JSON.parse(fs.readFileSync(file, 'utf8')).results.results;

const byProvider = {};
for (const r of results) {
  const p = r.provider.label || r.provider.id;
  (byProvider[p] ||= []).push(r);
}

const usd = n => `$${(n || 0).toFixed(4)}`;
for (const [provider, rows] of Object.entries(byProvider)) {
  const passed = rows.filter(r => r.success).length;
  const cost = rows.reduce((n, r) => n + (r.cost || 0), 0);
  const latencies = rows.map(r => r.latencyMs || 0).sort((a, b) => a - b);
  const median = latencies[Math.floor(latencies.length / 2)];
  console.log(`\n== ${provider}: ${passed}/${rows.length} passed, cost ${usd(cost)} (avg ${usd(cost / rows.length)}), median ${(median / 1000).toFixed(1)}s`);
  const byCategory = {};
  for (const r of rows) {
    const c = r.testCase.metadata?.category || '-';
    (byCategory[c] ||= [0, 0])[0] += r.success ? 1 : 0;
    byCategory[c][1]++;
  }
  console.log('   ' + Object.entries(byCategory).map(([c, [a, b]]) => `${c} ${a}/${b}`).join('  '));
  for (const r of rows) {
    if (failuresOnly && r.success) continue;
    const calls = r.response?.metadata?.toolNames?.length ?? '-';
    console.log(`${r.success ? 'PASS' : 'FAIL'}  ${r.testCase.description.padEnd(26)} calls=${String(calls).padEnd(2)} ${usd(r.cost)} ${((r.latencyMs || 0) / 1000).toFixed(1)}s`);
    if (r.error && !r.gradingResult?.componentResults?.length) console.log(`      error: ${String(r.error).split('\n')[0].slice(0, 160)}`);
    for (const g of r.gradingResult?.componentResults || []) {
      if (!g.pass) console.log(`      x ${(g.assertion?.type || '')} ${String(g.assertion?.value ?? '').slice(0, 70).replace(/\n/g, ' ')} -> ${String(g.reason || '').split('\n')[0].slice(0, 200)}`);
    }
  }
}
