`nevermind-complete.json` is reduced from the saved 2026-09-20 WEB_REMIX responses
`actual-playlist-browse.raw.json` and `actual-playlist-next.raw.json` under
`build/diagnostics/album-song-language-20260919/provider-track-identity-probe`.

It preserves all 13 actual playlist entry IDs, both independently observed video IDs and browse titles,
playlist identity fields, the current endpoint and the trailing automix renderer. Tracking data,
unneeded UI fields and original image URLs were removed. Names and row order do not establish the
relationships tested here. The next response contains region-blocked rows with identity fields but
without title/byline/duration metadata; these omissions are preserved rather than filling in names.

`1989-deluxe-restricted.json` is reduced from the saved 2026-09-27 English/JP WEB_REMIX
`playlist-browse.raw.json` and `playlist-next.raw.json` under
`build/diagnostics/1989-display-20260927/raw`. It retains the actual 19 browse entry identities,
16 next identities, names and terminal response shape. The three Voice Memo browse entries have
the explicit `MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT` policy, no playback endpoint, and no
corresponding row in the finite next queue. This is a partial identity observation, not a 19-track
mapping. Tracking, unrelated UI properties and original image URLs were removed; all retained
identity endpoints and availability markers come from the responses, not name or order matching.

When reducing future fixtures, retain every row wrapper and its original position, including
informational messages, automix and continuation rows. Filtering the queue to song renderers
can hide a production rejection even when every retained track identity is correct. Compare
the reduced fixture and the complete saved response with the same parser before accepting it.
The complete next row sequence is retained, including its `playlistExpandableMessageRenderer`
availability notice with the canonical playlist link and the trailing automix renderer. The notice
itself is not restriction evidence and cannot authorize a missing identity. An earlier reduced
fixture incorrectly filtered this wrapper out; the full raw response exposed that parser failure.
