package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

/** Metadata learned during one track-credit lookup; album identity remains separate from artists. */
@Serializable
data class ArtistCreditResolution(
    val credit: ArtistCredit,
    val album: Album?,
) : java.io.Serializable
