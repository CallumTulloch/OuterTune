`nevermind-complete.json` is reduced from the saved 2026-09-20 WEB_REMIX responses
`actual-playlist-browse.raw.json` and `actual-playlist-next.raw.json` under
`build/diagnostics/album-song-language-20260919/provider-track-identity-probe`.

It preserves all 13 actual playlist entry IDs, both independently observed video IDs and browse titles,
playlist identity fields, the current endpoint and the trailing automix renderer. Tracking data,
unneeded UI fields and original image URLs were removed. Names and row order do not establish the
relationships tested here. The next response contains region-blocked rows with identity fields but
without title/byline/duration metadata; these omissions are preserved rather than filling in names.
