# Album first-track regression fixtures

Anonymous `WEB_REMIX` browse responses captured on **2026-09-19 at 13:03:47 UTC**
(22:03:47 JST), with `gl=JP`, `hl=ja`, client version `1.20250310.01.00`.
Both requests returned HTTP 200. No cookies, account settings, or supplied backup records were used.

| Fixture | Album browse ID | Tracks | First recording ID |
| --- | --- | ---: | --- |
| `chop-suey.json` | `MPREb_hscoNGfCKJW` | 2 | `-cid1qHuy_U` |
| `nevermind.json` | `MPREb_jPOYfjGgApr` | 13 | `ljUtuoFt-8c` |

The excerpts retain the album header, artist browse IDs, artwork, canonical playlist URL, and full
track shelf, including durations and unavailable-track indicators. Neither shelf had a continuation.
Menus, tracking parameters, visitor data, and playlist set-video IDs were removed.
The two JSON files are consumed by the production `AlbumPage` parser without network access.

`ProvidedAlbumTrackRepairTest` is opt-in (`verifyProvidedAlbumTracks=true`) and emulator-only.
It copies an externally staged `files/album-first-track/provided-song.db` (schema 27) into an isolated
Room database, verifies that migration to schema 28 preserves all original table data, then applies
these album pages. It checks track order and preservation of saved state and associations before
exporting `files/album-first-track/repaired-song.db`. The staged source is hash-checked and unchanged.
The provided database and generated export must remain outside version control.

Original response SHA-256 values (full responses remain in ignored diagnostic output):

- Chop Suey!: `9fa3fe2b1ffd940085650c5c7b76e000eb40e8c0607fb39aa8fd51df087ff430`
- Nevermind: `cb1427e98e2932fc886ce2e844552053a9d0f05e3ea0dcdfa19fcb50cb70cc61`
