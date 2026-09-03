# コンテンツの由来と `isLocal`

`isLocal` の意味はEntityごとに異なるため、どのEntityの値かを区別して扱う。

| Entity | `isLocal = true` の意味 | オンライン曲のダウンロード |
| --- | --- | --- |
| `SongEntity` | 端末のフォルダから検出した曲 | `isLocal = false` のまま。`dateDownload` と `localPath` で管理する |
| `AlbumEntity` | フォルダ曲のメタデータから生成したアルバム | ダウンロードしてもローカル扱いにはしない |
| `ArtistEntity` | フォルダ曲のメタデータから生成したアーティスト | ダウンロードしてもローカル扱いにはしない |
| `PlaylistEntity` | YouTube Music側と同期せず、OuterTune内だけで管理する再生リスト | 収録曲の由来とは無関係 |

ライブラリの絞り込みは、次の意味とする。

- **ライブラリ**: 従来どおりフォルダ曲を含む。
- **ダウンロード済み**: ダウンロードしたオンライン曲を1曲以上含むもの。
- **フォルダ**: `SongEntity.isLocal = true` の曲を1曲以上含むもの。
- 再生リストの **フォルダ** 判定は `playlist_song_map -> song.isLocal` を使い、
  `PlaylistEntity.isLocal` は使わない。該当する混在再生リストを開いた後は、由来を問わず全収録曲を表示する。

「曲」画面の **いいね済み** は由来とは別の条件として扱う。**ライブラリ**、**DL済み**、
**フォルダ**のいずれか（または由来の指定なし）と組み合わせ、両方を満たす曲だけを表示する。
由来の3条件は従来どおり択一で、選択中の条件をもう一度押すと由来の指定を解除する。

`PlaylistEntity.isLocal` は再生リストの同期・所有形態を表す既存名称であり、
収録曲が端末ファイルかどうかを表す値ではない。

## フォルダ曲のArtist／Album Artist

- 曲の `Artist` とアルバムの `Album Artist` は別のクレジットとして保存する。
  `Album Artist` を曲のArtist一覧・曲数集計へ混ぜない。
- 同じ表示名でも、フォルダ由来のArtist (`isLocal = true`) とオンラインArtist
  (`isLocal = false`) は別Entityとして解決する。
- 同名アルバムの照合は、MusicBrainz Album ID、Album Artist、同一フォルダ、年の順に
  信頼度を下げて判断する。既知のMusicBrainz Album IDまたはAlbum Artistが異なるものは
  統合しない。年は補助情報であり、同じフォルダ内の年表記の揺れだけでは分割しない。
- 再スキャン時は同一ファイルパスを最優先する。パスが変わった場合のメタデータ照合では
  Album Artistも比較し、候補が同点なら既存のどれかへ推測で上書きせず新規曲として扱う。
- TagLib／FFmpegスキャンではMusicBrainz Album IDを利用できる。MediaStoreスキャンでは
  Album Artist列がない、または空の場合のみ、アルバムとフォルダの組み合わせごとに1回だけ
  Android標準のメタデータ取得で補完する。
