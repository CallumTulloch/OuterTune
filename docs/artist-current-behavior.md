# 現在のアーティスト情報の取得・解析・表示

2026-09-05。調査対象は `restart/artist-20260905`、HEAD `a80f2615`。アプリのコードは `40212b39` と同一。
これは現在のコードを説明する資料であり、今後採用する仕様や不具合修正の完了報告ではない。
ビルド、テスト実行、実サービスへの問い合わせ、エミュレータ操作は行っていない。

後続の実サービス調査は [実応答調査の記録](verification/records/2026-09-05-artist-response-investigation.md) を参照する。
以下はコードを調べた時点の説明であり、実応答の最新の確認結果は後続記録に分けている。

## 1. 結論

- オンライン曲は、名前の文字列を `、` や `&` で分割しない。応答の表示部品（Run）から名前と任意のIDを取り出す。
- 多くの経路は「名前、区切り、名前」の交互配置を前提としており、区切り文字の意味を判定しているわけではない。
- 一つのRunに二人分の名前が入っていれば、そのRunを採用する経路では一件のArtistになる。一つのRun内の記号が正式名の一部か、人物の区切りかを判定する仕組みはない。
- 内部データは複数Artistのリストを持てる。最初から別々に解析できた名前とIDは複数のまま受け渡される。
- 端末のフォルダ音源は別処理であり、曲のArtistタグを記号で分割している。オンライン曲の名前問題とは区別して扱う。

## 2. 取得する情報と通り道

オンライン応答のRunは `text`（表示文字列）と `navigationEndpoint`（任意の移動先）を持つ。
移動先のbrowse IDやページ種別はネストした項目にある。Runは表示部品であり、必ずしも一人のアーティストを意味しない。

```text
検索／アルバム／ホーム／再生キュー等の応答
  → 画面・応答形式ごとのパーサー
  → SongItem.artists = [{name, id?}, ...]
  → MediaMetadata.artists = [{name, id?, isLocal}, ...]
  → 再生中の表示・キュー・通知
  → 保存時は artist テーブル＋曲との対応表
  → ライブラリから読み出した曲の表示
```

SongItemのArtistには名前と任意のIDしかない。元の区切り部品、役割、名前を決定した根拠、結合表示かどうかは保持しない。
曲のArtist、アルバムのArtist、アーティストページのタイトルは別の取得箇所にあり、一致を強制する統一処理はない。

| 取得経路 | 主な解析方法 |
| --- | --- |
| 検索「曲」等 | flex columnを ` • ` の独立Runで区分し、決められた区分の交互位置を採用 |
| 検索「すべて」 | カード／一覧等で取り出す区分が異なる。一部は先頭のリンク有無からカテゴリ部分を除外 |
| 再生キュー（next／getQueue） | longBylineTextの最初の区分から交互位置を採用 |
| アルバム見出し | straplineTextOneの交互位置から取得 |
| アルバム内の曲 | `MUSIC_PAGE_TYPE_ARTIST` の移動先があるRunを抽出。リンクなしの名前はこの抽出に入らない |
| アーティストページ内の曲一覧 | 型付きのアーティストRunを優先し、なければ交互位置の解析へフォールバック |

入口ごとの取得方法が異なるので、同じ曲でも検索・アルバム・再生キューで名前の数やIDの有無が変わり得る。
これはコード上の可能性であり、対象曲の全経路について今回実測した結果ではない。

## 3. 通常の複数アーティスト

以下は仕組みを示す模式例。実サービスから今回取得したJSONではない。

```text
受信したRun:
  0: {text: "アーティストA", id: "AのID"}
  1: {text: " & ", id: なし}
  2: {text: "アーティストB", id: "BのID"}

解析後:
  [{name: "アーティストA", id: "AのID"},
   {name: "アーティストB", id: "BのID"}]
```

`artistElements()` は0, 2, 4…番目を取り出すため、上の二人を保持する。
区切りRunが `、`、`・`、` × ` でも同じ並びなら処理は同じ。区切りの文字種を検査しているわけではない。
型付きRunを抽出する経路では、アーティストページの種別を持つ二つのRunが取得される。

