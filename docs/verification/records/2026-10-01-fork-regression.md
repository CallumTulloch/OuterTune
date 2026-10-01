# 2026-10-01: フォーク以降の回帰・結合テスト

## 依頼・対象・合格条件

- フォーク時点から現在までの改修に対するエッジケース・結合テストを開始する。着手時には直近コミットだけを再確認する。
- 着手時HEAD: `0e16cca9`（チャンネル由来アーティストの手動紐づけ）。直前は `8bf3329c`（未取得アルバムの仮曲一覧を隠す）。
- 着手時の未コミット変更: 音声HTTP 403対策の `YTPlayerUtils.kt`、`PlaybackStreamProbe.kt`、`PlaybackStreamResolver.kt` と関連テスト・検証記録。これらは保全し、現在の作業ツリーを含めて検証する。HEAD単独の結果とは扱わない。
- 第一段階: `:innertube:test :app:testCoreDebugUnitTest` で既存の単体回帰テストを確認し、失敗と既存opt-inスキップを分けて記録する。
- 第二段階: DB・人物リンク・アルバム・キュー・表示の関連instrumentationと、発見した境界条件の追加テストを確認する。端末操作の対象・担当は実行前に告知する。
- 実行に必要なコンパイルは各タスクに任せ、同一作業ディレクトリでGradleを並列実行しない。cleanは実施しない。
- 検証PCの既知のTLS制約は再調査しない。固定応答・端末内の成功と、実機での実通信成功を区別する。
- 追加のユーザー条件: 必要ならコミットしてよい。DBは新規作成を前提とし、移行のためだけの改修は不要。これに合わせ、途中で用意した移行専用fixture・追加移行テストは最終差分から外した。

## 結果

- 証跡: `build/diagnostics/fork-regression-20261001/`。`source-start.json` にHEAD・作業ツリー・本番/単体テスト入力のSHA-256を保存。端末テスト開始時の再照合で変更0。
- `PASS`: `gradlew.bat :innertube:test :app:testCoreDebugUnitTest --offline --console=plain`、BUILD SUCCESSFUL in 19s。
  - app: 73クラス538件を今回実行、失敗/エラー/スキップ0、テスト本体計4.911秒。
  - innertube: 入力不変のためUP-TO-DATE。既存XMLの149成功/13既存実通信opt-inスキップ/失敗・エラー0を再利用。今回149件を新規実行したとは扱わない。
  - 通常の制限下の最初の呼び出しはGradle起動時のネットワーク権限で終了し、テスト未実行。既存キャッシュを使える権限で再実行。`unit-bootstrap-failure.log` と成功時の `unit-regression.log` を分離した。
- 端末: `Pixel_9_API_35`、`emulator-5556`、専用ADB5038、read-only/no-snapshot-save/no-snapshot-load/no-window。一時セッションだけを使用し、debugパッケージを初期化。元AVD・実機・通常ADBの操作なし。
- `FAIL（テストfixture）`: 既存APKで `MetadataDisplayRevisionTest#versionTwentyNineUpgradeKeepsNinetyFiveSavedSongsAndReadsCacheWithAnEmptyJournal` を実行。1件失敗、`duplicate column name: isChannel`。最新31からrevision表だけ削除して29を名乗るfixtureが原因で、製品の本物の29→31移行の失敗を示す結果ではない。`migration-before.txt` と使用APKのhashを保全。
- 最終対応: 旧移行テストを、新規の現行DBで95曲と名称を保存し、revision履歴が空でも再open後にキャッシュを読めるテストへ変更。95曲の全ID、名称/publicationの維持、変更後のrevision通知を確認する。DBのバージョン・schema・本番の移行処理は変更していない。
- 新規2件:
  - `ChannelArtistDatabaseTest#savedAndDownloadedChannelMembershipCountsOverlapOnceAndExcludesViewedOnlyTracksAcrossLinkAndUnlink`: 保存のみ/DLのみ/両方/閲覧のみの4曲から管理対象3曲・DL2曲を重複なく表示し、紐づけ/解除前後の元行・曲関連を保持する。
  - `ChannelArtistNamesProjectionTest#japaneseAndEnglishPerformerNamesApplyOnlyWhileChannelIsLinkedAndUnlinkRestoresUploaderSearch`: 実Roomと実表示オブジェクトを使い、同じUC値の投稿者と人物を未リンク時に混同せず、リンク中の日英名・検索alias・画像・遷移先と、解除後の元投稿者への復帰を確認する。

