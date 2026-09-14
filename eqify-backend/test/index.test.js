const test = require('node:test');
const assert = require('node:assert/strict');

const {
  dedupeHeadphones,
  keywordDetect,
  mapItunesGenre,
  mapLastFmTags,
  parametricToEightBand,
  selectItunesTrack
} = require('../index');

test('dedupeHeadphones keeps one deterministic result per model name', () => {
  const result = dedupeHeadphones([
    { name: 'Sony WH-1000XM5', source: 'A', type: 'over-ear' },
    { name: 'sony wh-1000xm5', source: 'B', type: 'over-ear' },
    { name: 'JBL Live 770NC', source: 'A', type: 'over-ear' }
  ]);

  assert.deepEqual(result.map(item => item.name), [
    'Sony WH-1000XM5',
    'JBL Live 770NC'
  ]);
});

test('genre mapping keeps supported EQ categories', () => {
  assert.equal(mapLastFmTags(['melodic death metal', 'metal']), 'Metal');
  assert.equal(mapLastFmTags(['deep house', 'electronic']), 'EDM');
  assert.equal(keywordDetect('unknown track', 'Kendrick Lamar').genre, 'Hip-Hop');
});

test('iTunes genres map only to existing EQ categories', () => {
  assert.equal(mapItunesGenre('Hip-Hop/Rap'), 'Hip-Hop');
  assert.equal(mapItunesGenre('Alternative'), 'Rock');
  assert.equal(mapItunesGenre('Singer/Songwriter'), 'Acoustic');
  assert.equal(mapItunesGenre('Unknown Category'), null);
});

test('iTunes result selection prefers matching track and artist', () => {
  const result = selectItunesTrack([
    { kind: 'song', trackName: 'Hello', artistName: 'Someone Else', primaryGenreName: 'Pop' },
    { kind: 'song', trackName: 'Hello', artistName: 'Adele', primaryGenreName: 'Pop' }
  ], 'Hello', 'Adele');

  assert.equal(result.artistName, 'Adele');
});

test('parametric conversion returns clamped eight-band gains', () => {
  const gains = parametricToEightBand([
    { type: 'PK', fc: 1000, gain: 30, q: 1 }
  ], 0);

  assert.equal(gains.length, 8);
  assert.ok(gains.every(gain => gain >= -12 && gain <= 12));
  assert.equal(gains[4], 12);
});
