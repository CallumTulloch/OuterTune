# NAV-20260905: 型付き endpoint によるオンライン遷移

- 対象 commit: `bf7cf2815693ed9ebea3d699a286f79503ff79c8`
- 記録日: 2026-09-05
- 目的: 未ダウンロードの検索結果と album 画面から、表示文字列を推測・分割せず、YouTube Music が返した型付き endpoint だけで artist / album へ遷移できるようにする。
- 変更範囲: 検索結果・DB 保存済み楽曲・album のメニュー導線、album header menu と album 内楽曲 menu の解析、artist 候補名の検証。
- 対象外: 長い曲名の表示方法、既存 DB の migration、YouTube Music の全レスポンス形式を実通信で網羅すること。
- DB 変更・移行方針: schema 変更なし。既存データの移行処理は追加しない。
- リリース阻害事項: なし。長い title の末尾差が省略される表示改善は `NAV-001` として後回しにした。

## 原因と修正判断

前回の `fd3730e0` は、無効な仮 artist ID を遷移に使わないクラッシュ防止と、再生開始後の album / 主 artist 補完を実装した。一方、検索結果の三点メニューは表示 artist と表示 album だけを参照し、menu 内の型付き endpoint を導線へ接続していなかった。このため、表示上は結合 artist しかなく album が空の対象曲では、再生前の「アーティストを見る」「アルバムを見る」が出なかった。

album 側でも、header menu の artist endpoint を画面へ渡しておらず、album 内楽曲を解析する際は各行の `renderer.menu` を破棄していた。したがって、再生時補完が正しくても、検索・album・保存済み楽曲の menu 導線は直っていなかった。前回これらまで修正済みと説明したのは範囲の取り違えだった。

今回の判断は次のとおり。

- `MUSIC_PAGE_TYPE_ALBUM`、`MUSIC_PAGE_TYPE_ARTIST` などで型が確認できる browse endpoint だけを遷移候補にする。
- `、`、`&` などで表示名を分割しない。表示用 artist 名と、menu が示す独立した遷移候補を別データとして保持する。
- 楽曲メニューでは、候補が複数ある時だけ artist page を取得して選択肢の表示名を確認する。album header では単一候補でも実ページ名を確認し、独立した導線として表示する。
- album-level の候補には header menu と明示的 album artist だけを使い、各曲固有の客演 artist を album artist として集約しない。
- 対象の header endpoint `UChWKQRswWTLRXp98zmgHtdQ` は実ページで `8082Audio` と確認できる。この ID を `翟锦彦` または表示用の結合名へ割り当てず、独立した `8082Audio` の遷移先として扱う。

## 合格条件

| ID | 条件 |
| --- | --- |
| AC-01 | 未ダウンロードの検索結果の三点メニューに artist / album 導線が表示される |
| AC-02 | artist 導線は `UChWKQRswWTLRXp98zmgHtdQ` の実ページ `8082Audio`、album 導線は `MPREb_NUdafp1DlA5` へ移動する |
| AC-03 | album header のリンクなし結合表示と、独立した artist endpoint を誤って関連付けない |
| AC-04 | DB 保存済みのオンライン楽曲でも、保持した型付き endpoint を menu 導線に使える |
| AC-05 | 通常版と instrumental 版を別 ID・別 title の別曲として再生し、いずれも主表示 artist が `翟锦彦` になる |
| AC-06 | artist / album 遷移と2曲の個別再生で致命的例外が発生しない |

## 追加・変更したテスト

| 対象 | 件数 | 確認内容 | 結果 |
| --- | ---: | --- | --- |
| `OnlineNavigationTest` | 6 | 型付き候補、album の優先順位、無効 ID・local 除外、DB 保存後も表示 credit と候補 ID を分離 | `PASS` |
| `AlbumPageParsingTest` | 2 | album header の artist 候補抽出・重複排除、album 内楽曲 menu の album / artist / credits hint 保持 | `PASS` |
| **合計** | **8** | 今回追加した対象回帰確認 | **全件 PASS** |

parser fixture は、実サービスで確認した response の階層・page type・対象 ID を残して小さくした合成 fixture であり、生の response 全体ではない。対象構造の回帰は固定できるが、未知の別 layout まで網羅したことは意味しない。

## 自動検証

| 状態 | コマンド・確認 | 対象 | 結果 | 所要時間 |
| --- | --- | --- | --- | --- |
| `FAIL` → 修正済み | 対象 test の初回 compile | 実装途中版 | `AlbumViewModel` の public property が internal 型 `ArtistNavigationTarget` を露出して compile 失敗。property を internal にして解消 | 対象再実行前 |
| `PASS` | 最終 full run の `OnlineNavigationTest` と `AlbumPageParsingTest` | 最終ソース | 8 pass / 0 skipped / 0 fail | 下記一式に含む |
| `PASS` | `.\gradlew.bat :innertube:test :app:testCoreDebugUnitTest --console=plain` | 最終ソース | 177 total: 164 pass / 13 skipped / 0 fail | 16秒 |
| `PASS` | `git diff --check` | 最終差分 | whitespace error なし | 1秒未満 |
| `PASS` | `.\gradlew.bat :app:assembleCoreRelease --console=plain` | 最終ソース | universal を含む core-release APK を生成。`lintVital` も成功 | 2分16秒 |
| `PASS` | 同上 | commit `bf7cf281` | commit後にVCS metadataを更新して再package。APK内revisionが `bf7cf2815693ed9ebea3d699a286f79503ff79c8` と一致 | 4秒 |

