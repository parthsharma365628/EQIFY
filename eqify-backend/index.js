require('dotenv').config();
const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const fetch = require('node-fetch');
const { ANDROID_BANDS_HZ, parseParametricEQText, parametricToEightBand } =
  require('./lib/autoeq-converter');

const app = express();
app.use(cors());
app.use(express.json());

const AUTOEQ_PATH = path.join(__dirname, 'autoeq-results');

// ── Last.fm config ────────────────────────────────────────────────────
// Set LASTFM_API_KEY as an environment variable in Railway/Render.
// Get a free key at: https://www.last.fm/api/account/create
const LASTFM_API_KEY = process.env.LASTFM_API_KEY || '';
const LASTFM_BASE    = 'https://ws.audioscrobbler.com/2.0/';

// ── Artist genre cache ────────────────────────────────────────────────
// Key: lowercase artist name
// Value: { genre: string, cachedAt: timestamp }
// Cache TTL: 24 hours — artist genres rarely change, no need to re-fetch
const GENRE_CACHE = new Map();
const CACHE_TTL_MS = 24 * 60 * 60 * 1000;

function getCachedGenre(artistName) {
  const key   = artistName.toLowerCase().trim();
  const entry = GENRE_CACHE.get(key);
  if (!entry) return null;
  if (Date.now() - entry.cachedAt > CACHE_TTL_MS) {
    GENRE_CACHE.delete(key);
    return null;
  }
  return entry.genre;
}

function setCachedGenre(artistName, genre) {
  GENRE_CACHE.set(artistName.toLowerCase().trim(), {
    genre,
    cachedAt: Date.now()
  });
}

// ── Last.fm genre fetch ───────────────────────────────────────────────
// Calls artist.getinfo which returns top tags (crowdsourced genre labels)
// sorted by popularity. Response time is typically 100–300ms.
// Returns an array of tag name strings, or null on failure.
async function getLastFmTags(artistName, apiKey = LASTFM_API_KEY) {
  if (!apiKey) return null;
  try {
    const url = new URL(LASTFM_BASE);
    url.searchParams.set('method',  'artist.getinfo');
    url.searchParams.set('artist',  artistName);
    url.searchParams.set('api_key', apiKey);
    url.searchParams.set('format',  'json');
    url.searchParams.set('autocorrect', '1');  // handles minor spelling differences

    const res = await fetch(url.toString(), { timeout: 3000 });
    if (!res.ok) {
      console.warn('Last.fm HTTP ' + res.status + ' for "' + artistName + '"');
      return null;
    }
    const data = await res.json();

    // Last.fm returns { error: N, message: '...' } on failure
    if (data.error) {
      console.warn(`Last.fm error for "${artistName}": ${data.message}`);
      return null;
    }

    const tags = data?.artist?.tags?.tag;
    if (!tags || tags.length === 0) return null;

    // tags is an array of { name, url } — extract just the names
    return tags.map(t => t.name.toLowerCase());

  } catch (e) {
    console.warn(`Last.fm fetch failed for "${artistName}": ${e.message}`);
    return null;
  }
}

// ── Last.fm tag → EQify genre mapping ────────────────────────────────
// Last.fm tags are crowdsourced and granular (e.g. "melodic death metal",
// "chillwave", "lo-fi hip hop"). Map them to our 10 EQ categories.
// Order matters — more specific patterns before broad ones.
function mapLastFmTags(tags) {
  const joined = tags.join(' ');

  if (/afrobeats|afropop|afro|nigeria|ghana|naija/.test(joined))            return 'Afrobeats';
  if (/hip.?hop|rap|trap|drill|grime|boom.?bap/.test(joined))              return 'Hip-Hop';
  if (/r&b|rnb|neo.?soul|contemporary r/.test(joined))                     return 'R&B';
  if (/edm|house|techno|trance|electronic|dance|dubstep|drum.?and.?bass|dnb|ambient electronic/.test(joined)) return 'EDM';
  if (/classical|orchestra|symphony|chamber|opera|baroque|contemporary classical/.test(joined)) return 'Classical';
  if (/metal|hardcore|deathcore|metalcore/.test(joined))                   return 'Metal';
  if (/rock|punk|grunge|indie rock|alternative rock|post.?rock/.test(joined)) return 'Rock';
  if (/jazz|bebop|swing|bossa nova|jazz fusion/.test(joined))               return 'Jazz';
  if (/blues/.test(joined))                                                 return 'Blues';
  if (/latin|reggaeton|salsa|bachata|cumbia|corrido|bossa/.test(joined))    return 'Latin';
  if (/k.?pop/.test(joined))                                                return 'K-Pop';
  if (/country|folk|bluegrass|americana|singer.?songwriter/.test(joined))   return 'Country';
  if (/ambient|lo.?fi|chillout|chillhop|downtempo|new age/.test(joined))   return 'Ambient';
  if (/soul|funk/.test(joined))                                              return 'Soul';
  if (/pop/.test(joined))                                                    return 'Pop';

  return null;
}

