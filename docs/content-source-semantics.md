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

`PlaylistEntity.isLocal` は再生リストの同期・所有形態を表す既存名称であり、
収録曲が端末ファイルかどうかを表す値ではない。
