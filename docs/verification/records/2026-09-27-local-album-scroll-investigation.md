# 2026-09-27: 大量ローカル取込後のアルバム一覧スクロール

## 現象・範囲

- ユーザー報告: `C:\Users\callu\Desktop\Music` から多数の曲を取り込むと、ライブラリのアルバム一覧がカクつく。当初は再起動後も続くとの回答。追加説明では、再起動直後は改善することもあるが、操作を重ねると同じアルバム周辺で再発する。収録曲が多いアルバムで起きる印象はあるが、相関は未確定。
- 期待: 取込完了後、収録曲数やジャケットの元解像度に左右されず一覧をスクロールできる。
- 今回は原因調査のみ。アプリ実装、音楽ファイル、設定、DB、APKには変更しない。
- 対象ソース: `9997984aea2491adef9cc24bba72b54c11789351`。着手時の未コミット差分なし。
- 調査の合格条件: 実データの特徴と該当表示経路を照合し、確認済みの非効率と実測していない因果関係を区別する。

## 確認済みの問題

### 1. アルバム表示だけで収録曲と関連データを全取得する

- `ui/component/items/AlbumItems.kt:48–62,111–125`: リスト・グリッド両方で、ダウンロードバッジ用に `database.albumSongs(album.id).collect` を開始する。
- ローカルアルバムを取得前に除外していない。全曲取得後に `filterNot { it.song.isLocal }` し、ローカル曲だけならダウンロード状態計算を終了する。
- `db/daos/AlbumsDao.kt:239–254` → `AlbumWithSongs` → `Song` のRoom relationsを経由し、曲ごとのアーティスト、アルバム、ジャンル、再生回数も実体化する。収録曲数が増えるほど返却データが増える。
- `ui/component/Library.kt:109–179` のライブラリ用ラッパーはこの既定バッジを使う。アルバム一覧にも適用される。
- 新しい項目がcompositionへ入ると購読を開始し、破棄後の再表示では再取得する。スクロールの毎フレームに全取得するという意味ではない。
- SQL自体はRoomの背景処理。取得・割当・状態更新が今回のフレーム落ちに与える時間は未測定。ローカルアルバムは `withTrackOrder()` の並べ替えを早期returnで省略するため、その整列を原因とはしない。

### 2. アルバムのジャケットを元の解像度で展開・保持する