このリストはMediaMetadataにも一要素ずつ写され、DBには人物ごとの行と曲との関連が保存される。対応表には表示順のpositionも保存する。

## 4. 結合名と名前内の記号

| 受信した形（模式例） | 交互位置を使う解析の結果 |
| --- | --- |
| `[AのRun, 区切りRun, BのRun]` | 二件 |
| `[{text: "A、B", id: なし}]` | `A、B` という一件、IDなし |
| `[{text: "A & B", id: あるID}]` | `A & B` という一件に、そのIDを付ける |
| `[{text: "Earth, Wind & Fire", id: あるID}]` | 文字列全体を一件として保持 |
| `[AのRun, BのRun]` のように区切りRunなし | 交互配置の前提が崩れ、Bが落ちる可能性 |

一Run内の名前を切らないため、名前内のカンマやアンパサンドは保持される。
ただし、実際に一組の正式名なのか複数人物の結合表示なのかを検証した結果ではない。
また、一つの結合名にIDが付いていても、そのIDが文字列全体またはどちらの人物を示すかを照合する処理はない。

特殊な判定は次のとおり。

- `splitBySeparator()` はRunの全文が正確に ` • ` の場合だけ、メタデータの区分境界とみなす。長い名前にこの記号が含まれるだけなら文字列を分割しない。境界判定にリンク有無は使わない。
- `artistElements()` はリンクなしで `4:49` などの時間形式に一致するRunを除外する。リンクがあれば残す。リンクなしの実名がこの形式だった場合との区別はできない。
- 検索「すべて」の一部に使う `clean()` は、最初の区分の先頭Runに移動先がなければその区分全体を落とす。リンクなしの名前とカテゴリ表示を常に識別できる仕組みではない。

## 5. 補完・保存・画面への表示

### 補完

検索結果のArtistリストが空の場合だけ、同じ曲IDをgetQueueで取得してArtistリストを補う。
結合名でも一件あれば空ではないため、この補完は実行されない。名前があるがIDだけない場合も対象外。
再生時にクレジットを調べて人物を再解決する旧途中実装は、現在のコードにはない。

別経路として、ダウンロード前にアルバムがないオンライン曲をgetQueueで補う際、IDなしのArtistに対して名前が完全一致する取得結果を採用する。
これも結合名を分割する処理ではなく、既に内部ID `LA...` があるArtistは対象外。アルバムがある曲にはこの取得を行わない。

### 保存

- オンラインIDがあればそのIDを優先する。同じIDが既にDBにあれば既存Artistを使う。
- IDがなければ、同じ由来（オンライン／フォルダ）の中で名前を照合する。大文字小文字・空白・Unicode表記の正規化を使う補助照合もあるが、名前を人物ごとに分割はしない。
- 見つからなければ内部ID `LA...` を作る。オンライン曲から作った場合は `isLocal=false` のまま。一件の結合名は、結合名を持つ一件のDB Artistになり得る。
- 既存曲に別のArtistを挿入する経路が呼ばれても、既存の曲と人物の関連を全削除して置換する処理ではない。新しいIDの関連が追加され、古い関連が残る可能性がある。
- 再生時の `recoverSong()` は既存曲なら主に不足した長さを更新する。再生するだけでアーティスト関係を修復する処理ではない。

### 表示と移動

