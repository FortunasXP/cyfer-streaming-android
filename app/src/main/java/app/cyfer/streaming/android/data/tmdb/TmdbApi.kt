package app.cyfer.streaming.android.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Query

interface TmdbApi {

    @GET("trending/movie/day")
    suspend fun getTrendingMovies(
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("trending/tv/day")
    suspend fun getTrendingShows(
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("tv/popular")
    suspend fun getPopularShows(
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("movie/now_playing")
    suspend fun getNowPlaying(
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("movie/{id}")
    suspend fun getMovieDetails(
        @retrofit2.http.Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,similar,external_ids,images",
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): TmdbDetailResponse

    @GET("tv/{id}")
    suspend fun getTvDetails(
        @retrofit2.http.Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,similar,external_ids,images",
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): TmdbDetailResponse

    @GET("movie/{id}/images")
    suspend fun getMovieImages(
        @retrofit2.http.Path("id") id: Int,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): TmdbImages

    @GET("tv/{id}/images")
    suspend fun getTvImages(
        @retrofit2.http.Path("id") id: Int,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): TmdbImages

    @GET("tv/{id}/season/{season}")
    suspend fun getTvSeason(
        @retrofit2.http.Path("id") id: Int,
        @retrofit2.http.Path("season") seasonNumber: Int
    ): TmdbSeasonResponse

    // ── Discovery + genre browsing ──────────────────────────────────

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("page") page: Int = 1,
        @Query("with_genres") withGenres: String? = null,
        @Query("primary_release_year") year: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbResponse

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("page") page: Int = 1,
        @Query("with_genres") withGenres: String? = null,
        @Query("first_air_date_year") year: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbResponse

    @GET("genre/movie/list")
    suspend fun getMovieGenres(): TmdbGenreList

    @GET("genre/tv/list")
    suspend fun getTvGenres(): TmdbGenreList

    @GET("trending/movie/day")
    suspend fun getTrendingMoviesPaged(@Query("page") page: Int): TmdbResponse

    @GET("trending/tv/day")
    suspend fun getTrendingShowsPaged(@Query("page") page: Int): TmdbResponse

    @GET("movie/popular")
    suspend fun getPopularMoviesPaged(@Query("page") page: Int): TmdbResponse

    @GET("tv/popular")
    suspend fun getPopularShowsPaged(@Query("page") page: Int): TmdbResponse

    @GET("movie/now_playing")
    suspend fun getNowPlayingPaged(@Query("page") page: Int): TmdbResponse

    @GET("movie/{id}/recommendations")
    suspend fun getMovieRecommendations(@retrofit2.http.Path("id") id: Int): TmdbResponse

    @GET("tv/{id}/recommendations")
    suspend fun getTvRecommendations(@retrofit2.http.Path("id") id: Int): TmdbResponse

    @GET("person/{id}")
    suspend fun getPersonDetails(@retrofit2.http.Path("id") id: Int): TmdbPerson

    @GET("person/{id}/combined_credits")
    suspend fun getPersonCombinedCredits(@retrofit2.http.Path("id") id: Int): TmdbCombinedCredits
}
