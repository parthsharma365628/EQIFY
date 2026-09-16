const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseParametricEQText, parametricToEightBand } = require('../lib/autoeq-converter');
const { build } = require('../scripts/build-headphone-db');

test('parse and convert PK, shelves and preamp with eight finite bounded gains', () => {
  const parsed = parseParametricEQText([
    'Preamp: -3.0 dB',
    'Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.0',
    'Filter 2: ON LS Fc 100 Hz Gain 2.0 dB Q 0.7',
    'Filter 3: ON HS Fc 6000 Hz Gain -2.0 dB Q 0.7'
  ].join('\n'));
  const gains = parametricToEightBand(parsed.filters, parsed.preamp);
  assert.equal(parsed.malformedFilters, 0);
  assert.equal(parsed.filters.length, 3);
  assert.equal(gains.length, 8);
  assert.ok(gains.every(g => Number.isFinite(g) && g >= -12 && g <= 12));
});

test('generator deduplicates names, creates stable profiles and refuses overwrite', t => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'eqify-convert-'));
  t.after(() => fs.rmSync(temp, { recursive: true, force: true }));
  const input = path.join(temp, 'input');
  const output = path.join(temp, 'output');
  for (const [source, name] of [['A', 'Sony XM5'], ['B', 'sony xm5']]) {
    const dir = path.join(input, source, 'over-ear', name);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, `${name} ParametricEQ.txt`),
      'Preamp: -1 dB\nFilter 1: ON PK Fc 1000 Hz Gain 3 dB Q 1\n');
  }
  const report = build(input, output, 'fixture-1');
  const index = JSON.parse(fs.readFileSync(path.join(output, 'index.json')));
  assert.equal(report.duplicatesRemoved, 1);
  assert.equal(index.headphones.length, 1);
  assert.match(index.headphones[0].profilePath, /^profiles\/[0-9a-f]{2}\/[0-9a-f]{64}\.json$/);
  const profile = JSON.parse(fs.readFileSync(path.join(output, index.headphones[0].profilePath)));
  assert.equal(profile.gains.length, 8);
  assert.equal(profile.name, index.headphones[0].name);
  assert.equal(profile.normalizedName, 'sony xm5');
  assert.throws(() => build(input, output, 'fixture-1'), /new or empty/);
});
