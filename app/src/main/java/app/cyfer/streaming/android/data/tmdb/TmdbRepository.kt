package app.cyfer.streaming.android.data.tmdb

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object TmdbRepository {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${TmdbConfig.BEARER_TOKEN}")
                    .addHeader("accept", "application/json")
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    private val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(TmdbConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    suspend fun getTrendingMovies(page: Int = 1): List<TmdbItem> =
        api.getTrendingMovies(page).results

    suspend fun getTrendingShows(page: Int = 1): List<TmdbItem> =
        api.getTrendingShows(page).results

    suspend fun getPopularMovies(page: Int = 1): List<TmdbItem> =
        api.getPopularMovies(page).results

    suspend fun getPopularShows(page: Int = 1): List<TmdbItem> =
        api.getPopularShows(page).results

    suspend fun getNowPlaying(page: Int = 1): List<TmdbItem> =
        api.getNowPlaying(page).results

    suspend fun search(query: String, page: Int = 1): List<TmdbItem> =
        api.searchMulti(query, page).results

    suspend fun getMovieDetails(id: Int): TmdbDetailResponse =
        api.getMovieDetails(id)

    suspend fun getTvDetails(id: Int): TmdbDetailResponse =
        api.getTvDetails(id)

    /** Cheap logo-only fetch used by the home Hero to overlay a logo on the backdrop. */
    suspend fun getBestLogoUrl(id: Int, mediaType: String): String? = try {
        val images = if (mediaType == "tv") api.getTvImages(id) else api.getMovieImages(id)
        images.bestLogoUrl
    } catch (_: Throwable) {
        null
    }

    /** Full episode list for one season of a TV show. */
    suspend fun getTvSeason(tvId: Int, seasonNumber: Int): List<TmdbEpisode> =
        api.getTvSeason(tvId, seasonNumber).episodes

    // ── Catalog / discovery / browse ────────────────────────────────

    suspend fun discoverMovies(
        page: Int,
        genreId: Int? = null,
        year: Int? = null,
        sortBy: String = "popularity.desc",
    ): TmdbResponse = api.discoverMovies(
        page = page,
        withGenres = genreId?.toString(),
        year = year,
        sortBy = sortBy,
    )

    suspend fun discoverTv(
        page: Int,
        genreId: Int? = null,
        year: Int? = null,
        sortBy: String = "popularity.desc",
    ): TmdbResponse = api.discoverTv(
        page = page,
        withGenres = genreId?.toString(),
        year = year,
        sortBy = sortBy,
    )

    suspend fun getMovieGenres(): List<TmdbGenre> = api.getMovieGenres().genres
    suspend fun getTvGenres(): List<TmdbGenre> = api.getTvGenres().genres

    suspend fun getTrendingMoviesPaged(page: Int): TmdbResponse = api.getTrendingMoviesPaged(page)
    suspend fun getTrendingShowsPaged(page: Int): TmdbResponse = api.getTrendingShowsPaged(page)
    suspend fun getPopularMoviesPaged(page: Int): TmdbResponse = api.getPopularMoviesPaged(page)
    suspend fun getPopularShowsPaged(page: Int): TmdbResponse = api.getPopularShowsPaged(page)
    suspend fun getNowPlayingPaged(page: Int): TmdbResponse = api.getNowPlayingPaged(page)

    suspend fun getPersonDetails(id: Int): TmdbPerson = api.getPersonDetails(id)
    suspend fun getPersonCombinedCredits(id: Int): TmdbCombinedCredits = api.getPersonCombinedCredits(id)

    suspend fun getMovieRecommendations(id: Int): List<TmdbItem> =
        api.getMovieRecommendations(id).results
    suspend fun getTvRecommendations(id: Int): List<TmdbItem> =
        api.getTvRecommendations(id).results
}
