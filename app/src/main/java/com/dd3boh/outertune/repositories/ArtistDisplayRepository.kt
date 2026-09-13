package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publishes database-backed display identities without changing tags or fetching remote content. */
@Singleton
class ArtistDisplayRepository internal constructor(
    private val mappings: Flow<List<ArtistDisplayMapping>>,
    private val scope: CoroutineScope,
    private val publish: suspend (List<ArtistDisplayMapping>) -> Unit,
) {
    @Inject
    constructor(database: MusicDatabase) : this(
        database.artistDisplayMappings(),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { values -> withContext(Dispatchers.Main) { ArtistDisplayProjection.publish(values) } },
    )

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { mappings.distinctUntilChanged().collect { publish(it) } }
    }
}
