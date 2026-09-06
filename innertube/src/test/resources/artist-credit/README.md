# Artist credit response fixtures

Extracted from anonymous WEB_REMIX responses captured on 2026-09-05 with client
version `1.20250310.01.00`, region JP. These are reduced captures, not invented
representations of the target response. Tracking/accessibility fields, irrelevant
menu actions and redundant thumbnail sizes were removed. Name text, Run boundaries,
page types, IDs and contributor roles are unchanged. No image is downloaded by tests.

The full original captures and request/hash metadata are kept in the ignored research
directory `build/artist-response-research-20260905`; the investigation is documented in
`docs/verification/records/2026-09-05-artist-response-investigation.md`.

| Fixture | Captured source / extraction |
| --- | --- |
| target-queue.json | target-queue-ja.raw.json, queueDatas[0].content.playlistPanelVideoRenderer; TSZhKssbW2g |
| duet-queue.json | control-duet-queue-ja.raw.json, same path; Lady Gaga / Bruno Mars |
| band-queue.json | control-band-queue-en.raw.json, same path; Earth, Wind & Fire, English |
| target-search-card.json | target-search-all-ja.raw.json, first search section's musicCardShelfRenderer |
| target-search-row.json | target-search-songs-ja.raw.json, contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents[0].musicShelfRenderer.contents[0].musicResponsiveListItemRenderer; TSZhKssbW2g, ATV |
| video-search-row.json | target-search-all-ja.raw.json, contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents[4].itemSectionRenderer.contents[0].musicResponsiveListItemRenderer; -ntcgCgg11c, UGC |
| target-credits.json | target-credits-ja.raw.json, credits dialog for TSZhKssbW2g; Japanese |
| target-artist.json | target-artist-ja.raw.json, artist header title and canonical URL; 8082Audio |
| target-album-row.json | cross-8082-soundtrack-album-ja.raw.json, soundtrack musicShelfRenderer.contents[16].musicResponsiveListItemRenderer; 6zUV1aBPicg |
| duet-album-row.json | same soundtrack, contents[0].musicResponsiveListItemRenderer; 陈鸿宇 / 8082Audio |

Tests explicitly label altered captures and artificial boundary examples. In particular,
two independently supplied artist names both without IDs is a supported synthetic case;
it was not observed in the normal byline fields of the captured sample. Credits contain
two independent unlinked performer names, which is a different response field and role.

Search type tests use the actual search-row captures above; album-row captures have no
per-track thumbnail and rely on their parent album artwork. OMV search coverage is an
explicit synthetic type-only mutation of the captured UGC search row.
