package app.cyfer.streaming.android.data.tmdb

object TmdbConfig {
    const val BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJkNDU2ODUyZDA1OTExYTczMDMxMWM0ZGE4ZjYxNTQ1YiIsIm5iZiI6MTc2OTYyMjYyMS43NDgsInN1YiI6IjY5N2E0YzVkNTdiNTUxMzBmNjkzZTZkZCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.qsHIBbC1LmFOVtxS8XdBmFuU-FwOQUn4YGqRKBwvkiQ"

    const val BASE_URL = "https://api.themoviedb.org/3/"
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
    const val POSTER_W500 = "${IMAGE_BASE}w500"
    const val BACKDROP_W1280 = "${IMAGE_BASE}w1280"
}
