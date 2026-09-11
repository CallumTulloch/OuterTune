package com.dd3boh.outertune.playback

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource

/** Resolve each cache miss, including a hole reached after a cached prefix in the same read. */
internal fun createPlaybackCacheDataSourceFactory(
    downloadCache: Cache,
    playerCache: Cache,
    upstreamFactory: DataSource.Factory,
    resolver: ResolvingDataSource.Resolver,
    cacheWriteDataSinkFactory: DataSink.Factory = CacheDataSink.Factory().setCache(playerCache),
): CacheDataSource.Factory = CacheDataSource.Factory()
    .setCache(downloadCache)
    .setUpstreamDataSourceFactory(
        createResolvingPlayerCacheDataSourceFactory(playerCache, upstreamFactory, resolver, cacheWriteDataSinkFactory)
    )
    .setCacheWriteDataSinkFactory(null)
    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

internal fun createResolvingPlayerCacheDataSourceFactory(
    playerCache: Cache,
    upstreamFactory: DataSource.Factory,
    resolver: ResolvingDataSource.Resolver,
    cacheWriteDataSinkFactory: DataSink.Factory = CacheDataSink.Factory().setCache(playerCache),
): CacheDataSource.Factory = CacheDataSource.Factory()
    .setCache(playerCache)
    .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(upstreamFactory, resolver))
    .setCacheWriteDataSinkFactory(cacheWriteDataSinkFactory)
    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