### 端末回帰・最終差分の結果

| 確認 | 結果 |
| --- | --- |
| DB/アルバム/名称/表示差分/バックアップ/同期OFF | PASS 156件、SKIPPED 1件、69.442秒。全157件のinstrumentation終了表示だけで成功数を決めず、各statusを集計した |
| キュー削除・即時reload・サービス終了時保存 | PASS 1件、4.049秒。fresh processで単独実行 |
| 再生履歴・回数・統計・DB読取りの生存 | PASS 4件、15.763秒＋0.392秒。サービス試験とliveness試験は別process |
| 人物リンク・チャンネル・再生投影・追加境界 | 対象回帰はPASS。途中版56件、21.641秒には後で外した移行専用2件を含むため、その56件を最終版の成功数にはしない |
| 新規DB保存・再openを含むMetadataDisplayRevisionTest | 最終ソースでPASS 8件、13.420秒。7件は上記途中版と重複 |
| 最終差分で有効な端末ケース | **PASS 216件、SKIPPED 1件、失敗0**。クラス/メソッドをキーに重複を除き、最終ソースに存在しない途中ケースを除外 |
| 最終テストAPK | `:app:assembleCoreDebugAndroidTest --offline --console=plain`成功、19秒。追加SQL fixture途中版の型推論エラーは解消済みで、失敗ログを別名保全 |
| ソース・差分 | 本番/単体テストの開始時SHA-256から変更0。最終androidTest入力・APK hashを`source-final.json`/`final-apk-hashes.json`へ保存。差分チェック成功 |

- SKIPPED: `MetadataDisplayIncrementalTest#suppliedBackupMigratesAndReadsOneChangedTargetWithoutReloadingTheCache`。外部バックアップパスを指定する任意診断のため、今回は引数なし。新規DB前提の検証とは区別し、成功数に含めていない。
- 途中の旧29→31/30→31試験は実行して成功したが、ユーザーの新規DB条件に合わせてソースから外し、最終ケース数から除外。旧fixtureの最初の失敗も履歴として保全する。
- 集計: `summarize-instrumentation.ps1` / `instrumentation-metrics.json`。操作・クラス選択・各instrumentationログは同じ診断先に保存。ADB exit0だけでは合格にしていない。
- 独立レビュー: 最終3テスト差分のassertと共有表示オブジェクトのfinally清掃を確認。指摘なし。
- 終了: 2026-10-01 20:06 JST、一時read-onlyセッションの本体/補助プロセスを起動引数で照合して終了、残存0。専用ADB5038停止、通常ADB/元AVD/実機の変更なし。`emulator-cleanup.json`。端末crash bufferは空。ユーザーへ通常エミュレータの操作可能を告知した。

## 次の段階

この第一回は既存単体回帰と決定的なDB/サービス/表示結合が対象。通常UIによる全経路・実ファイル・実通信・実機負荷まで完了したとは扱わない。

| ID | 次に用意する確認・合格条件 | 実行時期 |
| --- | --- | --- |
| REGRESSION-SCAN-001 | 合成音源の実スキャン→表示→再スキャン→OFF/ON→再起動。曲ID/所属の維持、遅延再登録なし、元音源とオンラインDL曲の保全 | 音源/権限/待機ゲートfixtureを準備した第二段階 |
| REGRESSION-DL-001 | 実転送の中断/再開・再生との同時開始・外部ファイル削除。Media3 index/DB/cache/表示と圏外再生の整合 | 専用DLディレクトリとHTTP音源fixtureを準備した第二段階 |
| REGRESSION-RESTORE-001 | 新規DBの通常バックアップ→別の新規検証環境へ復元→再起動。所属/リンク/歌詞設定/キュー保持、破損時の既存検証データ保全 | バックアップUIの固定シナリオを準備した第二段階 |
| REGRESSION-PERF-001 | 同一データ・操作・再生状態でCPU/GC/メモリ/温度と待機収束を比較 | 通信可能なarm64実機と比較条件が揃った段階 |

