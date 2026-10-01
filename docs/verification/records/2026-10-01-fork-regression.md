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