function mapItunesGenre(primaryGenreName) {
  if (typeof primaryGenreName !== 'string') return null;
  const normalized = primaryGenreName.trim().toLowerCase();
  if (!normalized) return null;
  if (normalized.includes('alternative')) return 'Rock';
  if (normalized.includes('singer/songwriter')) return 'Acoustic';
  return mapLastFmTags([normalized]);
}

function normalizeCatalogText(value) {
  return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function selectItunesTrack(results, trackName, artistName) {
  if (!Array.isArray(results)) return null;
  const wantedTrack = normalizeCatalogText(trackName);
  const wantedArtist = normalizeCatalogText(artistName);

  return results
    .filter(item => item && item.kind === 'song' && item.primaryGenreName)
    .map(item => {
      const resultTrack = normalizeCatalogText(item.trackName);
      const resultArtist = normalizeCatalogText(item.artistName);
      let score = 0;
      if (wantedTrack && resultTrack === wantedTrack) score += 6;
      else if (wantedTrack && resultTrack.includes(wantedTrack)) score += 3;
      if (wantedArtist && resultArtist === wantedArtist) score += 4;
      else if (wantedArtist && resultArtist.includes(wantedArtist)) score += 2;
      return { item, score };
    })
    .sort((a, b) => b.score - a.score)[0]?.item || null;
}

async function getItunesGenre(trackName, artistName) {
  try {
    const url = new URL('https://itunes.apple.com/search');
    url.searchParams.set('term', [trackName, artistName].filter(Boolean).join(' '));
    url.searchParams.set('country', 'US');
    url.searchParams.set('media', 'music');
    url.searchParams.set('entity', 'song');
    url.searchParams.set('limit', '5');

    const response = await fetch(url.toString(), { timeout: 3000 });
    if (!response.ok) {
      console.warn('iTunes HTTP ' + response.status + ' for genre lookup');
      return null;
    }
    const data = await response.json();
    const match = selectItunesTrack(data?.results, trackName, artistName);
    return match ? mapItunesGenre(match.primaryGenreName) : null;
  } catch (error) {
    console.warn('iTunes genre lookup failed: ' + error.message);
    return null;
  }
}

// ── Keyword fallback ──────────────────────────────────────────────────
// Used when Last.fm is unavailable or returns no usable tags.
const GENRE_RULES = [
  { keywords: ['hip hop', 'hip-hop', 'trap', 'drill', 'lil ', 'young ', 'uzi',
               'travis scott', 'kendrick', 'j. cole', 'j cole', 'drake', 'future',
               'offset', 'cardi', 'nicki', '21 savage', 'metro boomin', 'playboi',
               'asap', 'a$ap', 'wiz khalifa'], genre: 'Hip-Hop' },
  { keywords: ['r&b', 'rnb', 'soul', 'weeknd', 'the weekend', 'frank ocean', 'sza',
               'khalid', 'bryson tiller', 'summer walker', 'daniel caesar',
               'h.e.r', 'giveon', 'usher', 'alicia keys', 'john legend'], genre: 'R&B' },
  { keywords: ['edm', 'house', 'techno', 'trance', 'dubstep', 'drum and bass', 'dnb',
               'electronic', 'avicii', 'martin garrix', 'tiesto', 'deadmau5',
               'skrillex', 'calvin harris', 'marshmello', 'flume', 'daft punk'], genre: 'EDM' },
  { keywords: ['classical', 'orchestra', 'symphony', 'beethoven', 'mozart', 'bach',
               'chopin', 'handel', 'vivaldi', 'brahms', 'concerto', 'sonata'], genre: 'Classical' },
  { keywords: ['metal', 'metallica', 'slayer', 'megadeth', 'pantera', 'tool', 'korn',
               'linkin park', 'slipknot', 'death metal', 'black metal'], genre: 'Metal' },
  { keywords: ['rock', 'punk', 'grunge', 'nirvana', 'foo fighters', 'radiohead',
               'arctic monkeys', 'the strokes', 'green day', 'blink-182',
               'acdc', 'ac/dc', 'led zeppelin', 'queen', 'tame impala'], genre: 'Rock' },
  { keywords: ['jazz', 'blues', 'miles davis', 'coltrane', 'duke ellington',
               'charlie parker', 'thelonious monk', 'herbie hancock'], genre: 'Jazz' },
  { keywords: ['latin', 'reggaeton', 'bad bunny', 'j balvin', 'maluma', 'ozuna',
               'daddy yankee', 'rauw alejandro', 'karol g', 'shakira'], genre: 'Latin' },
  { keywords: ['k-pop', 'kpop', 'bts', 'blackpink', 'twice', 'exo',
               'stray kids', 'nct', 'ive', 'aespa', 'seventeen'], genre: 'K-Pop' },
  { keywords: ['pop', 'taylor swift', 'ed sheeran', 'ariana grande', 'dua lipa',
               'billie eilish', 'harry styles', 'olivia rodrigo', 'post malone',
               'justin bieber', 'selena gomez', 'doja cat', 'sabrina carpenter'], genre: 'Pop' },
];

function keywordDetect(track, artist) {
  const combined = `${track} ${artist}`.toLowerCase();
  for (const rule of GENRE_RULES) {
    for (const keyword of rule.keywords) {
      if (combined.includes(keyword)) return { genre: rule.genre, source: 'keyword-match' };
    }
  }
  return { genre: 'Pop', source: 'default-fallback' };
}

// ── AutoEQ headphone scanning ─────────────────────────────────────────

function getAllHeadphones() {
  const headphones = [];
  let sources;
  try { sources = fs.readdirSync(AUTOEQ_PATH); }
  catch (e) { console.error('Cannot read AutoEQ path:', AUTOEQ_PATH, e.message); return headphones; }

  for (const source of sources) {
    const sourcePath = path.join(AUTOEQ_PATH, source);
    try { if (!fs.statSync(sourcePath).isDirectory()) continue; } catch (e) { continue; }
    let types;
    try { types = fs.readdirSync(sourcePath); } catch (e) { continue; }

    for (const type of types) {
      const typePath = path.join(sourcePath, type);
      try { if (!fs.statSync(typePath).isDirectory()) continue; } catch (e) { continue; }
      let names;
      try { names = fs.readdirSync(typePath); } catch (e) { continue; }

      for (const name of names) {
        try {
          const hp = path.join(typePath, name);
          if (!fs.statSync(hp).isDirectory()) continue;
          const eqFile = path.join(hp, `${name} ParametricEQ.txt`);
          if (fs.existsSync(eqFile)) {
            headphones.push({ name, source, type, path: `${source}/${type}/${name}` });
          }
        } catch (e) { continue; }
      }
    }
  }
  return headphones.sort((a, b) =>
    a.name.localeCompare(b.name) ||
    a.source.localeCompare(b.source) ||
    a.type.localeCompare(b.type)
  );
}

function dedupeHeadphones(headphones) {
  const seen = new Set();
  return headphones.filter(headphone => {
    const key = headphone.name.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

let headphoneCache = null;
function getHeadphones() {
  if (!headphoneCache) {
    console.log('Scanning AutoEQ database...');
    headphoneCache = getAllHeadphones();
    console.log(`Found ${headphoneCache.length} headphones`);
  }
  return headphoneCache;
}

function parseParametricEQ(filePath) {
  return parseParametricEQText(fs.readFileSync(filePath, 'utf8'));
}

// ── API endpoints ─────────────────────────────────────────────────────

app.get('/api/headphones', (req, res) => {
  const search = typeof req.query.search === 'string'
    ? req.query.search.toLowerCase().trim()
    : '';
  let list = getHeadphones();
  if (search) list = list.filter(h => h.name.toLowerCase().includes(search));
  list = dedupeHeadphones(list);
  res.json(list.slice(0, 50).map(h => ({ name: h.name, type: h.type })));
});

app.get('/api/eq/:headphoneName', (req, res) => {
  const searchName = decodeURIComponent(req.params.headphoneName).trim();
  const all   = getHeadphones();
  const match =
    all.find(h => h.name === searchName) ||
    all.find(h => h.name.toLowerCase() === searchName.toLowerCase());

  if (!match) return res.status(404).json({ error: `Headphone not found: ${searchName}` });

  const filePath = path.join(AUTOEQ_PATH, match.path, `${match.name} ParametricEQ.txt`);
  try {
    const { preamp, filters } = parseParametricEQ(filePath);
    const gains = parametricToEightBand(filters, preamp);
    res.json({
      headphone: match.name,
      bands: ANDROID_BANDS_HZ.map((hz, i) => ({ frequencyHz: hz, gainDb: gains[i] }))
    });
  } catch (e) {
    console.error(`EQ parse error for ${match.name}:`, e.message);
    res.status(500).json({ error: e.message });
  }
});

/**
 * POST /api/v1/genre
 *
 * Detection priority:
 *   1. In-memory cache     — instant, 24h TTL per artist
 *   2. Last.fm API         — 100–300ms, accurate crowdsourced tags
 *   3. iTunes Search API   — track metadata, no API key required
 *   4. Keyword fallback    — instant, covers known artists
 *   5. Default             — returns "Pop"
 *
 * The cache means repeat listens to the same artist are instant.
 * A user who listens to 20 Drake songs in a row only calls Last.fm once.
 */
app.post('/api/v1/genre', async (req, res) => {
  const track = typeof req.body?.track === 'string' ? req.body.track.trim() : '';
  const artist = typeof req.body?.artist === 'string' ? req.body.artist.trim() : '';
  if (!track && !artist) return res.status(400).json({ error: 'track or artist required' });

  const cleanArtist = artist.split('•')[0].split('·')[0].trim();

  // Split multi-artist strings like "Burna Boy, Dave" or "Drake ft. 21 Savage"
  // Try each artist individually so cache hits and keyword matches work correctly
  const artistParts = cleanArtist
    ? cleanArtist.split(/[,&]| ft\.? | feat\.? | x /i).map(a => a.trim()).filter(Boolean)
    : [track];

  // Try each artist in order — return on first match
  for (const singleArtist of artistParts) {

    // 1. Cache hit — return immediately
    const cached = getCachedGenre(singleArtist);
    if (cached) {
      console.log(`Cache hit: "${singleArtist}" → ${cached}`);
      return res.json({ genre: cached, source: 'cache' });
    }

    // 2. Last.fm — most accurate, tried first for every artist
    if (LASTFM_API_KEY) {
      const tags = await getLastFmTags(singleArtist);
      if (tags && tags.length > 0) {
        const mapped = mapLastFmTags(tags);
        if (mapped) {
          console.log(`Last.fm: "${singleArtist}" [${tags.slice(0,3).join(', ')}] → ${mapped}`);
          setCachedGenre(singleArtist, mapped);
          return res.json({ genre: mapped, source: 'lastfm' });
        }
      }
    }

    // 3. iTunes — track-level metadata; no API key required
    const itunesGenre = await getItunesGenre(track, singleArtist);
    if (itunesGenre) {
      console.log('iTunes: "' + track + '" by "' + singleArtist + '" → ' + itunesGenre);
      return res.json({ genre: itunesGenre, source: 'itunes' });
    }

    // 4. Keyword fallback — used when remote metadata is unavailable
    const keywordResult = keywordDetect(track, singleArtist);
    if (keywordResult.source === 'keyword-match') {
      console.log(`Keyword fallback: "${singleArtist}" → ${keywordResult.genre}`);
      setCachedGenre(singleArtist, keywordResult.genre);
      return res.json(keywordResult);
    }
  }

  // 5. Default fallback
  console.log(`Unknown artist: "${cleanArtist}" — defaulting to Pop`);
  res.json({ genre: 'Pop', source: 'default-fallback' });
});

// ── Cache stats endpoint (useful for debugging) ───────────────────────
app.get('/api/cache/stats', (req, res) => {
  res.json({
    genreCacheSize: GENRE_CACHE.size,
    headphoneCacheSize: headphoneCache ? headphoneCache.length : 0,
    entries: Array.from(GENRE_CACHE.entries()).map(([k, v]) => ({
      artist: k,
      genre: v.genre,
      ageMinutes: Math.round((Date.now() - v.cachedAt) / 60000)
    }))
  });
});

function startServer(port = Number(process.env.PORT) || 3000) {
  return app.listen(port, () => {
    console.log('EQify backend running at http://localhost:' + port);
    if (!LASTFM_API_KEY) {
      console.warn('LASTFM_API_KEY not set — genre detection will use iTunes and keyword fallback');
    }
    getHeadphones();
  });
}

if (require.main === module) startServer();

module.exports = {
  app,
  dedupeHeadphones,
  keywordDetect,
  mapItunesGenre,
  mapLastFmTags,
  parametricToEightBand,
  parseParametricEQ,
  selectItunesTrack,
  startServer
};