今回のコミット対象は最終3テストファイルと本記録のみ。既存の未コミット音声403対策・その記録/成果物はそのまま保全する。

## 第二回: 実ファイル・Media3・通常UI復元

ユーザーの継続依頼により `17425de2` から進めた。別作業の403対策は引き続き保全し、今回のコミットへ含めない。証跡は `build/diagnostics/fork-regression-phase2-20261001/`。

| 対象 | 最終結果 |
| --- | --- |
| app単体回帰 | PASS 538件、失敗/スキップ0、4.436秒。最終本番ソースとAPKのビルド29秒 |
| 実復元と既存snapshot | PASS 11件、3.167秒。追加7件で破損/不整合入力・設定書込み拒否を検証 |
| 近傍の保存元/リンク/アルバムDB回帰 | PASS 23件、4.852秒 |
| 実Media3失敗/再開/停止/同時再生 | PASS 3件、2.971秒。全音源バイト一致とオフライン読込 |
| 実TagLib/SAF/スキャンOFF-ON/DB再open | PASS 4件、2.637秒。対象クラスだけを明示opt-inで実行 |
| 通常UI復元と実アプリ再起動 | PASS 1シナリオ。準備/照合の補助instrumentation各1件、0.854秒/0.385秒。PID4398→5121、26テーブル/4曲/リンク/名前/歌詞/キュー一致、合成設定の全キー/値保持 |
| 第二回の端末集計 | 機能回帰41件＋UIシナリオ補助2件、合計43件PASS、失敗/スキップ0。中間失敗・反復実行を加算しない |

- 本番不具合を2件再現・修正した: 破損バックアップが検証前に設定を置き換える問題と、パス欠落localの解除条件が `= null` で働かない問題。DB版/schema/移行処理は変更していない。
- 途中のfixture失敗も別ログで保全。Media3 upstream生成時/空配列の失敗と、通常UIの起動処理に不適合なfake DL/名称根拠を、製品不具合の件数へ加えていない。
- UI fixtureとscannerのDB再openだけを更新した段階では本番/JVM入力の変更0をhashで照合して単体結果を再利用。その後、復元のファイル置換APIだけを変更した最終版では単体538件・復元11件・通常UIを再実行した。保存元/リンク/アルバムDB・scanner・Media3の入力変更は0で、該当端末結果を再利用した。Android 7対応不要というユーザー指示に従い、Android 7向けの追加検証は行わない。
- 詳細: [復元前検証・通常UI再起動](2026-10-01-backup-restore-validation.md)、[実ファイルスキャン](2026-10-01-local-scan-integration.md)、[Media3中断・再開](2026-10-01-download-cache-integration.md)。
- 最終本番APK coreDebug/arm64-v8a: SHA-256 `89DA7DBB82F6BFA86249E791E53E0A7011CE24215F8722978A920A75F0B372B6`。最終test APKのhashは診断先 `final-apk-hashes.json` に保存。
- 終了: 最初のセッションは2026-10-01 21:14 JST、復元API再検証のセッションは21:32 JST。一時read-onlyセッション/専用ADB5038だけを終了、残存0、crash buffer空。ユーザーへ操作終了・通常利用可能と案内。

### 残る範囲の更新

