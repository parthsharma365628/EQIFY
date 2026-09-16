#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { ANDROID_BANDS_HZ, parseParametricEQText, parametricToEightBand } =
  require('../lib/autoeq-converter');

const SCHEMA_VERSION = 1;

function usage() {
  return 'Usage: node scripts/build-headphone-db.js --input <autoeq-results> ' +
    '--output <new-empty-directory> --version <AutoEq-commit-or-release>';
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    if (!['--input', '--output', '--version'].includes(key) ||
        !argv[i + 1] || argv[i + 1].startsWith('--') || args[key]) {
      throw new Error(usage());
    }
    args[key] = argv[i + 1];
  }
  if (!args['--input'] || !args['--output'] ||
      !args['--version'] || !args['--version'].trim()) throw new Error(usage());
  return { input: args['--input'], output: args['--output'], version: args['--version'].trim() };
}

function isWithin(parent, child) {
  const relative = path.relative(parent, child);
  return relative === '' || (!relative.startsWith('..' + path.sep) &&
    relative !== '..' && !path.isAbsolute(relative));
}

function assertSafePaths(input, output) {
  if (fs.existsSync(output)) output = fs.realpathSync(output);
  const root = path.parse(input).root;
  if (input === root || output === path.parse(output).root ||
      input === process.cwd() || isWithin(input, output) || isWithin(output, input)) {
    throw new Error('Input/output must be distinct, narrow directories; no roots or nesting.');
  }
  if (!fs.statSync(input).isDirectory()) throw new Error('Input must be a directory.');
  if (fs.existsSync(output)) {
    if (!fs.statSync(output).isDirectory() || fs.readdirSync(output).length) {
      throw new Error('Output directory must be new or empty; nothing was overwritten.');
    }
  }
}

function discover(input) {
  const candidates = [];
  for (const source of fs.readdirSync(input, { withFileTypes: true })) {
    if (!source.isDirectory() || source.isSymbolicLink()) continue;
    const sourcePath = path.join(input, source.name);
    for (const type of fs.readdirSync(sourcePath, { withFileTypes: true })) {
      if (!type.isDirectory() || type.isSymbolicLink()) continue;
      const typePath = path.join(sourcePath, type.name);
      for (const headphone of fs.readdirSync(typePath, { withFileTypes: true })) {
        if (!headphone.isDirectory() || headphone.isSymbolicLink()) continue;
        const file = path.join(typePath, headphone.name, `${headphone.name} ParametricEQ.txt`);
        if (fs.existsSync(file) && fs.statSync(file).isFile()) {
          candidates.push({ name: headphone.name, source: source.name, type: type.name, file });
        }
      }
    }
  }
  return candidates.sort((a, b) => a.name.localeCompare(b.name) ||
    a.source.localeCompare(b.source) || a.type.localeCompare(b.type));
}

function build(input, output, datasetVersion) {
  input = fs.realpathSync(path.resolve(input));
  output = path.resolve(output);
  assertSafePaths(input, output);
  const candidates = discover(input);
  if (!candidates.length) throw new Error('No matching ParametricEQ.txt files found.');

  const seen = new Set();
  const records = [];
  const profiles = [];
  const unknownFilterTypes = {};
  let duplicatesRemoved = 0;
  for (const candidate of candidates) {
    if (!candidate.name.trim() || !candidate.source.trim() || !candidate.type.trim()) {
      throw new Error('Empty headphone name/source/type in AutoEQ tree.');
    }
    const normalizedName = candidate.name.toLowerCase();
    if (seen.has(normalizedName)) { duplicatesRemoved++; continue; }
    seen.add(normalizedName);
    const parsed = parseParametricEQText(fs.readFileSync(candidate.file, 'utf8'));
    if (parsed.malformedFilters || !Number.isFinite(parsed.preamp) ||
        parsed.filters.some(f => !Number.isFinite(f.fc) || f.fc <= 0 ||
          !Number.isFinite(f.gain) || !Number.isFinite(f.q) || f.q <= 0)) {
      throw new Error(`Invalid filter/preamp in ${candidate.source}/${candidate.type}/${candidate.name}`);
    }
    for (const [type, count] of Object.entries(parsed.unknownFilterTypes)) {
      unknownFilterTypes[type] = (unknownFilterTypes[type] || 0) + count;
    }
    const gains = parametricToEightBand(parsed.filters, parsed.preamp);
    if (gains.length !== 8 || gains.some(g => !Number.isFinite(g) || g < -12 || g > 12)) {
      throw new Error(`Invalid eight-band output for ${candidate.name}`);
    }
    const key = crypto.createHash('sha256').update(normalizedName).digest('hex');
    // Keep each Git tree comfortably below GitHub's recommended directory width.
    const profilePath = `profiles/${key.slice(0, 2)}/${key}.json`;
    const record = { name: candidate.name, normalizedName, source: candidate.source,
      type: candidate.type, gains };
    records.push(record);
    profiles.push({ profilePath, record });
  }

  const checksum = crypto.createHash('sha256').update(JSON.stringify(records)).digest('hex');
  const manifest = { schemaVersion: SCHEMA_VERSION, datasetVersion,
    frequenciesHz: ANDROID_BANDS_HZ, checksum,
    headphones: profiles.map(({ profilePath, record }) => ({
      name: record.name, normalizedName: record.normalizedName,
      source: record.source, type: record.type, profilePath
    })) };
  const report = { schemaVersion: SCHEMA_VERSION, datasetVersion,
    filesScanned: candidates.length, profilesSelected: records.length,
    profilesConverted: records.length, duplicatesRemoved, unknownFilterTypes, checksum };

  fs.mkdirSync(path.join(output, 'profiles'), { recursive: true });
  fs.writeFileSync(path.join(output, 'index.html'),
    '<!doctype html><meta charset="utf-8"><title>EQify headphone data</title>' +
    '<h1>EQify headphone data</h1><p>Static eight-band profile export.</p>' +
    '<a href="index.json">Dataset index</a>\n', { flag: 'wx' });
  fs.writeFileSync(path.join(output, 'index.json'), JSON.stringify(manifest) + '\n', { flag: 'wx' });
  fs.writeFileSync(path.join(output, 'conversion-report.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  for (const { profilePath, record } of profiles) {
    fs.mkdirSync(path.dirname(path.join(output, profilePath)), { recursive: true });
    fs.writeFileSync(path.join(output, profilePath),
      JSON.stringify({ schemaVersion: SCHEMA_VERSION, datasetVersion,
        frequenciesHz: ANDROID_BANDS_HZ, ...record }) + '\n', { flag: 'wx' });
  }
  console.log(`Converted ${records.length} profiles (${duplicatesRemoved} duplicates).`);
  console.log(`Output: ${output}`);
  console.log(`SHA-256 canonical records: ${checksum}`);
  if (Object.keys(unknownFilterTypes).length) console.warn('Unknown filter types:', unknownFilterTypes);
  return report;
}

if (require.main === module) {
  try {
    const args = parseArgs(process.argv.slice(2));
    build(args.input, args.output, args.version);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = { build, discover, parseArgs };
