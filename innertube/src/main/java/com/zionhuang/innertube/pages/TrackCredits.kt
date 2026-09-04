package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.response.TrackCreditsResponse

/**
 * Track credits as returned by YouTube Music.
 *
 * Section labels are deliberately not interpreted because they are localized. Run boundaries are
 * also retained verbatim; callers must not infer artist boundaries from punctuation or newlines.
 */
data class TrackCredits(
    val videoId: String,
    val titleRuns: List<Run>,
    val primaryArtistDisplayRuns: List<Run>,
    val sections: List<Section>,
) {
    data class Section(
        val titleRuns: List<Run>,
        val valueRuns: List<Run>,
    )

    companion object {
        internal fun fromResponse(response: TrackCreditsResponse): TrackCredits? {
            val dialog = response.onResponseReceivedActions.orEmpty()
                .firstNotNullOfOrNull { action ->
                    action.openPopupAction?.popup?.dismissableDialogRenderer
                } ?: return null
            val metadata = dialog.metadata?.musicMultiRowListItemRenderer ?: return null
            val titleRuns = metadata.title?.runs.orEmpty()
            val videoId = titleRuns.firstNotNullOfOrNull { run ->
                run.navigationEndpoint?.anyWatchEndpoint?.videoId
            } ?: return null

            return TrackCredits(
                videoId = videoId,
                titleRuns = titleRuns,
                primaryArtistDisplayRuns = metadata.subtitle?.runs.orEmpty(),
                sections = dialog.sections.orEmpty().mapNotNull { section ->
                    section.dismissableDialogContentSectionRenderer?.let { renderer ->
                        Section(
                            titleRuns = renderer.title?.runs.orEmpty(),
                            valueRuns = renderer.subtitle?.runs.orEmpty(),
                        )
                    }
                },
            )
        }
    }
}