13件の `SKIPPED` は、既存の `innertube/src/test/java/com/zionhuang/innertube/YouTubeTest.kt` にある class-level `@Ignore` のライブ通信テストである。今回追加した8件に skip はない。

## 手動検証

| 状態 | 使用 build・環境 | 操作 | 実測 |
| --- | --- | --- | --- |
| `PASS` | 最終ソースの release candidate / release package のみを動かした Android emulator | 未ダウンロードの検索結果 `TSZhKssbW2g` の三点メニューを開く | 「アーティストを見る」と「アルバムを見る」の両方を表示 |
| `PASS` | 同上 | 検索結果の「アーティストを見る」を選ぶ | `UChWKQRswWTLRXp98zmgHtdQ` の `8082Audio` へ遷移 |
| `PASS` | 同上 | 「アルバムを見る」で album を開き、header の独立した「アーティストを見る: 8082Audio」を選ぶ | `8082Audio` へ遷移。結合表示または `翟锦彦` へ候補 ID を誤関連付けしなかった |
| `PASS` | 同上 | 通常版 `TSZhKssbW2g` と instrumental 版 `xDWhuDRnevk` を一曲ずつ再生 | 別 ID・別 title の別曲として個別に stream され、両方とも主表示 artist は `翟锦彦` |
| `PASS` | 同上 | player、MediaSession、通知、queue を確認 | 通常版の artist はすべて `翟锦彦`。queue は通常版・instrumental版の2項目を別々に保持し、両方とも補完後の artist を表示 |
| `PASS` | 同上 | アプリを強制終了して再起動 | 通常版の title と `翟锦彦` が保持され、再生再開後の MediaSession・通知にも反映 |
| `PASS` | 同上 | 上記 menu 遷移・画面遷移・個別再生中のログを確認 | fatal exception なし |
| `PASS` | commit `bf7cf281` を内部revisionに持つ最終APK / 同emulator | clean install、初回起動、`TSZhKssbW2g` の基本再生 | install成功、versionCode 71 / versionName 0.10.2-b1、stream開始、artist `翟锦彦`、fatal exceptionなし |

当初「同じ曲が2つ」と見えた2項目は重複登録・二重再生ではなかった。`TSZhKssbW2g` の title は末尾が `(特别版)`、`xDWhuDRnevk` は `(特别版伴奏)` であり、通常版と instrumental 版で ID も完全な title も異なる。現在の一覧では共通する長い title の末尾が省略され、相違部分を画面上で確認しにくいことが誤認の原因だった。

## FAIL・SKIPPED・実行しなかった確認

| 状態 | 対象 | 理由・今回への影響 | 追跡 ID |
| --- | --- | --- | --- |
| `FAIL` → 修正済み | 初回 targeted compile | internal 型の public 露出を検出し、最終検証前に修正した。最終ソースには残っていない | - |
| `SKIPPED` | Innertube live endpoint tests 13件 | 既存の `@Ignore`。今回のfixture testとは別で、今回追加testに skipはない | - |
| `DEFERRED` | 長い title の識別表示 | 2曲のデータと再生は正しく、今回の endpoint 導線とは独立した UI 改善 | `NAV-001` |

## 成果物

| 成果物 | サイズ | SHA-256 | 追加確認 |
| --- | ---: | --- | --- |
| `app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-universal-release-71.apk` | 11,414,767 bytes | `3DDF885CE7C592EE760701E1D05F9AFBFD2D81B9E85669EFFA026A013BED3AC6` | v2署名、signer 1名、内部revision `bf7cf281`、clean install と release-only emulator smoke が成功 |

signer certificate SHA-256: `45a8c1d0b4e914882ff085b18098cd917679bafedda7debd8a3c48b01727026d`

## 後回し項目

| 追跡 ID | 内容 | 後回し理由 | 実行する契機 |
| --- | --- | --- | --- |
| `NAV-001` | 長い共通 title の末尾にある通常版 / instrumental 版の差を一覧上で識別しやすくする | データ重複や二重再生ではなく、今回の導線修正を阻害しない | 曲一覧または title 表示を次に改修する時 |

## 結論・残存リスク

検索文字列を分解せず、検索・album header・保存済み楽曲の menu を型付き endpoint へ接続した。対象 UCh は実ページ名 `8082Audio` としてのみ表示・遷移し、主表示 artist `翟锦彦` や結合表示とは分離されている。対象8件と関連test一式、core-release build、release-only emulator の主要導線はすべて成功し、fatal exception はなかった。

残存事項は title 末尾の省略表示 `NAV-001` と、既存台帳に記録済みの別検証項目である。fixture は実構造由来の最小合成であり、未知の response layout に対する完全網羅を主張しない。