- 曲一覧や通知：リストの名前を `, ` で連結して表示する。一つの名前内のカンマとの見分けを表示文字列だけで保証する仕組みはない。
- プレイヤーの曲名下：Artist一件ずつ別のTextで表示し、間に `, ` を挿入する。IDがnullでなければ、そのIDのアーティスト画面へ移動する。
- 一件の結合名なら一つのTextとなり、中のAとBを別々に選べない。IDなしならプレイヤーのそのTextは選択無効。
- DB由来の `LA...` はnullではないため、現在の単純な判定では移動可能になる。ただしオンラインIDとして使えるという意味ではない。
- アーティスト画面にはDB内の曲を表示する機能もあるが、オンライン曲の `LA...` を受け取ったときに最初から内部表示へ振り分ける設計にはなっていない。通常はオンライン表示から始まり、ページ取得もIDをそのまま使う。
- メニューの条件も統一されていない。YouTube曲メニューはIDなしの人物を候補から除外するが、プレイヤーメニューと人物選択ダイアログには同じ除外がない。名前タップが無効でも別メニューで `artist/null` 等の遷移先を組み立て得る。実行時の異常終了までは今回確認していない。

### 画像・アーティストページ名

曲のArtist情報そのものには人物画像は含まれない。ArtistEntityには画像保存欄があり、ライブラリ側は画像なし・更新から10日超のArtistに対してアーティストページを取得する。
取得に成功すると画像だけでなくDBの名前もそのページのタイトルへ更新する。IDのない名前を検索して画像やIDを確定する処理ではない。
このライブラリ更新経路には `LA...` やフォルダ由来の除外判定がなく、内部IDもそのまま問い合わせる。
アーティスト画面の見出しはオンラインのページ名を優先し、なければDB名を使うため、再生中のメタデータの名前と違う可能性がある。
画像が全般的に出ないという過去報告の原因は、この静的調査だけでは確定できない。

## 6. フォルダ音源との違い

| 対象 | 現在の分割規則 |
| --- | --- |
| オンライン曲 | Runの構造・位置またはページ種別を使う。Run内の `&` や `、` を分割しない |
| フォルダ曲のArtistタグ | `;`、`ft.`、`feat.`、`&`、半角カンマで分割。大小文字を区別しない |
| フォルダ曲のAlbum Artistタグ | 複数タグ値、セミコロン、NUL区切りを使う。カンマや `&` は保持 |

したがって `Earth, Wind & Fire` を曲のArtistタグへ入れた場合、現在のフォルダ解析では分割され得る。一方、Album Artistでは保持するテストがある。
この規則の違いは別の課題として認識する。今回のオンライン曲の修正範囲に自動的に混ぜない。

## 7. 確認済みの根拠と、次に必要な情報

既存の `ArtistMetadataParsingTest` には、二人を別Runで受け取る例と、日本語の区切りを独立Runにした例がある。
`SearchSummaryParsingTest` には時間表示の除外と、空のArtistをキュー情報から補う例がある。
いずれもコード内で構築した入力であり、現在のサービス応答や端末動作を今回確認した証拠ではない。今回テストは再実行していない。

[引継ぎ](artist-handoff.md) にある対象曲 `TSZhKssbW2g` の結合表示、クレジット、オンラインIDの情報は過去の観測として扱う。
保全先 `backup/artist-before-restart-20260905` のfixtureには対象曲の結合名の例があるが、旧検証資料では観測した構造・IDを残した小さな合成fixtureと説明されている。完全な生応答とは扱わない。

次の調査では、以下を少数の曲について揃えると、表示・解析・登録のどこで情報が不足するか判断できる。

1. 対象曲を同一の曲IDで比較する。検索「すべて」「曲」、再生キュー、アルバム見出しと曲行のRun配列・ID・ページ種別を、言語・取得日時・ログイン有無と共に記録する。認証情報自体は保存しない。
2. 二人が別々のRunとIDで届く曲を一曲、記号を正式名に含むアーティストの曲を一曲、対照として揃える。曲名は入力探しの手がかりであり、名前の見た目だけで正常と判定しない。
3. クレジットなど別の情報源を採用するなら、その人物の役割と対象曲との関係を確認する。見出し、演奏者、提供者を自動的に同じ意味にしない。
4. ユーザーと、外部IDがない人物の表示・内部登録・移動先を決める。結合表示しか確認できない場合の扱いと、情報が矛盾する場合の優先順位も決める。
5. 実装後の確認は、新規DBで保存したArtist行・曲との関連と実際の画面を照合する。既存データの移行は今回の依頼条件では不要。

