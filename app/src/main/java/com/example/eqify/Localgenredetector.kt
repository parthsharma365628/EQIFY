package com.example.eqify

/**
 * On-device genre detector using keyword + artist name matching.
 *
 * Used as a fallback when the backend is unreachable (no network, server down,
 * or still pointing at the emulator localhost address).
 *
 * Rules are checked in order — first match wins. More specific rules
 * (metal, k-pop) come before broader ones (rock, pop) so they don't get
 * swallowed by the generic catch-all.
 */
object LocalGenreDetector {

    private data class Rule(val keywords: List<String>, val genre: String)

    private val rules = listOf(
        Rule(listOf(
            "hip hop", "hip-hop", "trap", "drill", "lil ", "young ",
            "uzi", "travis scott", "kendrick", "j. cole", "j cole",
            "drake", "future", "offset", "cardi", "nicki", "21 savage",
            "metro boomin", "playboi", "asap", "a\$ap", "wiz khalifa",
            "tyler the creator", "earl sweatshirt", "chance the rapper",
            "childish gambino", "joey bada", "flatbush"
        ), "Hip-Hop"),

        Rule(listOf(
            "r&b", "rnb", "weeknd", "the weekend", "frank ocean", "sza",
            "khalid", "bryson tiller", "summer walker", "daniel caesar",
            "h.e.r", "giveon", "usher", "alicia keys", "john legend",
            "miguel", "anderson paak", "jhene aiko", "kehlani", "tinashe"
        ), "R&B"),

        Rule(listOf(
            "edm", "house", "techno", "trance", "dubstep", "drum and bass",
            "dnb", "electronic", "avicii", "martin garrix", "tiesto",
            "deadmau5", "skrillex", "calvin harris", "marshmello", "flume",
            "illenium", "disclosure", "daft punk", "aphex twin", "burial",
            "four tet", "bicep", "fred again"
        ), "EDM"),

        Rule(listOf(
            "classical", "orchestra", "symphony", "beethoven", "mozart",
            "bach", "chopin", "handel", "vivaldi", "brahms", "schubert",
            "debussy", "ravel", "concerto", "sonata", "nocturne",
            "philharmonic", "tchaikovsky", "mahler", "sibelius"
        ), "Classical"),

        Rule(listOf(
            "metal", "metallica", "slayer", "megadeth", "pantera",
            "lamb of god", "system of a down", "tool", "korn",
            "linkin park", "slipknot", "avenged sevenfold", "a7x",
            "death metal", "black metal", "heavy metal", "iron maiden",
            "judas priest", "black sabbath", "ozzy"
        ), "Metal"),

        Rule(listOf(
            "rock", "punk", "grunge", "nirvana", "foo fighters", "radiohead",
            "arctic monkeys", "the strokes", "green day", "blink-182",
            "acdc", "ac/dc", "led zeppelin", "rolling stones", "queen",
            "oasis", "blur", "the killers", "muse", "tame impala",
            "vampire weekend", "LCD soundsystem", "pixies", "sonic youth"
        ), "Rock"),

        Rule(listOf(
            "jazz", "miles davis", "john coltrane", "coltrane",
            "duke ellington", "charlie parker", "thelonious monk",
            "herbie hancock", "bill evans", "norah jones", "diana krall",
            "swing", "bebop", "chet baker", "dave brubeck", "mingus"
        ), "Jazz"),

        Rule(listOf(
            "blues", "bb king", "muddy waters", "robert johnson",
            "john lee hooker", "stevie ray vaughan", "eric clapton blues"
        ), "Blues"),

        Rule(listOf(
            "latin", "reggaeton", "bad bunny", "j balvin", "maluma",
            "ozuna", "daddy yankee", "rauw alejandro", "karol g",
            "shakira", "salsa", "bachata", "cumbia", "corrido",
            "peso pluma", "natanael cano", "feid", "anuel"
        ), "Latin"),

        Rule(listOf(
            "afrobeats", "afropop", "burna boy", "wizkid", "davido",
            "rema", "ckay", "tems", "omah lay", "fireboy"
        ), "Afrobeats"),

        Rule(listOf(
            "k-pop", "kpop", "bts", "blackpink", "twice", "exo",
            "stray kids", "nct", "ive", "aespa", "seventeen",
            "txt", "itzy", "newjeans", "le sserafim"
        ), "K-Pop"),

        Rule(listOf(
            "soul", "funk", "james brown", "aretha franklin", "marvin gaye",
            "stevie wonder", "curtis mayfield", "al green", "otis redding",
            "sam cooke", "parliament", "funkadelic", "sly and the family"
        ), "Soul"),

        Rule(listOf(
            "country", "folk", "acoustic", "johnny cash", "willie nelson",
            "dolly parton", "kacey musgraves", "tyler childers",
            "zach bryan", "morgan wallen", "luke combs", "taylor swift country",
            "bluegrass", "americana"
        ), "Country"),

        Rule(listOf(
            "ambient", "lo-fi", "lofi", "chill", "chillhop",
            "study", "sleep", "focus", "meditation", "brian eno",
            "boards of canada", "bonobo", "nujabes"
        ), "Ambient"),

        Rule(listOf(
            "podcast", "audiobook", "spoken word", "comedy", "talk"
        ), "Podcast"),

        // Pop is last — broad catch-all for anything that contains "pop"
        // or major mainstream artists not matched above
        Rule(listOf(
            "pop", "taylor swift", "ed sheeran", "ariana grande", "dua lipa",
            "billie eilish", "harry styles", "olivia rodrigo", "post malone",
            "justin bieber", "selena gomez", "shawn mendes", "camila cabello",
            "charlie puth", "lizzo", "doja cat", "sabrina carpenter",
            "chappell roan", "gracie abrams", "sza pop", "the 1975",
            "lorde", "halsey", "sia", "adele", "sam smith", "sam mendes"
        ), "Pop"),
    )

    /**
     * Returns the best-guess genre for [track] + [artist].
     * Concatenates both strings, lowercases, and checks each rule in order.
     * Returns "Pop" if nothing matches.
     */
    fun detect(track: String, artist: String): String {
        val combined = "${track.lowercase()} ${artist.lowercase()}"
        for (rule in rules) {
            for (keyword in rule.keywords) {
                if (combined.contains(keyword)) return rule.genre
            }
        }
        return "Pop"
    }
}