| ID | 今回確認した範囲 | 残る条件・実行時期 |
| --- | --- | --- |
| REGRESSION-SCAN-001 | 実ファイル抽出/再タグ/移動、ArtistとAlbumArtist、OFF中の遅延排除、SAF除外/OFF-ON、ファイルDB再openと再スキャン | 通常画面の全遷移・実ファイル付き実機アプリ/OS再起動は、実機シナリオを準備した段階 |
| REGRESSION-DL-001 | 実Media3 manager/index/cache、HTTP Body中断/再開/停止、同時ExoPlayer、削除と独立player cache保全、圏外相当の全バイト読込 | DownloadUtil→DB印→表示→外部ファイル削除の一連の経路、実通信/実機は専用DL環境を準備した段階 |
| REGRESSION-RESTORE-001 | 実backup→restore、拒否時保全、通常DocumentsUI→実process再起動→全26テーブル一致/合成設定値保持 | 新規現行DB/合成データの合格条件を達成。音源/index移設と既存版の移行はこの結果に含めない |
| REGRESSION-PERF-001 | 今回の対象外 | 通信可能なarm64実機と同一の比較データ/操作が揃った段階 |

本番修正2件はそれぞれ追加テスト/記録とともにコミットし、Media3追加テストと全体記録を別コミットにまとめた。APK/ログ/合成バックアップはコミットしない。

## 配布用coreRelease APK

- ユーザーのrelease APK依頼により、`50b5eca4` と保全中の音声403対策を含む作業ツリーから生成。既存の最終テスト入力hashとの相違0を確認し、単体538件と既検証の結合結果を再利用した。製品ソース・version/schema・署名設定は変更していない。
- `PASS`: `gradlew.bat :app:assembleCoreRelease --offline --console=plain`、3分8秒。R8・resource shrink・release vital lintを含め成功。証跡: `build/diagnostics/fork-release-20261001/`。
- 成果物: `app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`。coreRelease / arm64-v8aのみ / versionName 0.10.2-b1 / versionCode 71、9,221,585 bytes。SHA-256 `3BFB338F90BDD397A904C996B66993D96DAD05AFA75DF50679BA0EBD33F4589B`。
- `PASS`: apksigner verify（v2署名）とaapt2のmanifest確認。package `com.dd3boh.outertune`、非debuggable、APK内native ABIはarm64-v8aだけ。`release-apk.json` / `apk-signature.txt` / `apk-badging.txt`。
- 端末: 専用read-only `Pixel_9_API_35` / emulator-5558 / ADB5039。arm64 APKをx86_64エミュレータのarm64対応で実行し、元AVD・通常ADB・実機には触れない。一時releaseパッケージだけを新規データへ初期化。
- `PASS（実releaseの通常UI）`: 最終の合成4曲backupを通常DocumentsUIで復元し、PID4093→4827の実再起動を観測。release自身で作成したbackupを空の検証アプリへ復元し、PID5894→6279の実再起動後に再backupした。
- `PASS`: release自身の復元前/後backupはZIPの全エントリ/CRC、SQLite integrity/FK/current31を満たす。全26テーブルの列/全行が相互一致し、元の合成expected JSONにも完全一致。人物リンク・正規English publication・歌詞/offset・混合キュー/shuffleを維持。設定はrelease自身の復元前/後で全バイト一致。元debug fixtureの設定全バイトとは異なり、起動時に追加する設定との差を示すものとして記録し、元設定ファイルとの全バイト一致とは扱わない。`release-backup-comparison.json`。
- `PASS（画面）`: 保存済み4曲と日英名称を表示。`release-songs.png` / `release-songs.xml`。実音源・実転送の再生確認はこのrelease確認の対象外。
- 途中の準備ではDocumentsUIの同名Downloads要素を選択できず復元が開始されなかった。また、外部保存直後の検証用初期化後に、親Activityを失ったPickerが残った。クラッシュはなく、該当状態とログを保全し、Pickerを閉じて新規の通常画面から最終シナリオを実行した。途中操作を製品の復元失敗件数へ加算しない。
- 終了: 2026-10-01 21:57 JST、起動PID/引数を照合した一時セッション/専用ADB5039のみ終了、残存0、crash buffer空。`emulator-cleanup.json`。ユーザーへ通常操作可能と案内した。
- release実通信は既知の環境制約に従って未検証。配布対象のAPKと上記の通信不要経路を確認した結果であり、実機負荷や実ネットワーク成功を示す結果ではない。
