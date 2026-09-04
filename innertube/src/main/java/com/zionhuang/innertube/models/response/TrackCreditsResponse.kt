package com.zionhuang.innertube.models.response

import com.zionhuang.innertube.models.Runs
import kotlinx.serialization.Serializable

/** Minimal response model for a MUSIC_PAGE_TYPE_TRACK_CREDITS browse endpoint. */
@Serializable
data class TrackCreditsResponse(
    val onResponseReceivedActions: List<ResponseAction>? = null,
) {
    @Serializable
    data class ResponseAction(
        val openPopupAction: OpenPopupAction? = null,
    )

    @Serializable
    data class OpenPopupAction(
        val popup: Popup? = null,
    )

    @Serializable
    data class Popup(
        val dismissableDialogRenderer: DismissableDialogRenderer? = null,
    )

    @Serializable
    data class DismissableDialogRenderer(
        val sections: List<Section>? = null,
        val metadata: Metadata? = null,
    )

    @Serializable
    data class Section(
        val dismissableDialogContentSectionRenderer: ContentSectionRenderer? = null,
    )

    @Serializable
    data class ContentSectionRenderer(
        val title: Runs? = null,
        val subtitle: Runs? = null,
    )

    @Serializable
    data class Metadata(
        val musicMultiRowListItemRenderer: MusicMultiRowListItemRenderer? = null,
    )

    @Serializable
    data class MusicMultiRowListItemRenderer(
        val title: Runs? = null,
        val subtitle: Runs? = null,
    )
}
