'use strict';

// Keep these frequencies and formulas aligned with the existing Node API.
const ANDROID_BANDS_HZ = [60, 170, 310, 600, 1000, 3000, 6000, 12000];
const KNOWN_TYPES = new Set(['PK', 'PEAKING', 'LS', 'LSC', 'LOW_SHELF', 'HS', 'HSC', 'HIGH_SHELF']);

function parseParametricEQText(content) {
  const filters = [];
  const unknownFilterTypes = {};
  let preamp = 0;
  let malformedFilters = 0;

  for (const line of content.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.startsWith('Preamp:')) {
      preamp = parseFloat(trimmed.split(':')[1]) || 0;
    }
    if (trimmed.includes('Filter') && trimmed.includes('Fc')) {
      const parts = trimmed.split(/\s+/);
      const fcIdx = parts.indexOf('Fc');
      const gainIdx = parts.indexOf('Gain');
      const qIdx = parts.indexOf('Q');
      const type = parts[3] || 'PK';
      const fc = fcIdx >= 0 ? parseFloat(parts[fcIdx + 1]) : NaN;
      const gain = gainIdx >= 0 ? parseFloat(parts[gainIdx + 1]) : NaN;
      const q = qIdx >= 0 ? parseFloat(parts[qIdx + 1]) : 1.0;
      if (isNaN(fc) || isNaN(gain)) {
        malformedFilters++;
        continue;
      }
      filters.push({ type, fc, gain, q });
      if (!KNOWN_TYPES.has(type)) {
        unknownFilterTypes[type] = (unknownFilterTypes[type] || 0) + 1;
      }
    }
  }
  return { preamp, filters, malformedFilters, unknownFilterTypes };
}

function parametricToEightBand(filters, preamp) {
  const gains = new Array(ANDROID_BANDS_HZ.length).fill(0);
  for (const filter of filters) {
    if (filter.type === 'PK' || filter.type === 'PEAKING') {
      const sigma = (Math.LOG2E / (filter.q || 1.0)) * 0.9;
      for (let i = 0; i < ANDROID_BANDS_HZ.length; i++) {
        const d = Math.abs(Math.log2(ANDROID_BANDS_HZ[i] / filter.fc));
        gains[i] += filter.gain * Math.exp(-0.5 * Math.pow(d / sigma, 2));
      }
    } else if (filter.type === 'LS' || filter.type === 'LSC' || filter.type === 'LOW_SHELF') {
      for (let i = 0; i < ANDROID_BANDS_HZ.length; i++) {
        if (ANDROID_BANDS_HZ[i] <= filter.fc) {
          gains[i] += filter.gain;
        } else {
          gains[i] += filter.gain * Math.exp(-Math.log2(ANDROID_BANDS_HZ[i] / filter.fc) * 1.5);
        }
      }
    } else if (filter.type === 'HS' || filter.type === 'HSC' || filter.type === 'HIGH_SHELF') {
      for (let i = 0; i < ANDROID_BANDS_HZ.length; i++) {
        if (ANDROID_BANDS_HZ[i] >= filter.fc) {
          gains[i] += filter.gain;
        } else {
          gains[i] += filter.gain * Math.exp(-Math.log2(filter.fc / ANDROID_BANDS_HZ[i]) * 1.5);
        }
      }
    }
  }
  return gains.map(g => parseFloat(Math.max(-12, Math.min(12, g + preamp)).toFixed(1)));
}

module.exports = { ANDROID_BANDS_HZ, parseParametricEQText, parametricToEightBand };