## コードの参照先

以下の行番号は今回調べたソース時点の目安。

| 内容 | ファイル・主な行 |
| --- | --- |
| Run・区分・交互位置 | [Runs.kt](../innertube/src/main/java/com/zionhuang/innertube/models/Runs.kt) 11, 16, 31, 35, 46 |
| 解析後の名前とIDのリスト | [YTItem.kt](../innertube/src/main/java/com/zionhuang/innertube/models/YTItem.kt) 11, 21 |
| 検索・空リストの補完 | [YouTube.kt](../innertube/src/main/java/com/zionhuang/innertube/YouTube.kt) 118, 170, 196, 209, 238, 834 |
| キューの解析 | [NextPage.kt](../innertube/src/main/java/com/zionhuang/innertube/pages/NextPage.kt) 23 |
| アルバムの曲・型付き抽出 | [AlbumPage.kt](../innertube/src/main/java/com/zionhuang/innertube/pages/AlbumPage.kt) 82、[PageHelper.kt](../innertube/src/main/java/com/zionhuang/innertube/pages/PageHelper.kt) 7 |
| 検索「すべて」の区分選択 | [SearchSummaryPage.kt](../innertube/src/main/java/com/zionhuang/innertube/pages/SearchSummaryPage.kt) 118 |
| 再生用データ変換 | [MediaMetadata.kt](../app/src/main/java/com/dd3boh/outertune/models/MediaMetadata.kt) 17, 36, 116, 160 |
| DB挿入・名前照合 | [DatabaseDao.kt](../app/src/main/java/com/dd3boh/outertune/db/DatabaseDao.kt) 279、[ArtistsDao.kt](../app/src/main/java/com/dd3boh/outertune/db/daos/ArtistsDao.kt) 61, 76, 337, 349 |
| 再生時のDB保存 | [MusicService.kt](../app/src/main/java/com/dd3boh/outertune/playback/MusicService.kt) 374 |
| ダウンロード前の補完 | [DownloadUtil.kt](../app/src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt) 77, 258 |
| プレイヤー・曲一覧・通知 | [Player.kt](../app/src/main/java/com/dd3boh/outertune/ui/player/Player.kt) 821、[SongItems.kt](../app/src/main/java/com/dd3boh/outertune/ui/component/items/SongItems.kt) 112、[MediaItemExt.kt](../app/src/main/java/com/dd3boh/outertune/extensions/MediaItemExt.kt) 22 |
| アーティスト画面 | [ArtistViewModel.kt](../app/src/main/java/com/dd3boh/outertune/viewmodels/ArtistViewModel.kt) 25, 40、[ArtistScreen.kt](../app/src/main/java/com/dd3boh/outertune/ui/screens/artist/ArtistScreen.kt) 146, 154, 163, 550 |
| 画像更新 | [LibraryViewModels.kt](../app/src/main/java/com/dd3boh/outertune/viewmodels/LibraryViewModels.kt) 238 |
| フォルダ曲の分割 | [LocalMediaUtils.kt](../app/src/main/java/com/dd3boh/outertune/ui/utils/LocalMediaUtils.kt) 26、[TagLibScanner.kt](../app/src/main/java/com/dd3boh/outertune/utils/scanners/TagLibScanner.kt) 103 |
| Album Artistの分割 | [MetadataScanner.kt](../app/src/main/java/com/dd3boh/outertune/utils/scanners/MetadataScanner.kt) 39, 61 |
| 既存の解析テスト | [ArtistMetadataParsingTest.kt](../innertube/src/test/java/com/zionhuang/innertube/ArtistMetadataParsingTest.kt)、[SearchSummaryParsingTest.kt](../innertube/src/test/java/com/zionhuang/innertube/SearchSummaryParsingTest.kt)、[MetadataScannerTest.kt](../app/src/test/java/com/dd3boh/outertune/utils/scanners/MetadataScannerTest.kt) |
