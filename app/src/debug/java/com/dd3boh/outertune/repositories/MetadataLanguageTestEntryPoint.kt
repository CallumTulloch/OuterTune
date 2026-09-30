package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Allows instrumentation to exercise the running application's repository with its real database. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MetadataLanguageTestEntryPoint {
    fun database(): MusicDatabase
    fun downloadUtil(): com.dd3boh.outertune.playback.DownloadUtil
}