- `ui/component/items/AlbumItems.kt:86–93,147–153`: `ItemThumbnail` に `preferredSize` を渡していない。
- `ui/component/items/Items.kt:698–718`: 既定値 `-1` を `LocalArtworkPath(path, -1, -1)` に渡す。
- `utils/CoilBitmapLoader.kt:91–129`: 音楽ファイルから埋込画像を抽出し、オプションなしの `BitmapFactory.decodeByteArray` で全解像度を展開する。縮小条件 `data.x + data.y > 0` はアルバム経路では成立せず、そのBitmapを返す。
- `Factory.create` はCoilの `options.size` を使わない。`ImageFetchResult` を返すためCoil標準decoderでの縮小も通らない。Coil 3.3.0の [EngineInterceptor](https://github.com/coil-kt/coil/blob/3.3.0/coil-core/src/commonMain/kotlin/coil3/intercept/EngineInterceptor.kt) で、返された画像がそのままキャッシュ・描画経路へ進むことを確認した。
- 曲一覧は最終縮小サイズを指定するが、全解像度を展開した後で縮小する。今回はユーザーが確認したアルバム経路を優先する。
- メモリキャッシュは存在する (`App.kt:117–138`)。毎回必ず再デコードするとは限らないが、大きなBitmapがキャッシュを圧迫し、キャッシュミス時に抽出・展開を繰り返す候補になる。
- メモリキャッシュ上限は `.maxSizePercent(context, 0.3)`。この独自Fetcherには縮小画像をディスクへ保存する処理がなく、返却する `ImageFetchResult` はCoilでも `diskCacheKey = null` になる。設定のディスクキャッシュ容量を大きくするだけで、この経路の再デコードが解消するとは言えない。
- `CoilBitmapLoader.kt:94` で作った `MediaMetadataRetriever` に `release()` / `close()` / `use` がない。利用直後の明示解放が欠けている。永久的なリークや実機の資源枯渇を確認したという意味ではない。
- 画像なし・抽出失敗時も `drawPlaceholder()` の既定2000×2000 Bitmapを作る (`:99–101,154–164`)。今回の883曲中、カバーなしは1曲。

Android公式も、表示サイズに合わせた縮小デコードで不要なメモリと処理負荷を避ける方法を説明している: [Loading Large Bitmaps Efficiently](https://developer.android.com/topic/performance/graphics/load-bitmap)。抽出器の解放契約: [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever#release())。

## 実データの読取調査

- MP3 806曲、M4A 77曲、合計883曲。ほかにM4V 1件があるが、今回の音楽タグ集計には含めない。
- Mutagenで埋込画像を取得し、Pillowで画像ヘッダを読む。MP3は表紙タイプ(APIC type 3)を優先、M4Aは最初のcovr。Androidの複数画像からの選択と完全に同一とは保証しない。
- 882曲に画像あり、19曲は複数画像。選んだ882画像の内容ハッシュは33種類。読取エラーなし。画像調査の所要約3秒。

| 収録フォルダの例 | 該当曲数 | 画像サイズ | 1枚のRGBA展開量の概算 |
| --- | ---: | --- | ---: |
| メカクシティデイズ | 13 | 3311×3001 | 37.90 MiB |
| Monster Hunter 4 | 63 | 2894×2933 | 32.38 MiB |
| NieR | 62 | 3000×2554 | 29.23 MiB |

これらは `幅 × 高さ × 4` の見積もりで、実機メモリ実測ではない。アルバム一覧で各曲分の画像を同時に展開するという意味でもない。曲数が多いフォルダに大画像もあるため、曲数と画像サイズの影響を現時点では分離できない。

読取結果原本: 無視対象の `build/diagnostics/local-scroll-20260927/music-artwork-inventory.json`。元音楽は変更なし。

## 判定・次の確認

- **確認済み:** アルバム一覧の不要な全曲取得、元解像度Bitmapの返却、抽出器の明示解放欠落。実データにも大きな埋込画像がある。
- **有力な推定:** 「再起動直後は軽い場合があり、操作を重ねると同じアルバム付近で再発」という補足から、大画像の展開・キャッシュ圧迫と抽出器の解放遅延を優先候補にする。全曲取得も「収録曲が多いものほど重い」という報告に合う。キャッシュの存在自体を不具合や無制限リークとは扱わない。
- バッジの `LaunchedEffect` はcompositionから外れるとキャンセルされる構造。全曲購読が操作のたびに永久に蓄積することは確認しておらず、累積悪化の説明としては画像側の資源負荷の方が有力。ただし再起動による改善だけでは両者の因果は確定しない。
- **未確認:** 主因、各処理時間、GC発生、フレーム落ち率、端末上のアルバム構成、利用APKと現在ソースの一致。
- `adb devices -l` は接続端末なし。実機・エミュレータでの再現計測なし。調査のみのためGradle・APKビルドは実行しない。
- 修正候補: 表示サイズで縮小デコードする、抽出器を必ず解放する、ローカルアルバムの不要なバッジ用曲取得を避ける。追加の画質維持条件を受けた実施順序は次節。音楽ファイルの再圧縮・タグ変更は必要ない。
- 修正効果の確認は同じアルバム一覧・同じスクロール経路で実施し、初回とキャッシュ済みを分けてフレーム時間・メモリを比較する。可能なら全曲取得と画像処理を個別に比較して寄与を分離する。
- 取込中のDB更新・全体整列も負荷候補だが、再起動後の症状の説明としては優先しない。

## 過去の画質改善との照合・修正方針の検討

ユーザー追加条件: 以前にアルバム・アーティスト画像の低解像度を改善しており、その改善は必要。コミット履歴を確認し、画質を維持できる方法を吟味する。今回は引き続き調査のみ。

### 履歴で確認できた内容

`git log --all` のコメント検索、画像関連ファイルの `git log -p/-G/-S`、`git blame`、該当commitの `git show` で確認。

| commit | 日付 | 差分で確認した内容 |
| --- | --- | --- |
| `f461090c` | 2026-08-30 | `fix(player): request artwork at display resolution`。プレイヤーの表示領域を物理pxへ換算して画像を要求。オンラインURLも要求サイズへ変換する。 |
| `b7f4e207` | 2026-08-30 | `fix(player): load full-resolution artwork before lyrics`。YouTube動画の大きな表示にはmaxresdefaultを要求し、取得失敗時は保存済みURLへ戻す。Google画像URLの対象ホストにyt3系を追加。対応するURL変換テストも追加。 |
| `a98e015a` | 2025-09-08 | upstreamの `ui: Load exact item size for artwork thumbnails`。ローカル曲のdp→px変換と縦横比を保持する縮小。 |
| `c97418b02` | 2025-10-31 | upstreamで `ItemThumbnail` に `preferredSize=-1` を導入。アルバム表示のサイズ未指定経路はここから継承。コメントは変更目的を説明しておらず、差分を根拠にした。 |
| `e564968f` | 2025-08-28 | upstreamの `app: Coil all the way`。現在の全解像度デコードと、抽出器の明示解放がない経路の起点。 |

- 8月30日の2件は現在HEADの祖先で、`Thumbnail.kt:98–118`、`MediaMetadata.kt:110–117` に表示pxでの取得・フォールバックが残っている。`YouTubeUtils.kt` は `b7f4e207` から現在まで差分なし。
- したがって、今回のローカル画像の全展開・解放漏れを、8月30日の高画質化が作った不具合とは扱わない。
- `49803281`（9月4日）は画像欠落時の人物アイコン・バッジとアプリロゴの変更。`5e19cc29` は人物画像用の共通表示導入、`798822af` は画像情報の保存・補完であり、低解像度への制限解除とは確認できなかった。
- 現行アーティスト画像は `ArtistThumbnail` の通常URL→Coil経路で、今回の音楽ファイル用 `LocalArtworkPath` / 独自Fetcherを使わない。アーティスト詳細の `resize(1200,900)` も以前からの指定。
- 調べた履歴で明確に特定できた関連改善は8月30日のプレイヤー系。ユーザーが記憶する「アルバム・アーティスト」の改修そのものと同一とは断定せず、ほかの改修が存在しなかったとも断定しない。

### 現時点の推奨順序

1. **画質に影響しない処理を先に修正・計測する。** ローカルアルバムのダウンロードバッジ用全曲取得を開始しない。画像抽出器を成功・失敗・取消の各経路で確実に解放する。画像要求サイズ・取得URLはこの段階では変えない。
2. **負荷が残る場合、アルバム一覧の表示サイズに応じたデコードを独立して検証する。** 一律100/200/512px等の上限は設けない。実際の表示領域を物理pxに換算し、表示に必要な画素数を下回らない画像を生成する。全解像度展開後の縮小では一時メモリ負荷が残るため、画像ヘッダを読んだ段階でサンプリング寸法を決める。
3. **大きな表示用画像とキャッシュを保護する。** プレイヤー主画像、アルバム詳細、アーティスト画像の取得品質を維持し、小さい一覧画像を拡大流用しない。サイズ未指定の元画像取得・通知を勝手に小さいサイズへ変更しない。

実装検討時の注意:

- 現行キャッシュキーは `path;x;y` (`CoilBitmapLoader.kt:182–192`)。一覧・詳細・プレイヤーの寸法の区別を維持する。`Options.size` を利用する設計へ変更するならキーと `isSampled` も整合させ、縮小済み画像を元画像と誤認させない。
- ライブラリの大小タイルは `GridCells.Adaptive` で実際の幅が変わる (`LibraryGrid.kt:15–25`, `Items.kt:244–272`)。`96dp`等の定数だけでグリッドの必要画素数を決めない。
- ローカルの現在の縮小はFitの縦横比保持。非正方形の画像やCrop表示では必要な画素数が異なる。共通処理の変更で切り抜き方を変えない。
- アルバム詳細は144dp×density、主プレイヤーは実測表示px、テーマ色・ぼかし背景は100×100を使う別用途。それぞれの要求を一律に統一しない。

### 変更後に必要な比較（未実施）

- 同一端末・同一アルバム順で、再起動直後と操作を繰り返した後のフレーム時間・メモリを比較。画像変更前の段階と画像変更後を分ける。
- 大小タイル、リスト、高密度画面、縦横画面で旧版と画質を比較。今回の大型カバーと非正方形カバーを含める。
- 一覧→プレイヤー→詳細、および逆順で同じ画像を開き、キャッシュ済みの小画像が大きな表示へ使われてぼやけないことを確認。
- 高解像度のオンライン画像と、maxres取得失敗時のフォールバックを維持。関連する既存URL変換テストも使う。
- 今回追加したのはこの調査記録のみ。コード変更、テスト実行、ビルド、端末操作はしていない。

## 第1段階の実装・検証（ユーザー承認後）

- ユーザー依頼: 改修開始。カクつきそのものの体感評価はユーザーが担当し、それ以外の確認はエージェントが担当する。
- 今回の実装範囲は、画質に影響しないアルバムの不要な曲取得削減と画像抽出器の解放。表示寸法・デコード解像度・画像URL・キャッシュキーは変更しない。表示サイズに合わせたデコードは、この段階をユーザーが評価してから判断する。
- `AlbumItems.kt`: バッジ処理を共通化。ローカルアルバムはお気に入り表示後、DBとダウンロード状態の監視を始める前に終了する。オンラインはアルバムID等にeffect/stateを紐づけ、曲一覧が空になったときに以前のバッジ状態を残さない。
- `CoilBitmapLoader.kt`: 埋込画像のbyte配列取得を `extractEmbeddedArtwork` に分離。読取成功・画像なし・失敗のいずれでも `finally` で `release()`。解放時の例外は記録し、取得済み画像や元の読取エラーを置き換えない。minSdk24でも使えるAPIを使用。
- 検証対象: ローカルの曲取得抑止とお気に入り表示、画像の成功/失敗時の解放、画像の寸法・画素とキャッシュの区別、既存の高解像度URL変換、coreRelease/arm64-v8a成果物。
- Pixel_9_API_35を `-read-only -no-snapshot-save -no-window` の一時セッションで起動。元のAVDデータへ書き戻さず、通信を停止して機能確認する。ユーザーへ操作担当を事前案内済み。体感性能の合否は測定しない。

### 最終ソースの自動検証

対象は `9997984a` + 本改修の未コミット差分。検証中の本体ソース追加変更なし。

| 確認 | 結果 |
| --- | --- |
| `YouTubeUtilsTest` | PASS: 5件、0.096秒。既存の大画像URL要求と小画像URL・クエリの保持。 |
| `LocalArtworkResourceTest` | PASS: 9件。読取成功・画像なし・source失敗・画像取得失敗で解放。解放自身の失敗時にも取得済みbyte配列と元の例外を保持。実Android抽出・デコードで1200×800の全960,000画素が一致。明示48×48要求の48×32結果とキャッシュキー分離も維持。 |
| `AlbumBadgesTest` | PASS: 2件。ローカル一覧はDB・DownloadUtilを供給せず描画成功。グリッドでは実Roomのquery callbackを正例で検証後、アルバム切替を含めてバッジ用全曲取得0件。お気に入り表示・非表示を実描画で確認。 |
| Androidテスト合計 | PASS: 11件、テスト本体14.304秒（コマンド全体18.822秒）。Pixel_9_API_35 / emulator-5556、API35、arm64変換実行。 |
| ビルド | PASS: 単体テスト + coreDebug + coreDebugAndroidTest + coreReleaseを同一Gradleで実行、269.946秒。cleanなし。799タスク中57実行、742 up-to-date。 |
| 静的レビュー・差分 | PASS: 独立レビューで指摘なし。画像URL、表示寸法、デコード・縮小、キャッシュキー、アーティスト画像、再生ボタン、カスタムバッジの経路を維持。`git diff --check` 問題なし。 |

実行コマンド:

```text
gradlew.bat --offline :app:testCoreDebugUnitTest --tests com.dd3boh.outertune.ui.utils.YouTubeUtilsTest :app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease
adb -s emulator-5556 shell am instrument -w -r -e class com.dd3boh.outertune.utils.LocalArtworkResourceTest,com.dd3boh.outertune.ui.AlbumBadgesTest com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner
```

証跡は無視対象の `build/diagnostics/local-scroll-20260927` に集約。`build.log`、`build-result.txt`、`instrumentation.log`、`artifact-verification.json`、`signature.txt`。既存Roomクエリの未使用列等の警告はあるがビルド失敗なし。新しいUIテストは既存のdebug fixture Activityを使い、追加のテストライブラリ依存はない。

### 配布成果物

- `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-local-album-scroll-stage1-20260927.apk`
- coreRelease / arm64-v8aのみ、versionName `0.10.2-b1`、versionCode `71`、9,202,061 bytes。APK v2署名検証PASS。
- SHA-256: `528875e89cb539928458ba0561e3622f171c0b0ea808d386114ec7071ce457c3`。
- R8 mappingは診断フォルダの `release-mapping.txt` に保全。合成MP3はandroidTest専用で、release APKに含まれないことをZIP内容で確認。
- DB schema29のまま。音楽ファイルやタグには変更なし。追加の解像度制限なし。

### リリースAPKの画面確認と後片付け

- 配布APKを一時エミュレータへインストールし、起動・既存オンラインアルバムの一覧→詳細の遷移を確認。通信停止中のためオンライン画像の取得成功は判定対象外。
- schema29の合成DBを通常の復元画面から読み込み、63曲入りローカルアルバムでグリッド・リスト・詳細の画像、お気に入り、曲数、曲一覧を確認。全63曲は同じ自作無音MP3を参照する表示検証用データで、実際の63音源の再生や性能を検証したものではない。
- アプリをforce-stop後に再起動し、ライブラリで同じ画像・お気に入り・63曲の表示を再確認。元画像の寸法・画素の保証は上記Androidテストで行い、縮小スクリーンショットだけで画質同等とは判定していない。
- 証跡: 診断フォルダ内の `release-local-grid`、`release-local-list`、`release-local-detail`、`release-local-restart-library` の各PNG/XML。合成データの生成スクリプト・DB・復元用backup・manifestも同フォルダに保全。
- `adb -s emulator-5556 emu kill` で検証用一時セッションを終了。`-read-only -no-snapshot-save` で起動したため、検証用インストール・合成データ・通信設定を元AVDへ保存しない。元音楽フォルダは変更なし。

### ユーザーへ引き渡す評価範囲

- `SCROLL-01`: ユーザー端末の同じアルバム一覧で、再起動直後と操作を重ねた後のカクつきをユーザーが判定する。エージェントによる「改善済み」の合格判定はしない。
- 小さい表示向けの縮小デコードは未実装。第1段階の評価後、必要なら既述の画質条件を守って別段階で判断する。
- オンラインの実通信、ダウンロード開始→完了の実通信遷移、API24実端末は今回未検証。既知のrelease証明書制約は再試行していない。

## 第2段階の追加調査・実装

### 第1段階に対するユーザー評価と再現条件

- **ユーザー報告:** 第1段階の配布APKではカクつきが改善しなかった。第1段階の機能テスト成功とは区別し、`SCROLL-01` の改善確認にはしない。
- 再現手順は、フォルダを取り込む → ライブラリから Aincrad または Monster Hunter X のアルバムを開く → 曲を再生する → ライブラリ一覧へ戻る → スクロールする。一時停止しても重い状態が続くとの追加報告。
- 体感性能の評価は引き続きユーザーが担当する。エージェントは機能・画質・保存処理の回帰と成果物を確認する。
- 対象ソースは `9997984aea2491adef9cc24bba72b54c11789351` + 第1・第2段階の未コミット差分。第1段階の上記PASSを第2段階のPASSへ引き継がない。

### 追加調査で分かったことと限界

- 実データの該当表紙は、Aincrad が1417×1250（同一画像39曲）、Monster Hunter X が500×500（同一画像42曲）。Monster Hunter 4 の2894×2933とは別である。Monster Hunter Xでも起きるため、大きな画像だけで今回の症状を説明できたとはしない。
- Coil 3.3.0のローカルruntime JARを `javap -c -p` で確認。`ImageRequest.Defaults` はfetcher/decoderに `ioCoroutineDispatcher()` を設定し、その実体は `Dispatchers.IO`。`EngineInterceptor` はfetcher contextへ `withContext` して取得を呼ぶ。現状の画像抽出・デコードがメインスレッドで動いていたという根拠はない。
- 一方、独自 `CoilBitmapLoader.fetch()` は `scope` を使っておらず、既存の `coilCoroutine = Dispatchers.IO.limitedParallelism(16)` の制限を通らない。`scope` を使うのはMedia3向けの `decodeBitmap` / `loadBitmap`。独自Fetcherが `ImageFetchResult` を返す経路は標準のBitmapFactory decoderも通らず、標準decoderの同時処理制限を利用しない。
- アルバム一覧は元解像度をキャッシュし、曲一覧・プレイヤー・ミニプレイヤー・テーマ色などの明示サイズ要求も、元解像度を展開してから縮小していた。再生開始に伴う用途別画像要求とキャッシュ入替は負荷候補だが、ユーザー端末でのGCやキャッシュミスの頻度を実測したものではない。
- UIの継続的な更新やDBの連続更新が、一時停止後も続くカクつきの主因だとする根拠は現時点で得られていない。再生後の遅延保存という別の無駄は確認できたが、継続症状の因果関係は未確定。
- `QueueSaveScheduler` は変更を約5秒まとめてから保存する。新しいアルバム再生キューの保存では、`DatabaseDao.saveQueue` が既存ローカル曲まで再insertし、曲ごとのアーティスト・アルバム関連解決とアルバム集計を繰り返していた。これは「再生中に毎フレームDBへ保存する」という意味ではない。

### 第2段階の実装

- `AlbumItems.kt`: リストの `ListThumbnailSize` を物理pxへ換算し、グリッドは既存 `GridItem` の `BoxWithConstraintsScope` から実際の表示幅・高さを物理pxへ換算して `preferredSize` を指定する。グリッドに固定96dpや一律512px等の上限は設けない。共通 `Items.kt` の既定値や他の呼出元は変更しない。
- `CoilBitmapLoader.kt`: 埋込画像のヘッダから元寸法を読み、既存と同じ最終表示寸法を先に求める。元幅・元高さを `inSampleSize` で割った寸法が最終寸法を下回らない範囲で、最大の2の累乗を選び、事前デコードしてから最終寸法へ調整する。必要画素数より小さい中間画像を作って拡大する方法ではない。
- 非正方形画像は従来のFit計算と整数化を維持。正方形画像に非正方形の明示枠を指定した場合の従来動作も維持する。どちらかの要求寸法が非正値なら元寸法・sample=1とし、極端な縦横比の有効な明示要求は最低1pxを確保する。
- サイズ未指定の元画像取得・通知向け要求は全解像度のまま。キーは既存の `path;x;y` を維持し、一覧の小画像と詳細・主プレイヤー・元画像を区別する。縮小した結果を `isSampled=true` とし、元画像として再利用させない。元音楽や埋込画像の再圧縮・書換えはしない。
- `fetch()` 自体を `withContext(coilCoroutine)` で囲み、既存の同時処理16件以内の制限を実際の抽出・デコードへ適用する。縮小で別Bitmapができたときは、この取得処理だけが所有する中間Bitmapを解放する。Coilの共有キャッシュ中の画像は解放しない。第1段階のretriever解放は維持する。
- `Theme.kt` と `Player.kt` のテーマ色・グラデーション用要求では、ローカル画像の既存100×100指定に `ImageRequest.size(100,100)` を合わせる。これにより、縮小済み100px画像を `Size.ORIGINAL` 要求として扱いキャッシュを外す不整合を避ける。主プレイヤーの表示サイズ要求やオンライン画像URLは変更しない。
- `DatabaseDao.kt`: キュー保存の同じトランザクション内で存在確認済みのローカル曲は再insertを省く。キューと曲順の関連保存は続け、オンライン曲は従来どおりinsertする。スキャナが保存した新しいローカルメタデータを古いキュー情報で再解決する処理も避ける。DBバージョン・schemaは29のまま。

### 第2段階の最終検証

検証対象は上記ソース確定後の差分。以後の変更はこの記録のみ。

| 確認 | 結果 |
| --- | --- |
| `YouTubeUtilsTest` | PASS: 5件、0.018秒。既存高解像度URLとフォールバック関連の変換を維持。 |
| `QueueSaveSchedulerTest` | PASS: 9件、0.274秒。遅延保存と順序・競合の既存回帰。 |
| `LocalArtworkResourceTest` | PASS: 12件。解放、原寸の全960,000画素一致、明示サイズ/Fit/拡大/無効寸法、極端な縦横比、巨大画像のサンプル下限、小→大→原寸と逆順の実ImageLoaderキャッシュ再利用、破損画像の代替表示。 |
| `AlbumBadgesTest` | PASS: 2件。ローカルカードの不要な全曲取得抑止とお気に入り表示。 |
| `QueuePersistenceDatabaseTest` | PASS: 5件。既存ローカル曲のメタデータ8表を変更せず、曲順・重複曲・シャッフル・再開位置・DB再open後の復元を保持。削除済みローカル曲を除き、新規オンライン曲は保存。Room SQL callbackでキュー保存を観測し、ローカルアルバム再照合とメタデータ書込みが0回であることを確認。 |
| Androidテスト合計 | PASS: 19件、テスト本体7.606秒、コマンド全体10.842秒。API35 / emulator-5556 / arm64変換実行。単体と合わせ33件PASS。 |
| ビルド・差分 | PASS: 単体テスト、coreDebug、coreDebugAndroidTest、coreReleaseを同一Gradleで実行。242.951秒、799タスク中40実行・759 up-to-date、cleanなし。独立レビュー・`git diff --check` 問題なし。 |

```text
gradlew.bat --offline :app:testCoreDebugUnitTest --tests com.dd3boh.outertune.ui.utils.YouTubeUtilsTest --tests com.dd3boh.outertune.playback.QueueSaveSchedulerTest :app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease
adb -s emulator-5556 shell am instrument -w -r -e class com.dd3boh.outertune.utils.LocalArtworkResourceTest,com.dd3boh.outertune.ui.AlbumBadgesTest,com.dd3boh.outertune.db.QueuePersistenceDatabaseTest com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- 証跡: 無視対象の `build/diagnostics/local-scroll-stage2-20260927`。初回インストールは一時エミュレータの空き容量不足で失敗し、旧テストAPKでのclass-not-foundを `instrumentation-invalid-old-install.log` に保全。この実行は最終ソースの合否に含めない。今回コピーした一時音源を一旦除去し、3 APKのインストール成功を確認して上記19件を再実行。その後113曲を同じ場所へ再コピーした。元音楽・元AVDは変更なし。
- 配布用coreReleaseでも同じ113曲（597,112,672 bytes）を全再スキャンし、33アルバムの一覧を確認。Aincradは39曲、Monster Hunter Xは42曲。両アルバムの詳細→曲選択→一覧へ戻る操作を実施し、`dumpsys media_session` のPLAYING・error=null・曲名を確認。Aincradは `the first town`、MHXは `MHX Promo Video`。
- グリッド・リスト・詳細・大きなプレイヤーの画像表示を確認。停止後にforce-stop→起動し、33アルバムの一覧と保存した曲名が保持されることを確認。必要な画素数とサイズ違いのキャッシュ分離は自動テストでも確認済み。
- 画面証跡は同フォルダの `final-*` PNG/XML。再生証跡は `final-aincrad-playback-verified.txt`、`final-monster-playback.txt`。採取した単発のmeminfoは統制した性能比較ではなく、改善率の根拠にしない。
- 検証用セッションを `adb -s emulator-5556 emu kill` で終了し、接続端末なしを確認。`-read-only -no-snapshot-save` のため検証中のインストール・設定・音源コピーは元AVDへ書き戻さない。操作終了をユーザーへ案内済み。

### 第2段階の配布成果物・ユーザー判定

- `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-local-album-scroll-stage2-20260927.apk`
- coreRelease / arm64-v8aのみ、versionName `0.10.2-b1`、versionCode `71`、minSdk24、targetSdk36、9,202,061 bytes。APK v2署名検証PASS。
- SHA-256: `5d6d0b5d134ebcf0bc99d37e195082408b8efa574bd589dd1c271bf6340f8b73`。R8 mappingは診断フォルダの `release-mapping.txt` に保全。
- **SCROLL-01 / ユーザー報告PASS:** 第2段階の確認中に「問題の解消を確認できました」と報告あり。エージェントの機能テスト・画素テストと、ユーザーによるカクつき解消の評価を区別する。
- ユーザーから残るテストの継続と、この改修全体のコミット・pushを依頼された。上記機能確認を完了して記録を確定する。
- **未確認:** 各修正の寄与の分離、ユーザー端末のGC・フレーム時間、API24実機、オンラインの実通信・ダウンロード状態遷移。既知のrelease証明書制約は再調査していない。確認できた複数の無駄をまとめて解消しており、一方だけを唯一の原因と断定しない。
