# 2026-09-30: 実機の発熱と名称解決の遅延

**最新状況（09/30午後・チェックポイント）:** 最初の修正版は実機再現でOOMによる反復強制終了が発生し、実機合格条件は**FAIL**。追加対策版はJVM187件＋Android64件（実DB/192MiB上限の負荷試験を含む）に合格し、別名APKを作成した。その後、ユーザーから実機で再び問題があるとの報告があり、**実機の問題は未解決**としてここまでをコミットする。最新報告の具体的な症状・使用APKは未照合であり、追加対策版でのOOM再発と断定しない。初回のPASS/PC割当削減を実機問題の解決と混同しない。詳細は末尾。

## 現象・今回の範囲

- ユーザー報告: 実機で画面がかなり熱くなり、海外アーティスト・アルバムの名称解決が途中から進まないように見える。
- 具体例は Nirvana / Nevermind。追加確認では、時間とともにアルバムの曲名は英語になったが非常に遅かった。永久停止とは区別する。
- CPU採取中はユーザーが曲・アルバムを操作し、個別の曲追加・ライブラリ追加を随時行っていた。無操作やスクロールだけの測定ではない。
- 期待: 通常の追加操作で過大な処理負荷・発熱が生じず、名称解決が進むこと。
- 改修着手前の初回調査は読取のみ。接続認証後に既存releaseのログ・CPU・メモリ・描画・温度を採取。この初回調査時点ではアプリの操作、再起動、更新、DB変更、ビルドは実施していない。後続の改修と最終検証は末尾に記載。
- ユーザーより残量を調査の制限にしなくてよいとの指示あり。測定のための連続操作は要求していない。

## 対象と方法

- 調査時のソース: `74a37dc3e1437ae6da9a254ec3a0491a6fd19055` + 9/29の未コミット差分。改修着手前の調査時点ではアプリソース変更なし。
- 実機: Samsung SM-S931Z、Android 16 / API 36。USB接続・充電中。
- インストール済み: `com.dd3boh.outertune`、versionCode 71、0.10.2-b1、最終更新 2026-09-29 02:05:50。
- インストール済みbase.apkのSHA-256は `260cda5285ca08d90d4b3224f122a6b3ae41253b0685fb07e30b937a1dfc632a`。9/29配布の修正版と一致。
- PIDは17583を維持。生ログは無視対象の `build/diagnostics/physical-heat-names-20260930/` に保存し、追跡文書へ認証値・端末識別子・全ログを転載しない。
- `top -b -d 1 -n 21 -p PID -o PID,%CPU,RES,ARGS` の初回を捨て、20個の区間値を使用。100%=1コア。端末全体の割合ではない。
- `dumpsys gfxinfo ... framestats` の同じStats sinceを持つプロセス累計の前後差を使用。resetなし。前後のmeminfo等により、描画区間はCPU区間より少し長い。
- meminfo取得に対応したExplicit GCがログに存在するため、調査前からのBackground GCと区別する。

## 実機で確認したこと

| 項目 | 結果 |
| --- | --- |
| 初回温度 | HALの現在値: SKIN 41.6℃、BAT 40.2℃、AP 51.6℃。SKINは画面ガラス面の直接測温ではない。 |
| 温度制御 | Thermal Status 2。AndroidのMODERATEに相当。キャッシュ値とHAL現在値は混同しない。 |
| 01:22:50–01:23:11のアプリCPU | 平均189.35%、最小161%、最大255%、20サンプル。ユーザーによる曲・アルバム操作中。 |
| 同区間の描画 | 累計12,741→12,745、増加4フレーム。ウィンドウ側も233→237。これだけで実際の全操作の描画やGPU負荷がゼロとは判定しない。 |
| 同区間後の温度 | HAL現在値 SKIN 41.7℃ / BAT 40.3℃、Thermal Status 2。 |
| 同区間後のPSS | 380,943KiB、約372MiB。単発値からリークとは判定しない。 |
| 保存した直近ログ | 01:18:23.990–01:22:50.782。Background GCを525回確認。数十～百数十MBの回収が反復。GCのtotal時間は停止時間ではない。 |
| 後続の30秒 | `/proc/PID/stat`の差分で10秒ごと0.20%、0.79%、0.30%。CPUが下がる状態も確認。操作条件は初回と統制していない。 |
| 後続温度 | battery service 33.9℃、thermal HALのSKIN 34.5℃ / BAT 34.0℃、Thermal Status 0。初回との単純なアプリ変更比較ではなく、時間・操作状態が異なる。 |

- `top -H`の別区間で1スレッド6035%など成立しない値が出たため、その出力は原因のスレッド特定に使用しない。生ログと除外理由を保持。
- 後続の`sample_threads.py`は`/proc/PID/task/*/stat`の同じTID・開始時刻のCPU tick差分を採取。HZ=100。3区間とも低CPUであり、高負荷時の処理内訳は得られていない。終了したスレッドは前後差に含まれない。
- 取得したE/FログはVibratorInfoのHAL警告2件。名称処理の失敗を示すログではない。既存の名称repositoryは例外詳細を捨てるため、ログがないことは成功の証拠ではない。
- この初回採取時点では実機データの名称行数、Nevermindの具体的なアルバムID・取得/評価/公開の時系列は未取得。後述のバックアップ調査で行数・保存状態を確認した。初回から英語化までの所要時間は今回計時していない。

## 改修前のコードから確認した候補と限界

- `MetadataNameRepository.kt:213-220`: 表示名称の更新で全行のgroup/map/aliasを組み直す。
- `:240,1372-1378`: 全名称を取得した後、重複判定用のキーを全体の証拠JSON decode/encode/sortで作る。判定結果をキーから除いているが、再観測時刻は残るため、同じ名称の再観測でも評価処理は起動し得る。
- `:278-341,344-379`: 言語モデルの再判定は対象別に絞られている一方、全体入力・公開準備は繰り返す。背景8グループは1バッチの上限で、whileループは残りの対象へ進む。
- `MetadataNamesDao.kt:158-188`: 同値SQL更新をWHERE条件で除外しておらず、購読側の再読込は起こり得る。
- `YouTubeSongMenu.kt:167-172` / `SongMenu.kt:197-200`の保存から`metadataRefreshTargets()`の更新を経て、repository `:234-235`が全保存対象を再度scheduleする。TTLで通信は抑制されるが、列挙・要求作成は残る。同期OFF時の`changeInLibrary`は早期returnするのでYouTube同期の暴走とは判断しない。
- `OriginalNamePublication.kt:20-46`は全対象の証拠JSONを生成してから既存結果と比較する。評価済みで更新0件でも公開準備が走り、同じsnapshotのJSON解析が複数経路で重複する。
- 9/27の全履歴再通信・全件モデル再判定の改善は維持されている。今回の全体集計とは区別する。
- 通常通信例外はFAILEDとして記録し5分待機。名称workerが通信失敗のたびに死亡する根拠はない。判定結果の自己書込は入力キーから除かれ、同じ公開結果を再利用し、同一失敗入力の即時再評価も抑制する。永久ループは確認していない。
- 上記全体処理は追加操作中の大量割当・CPU増加と整合するが、今回のGCログだけで主因とは確定しない。高負荷中のスタック/割当または実機DB相当データでの再現が必要。

## 初回調査の判定・終了状態

- OBSERVED: 配布修正版で発熱と、追加操作中の高CPU・大量GCを実機確認。単にスクロール中だから許容範囲とは判定しない。
- OBSERVED: 後続区間では低CPUへ戻った。常時高負荷、メモリリーク、名称処理の永久停止は未証明。
- 未確認: CPU/割当の具体的な関数、名称遅延と発熱の共通原因、通常追加1件あたりのコスト、改善後の性能。
- アプリソース・設定・DB・インストール状態を変更せず、端末への入力操作も行っていない。全採取は有限回で終了。ユーザーの端末操作を占有していない。

参考: [Android thermal status](https://developer.android.com/reference/android/os/PowerManager#THERMAL_STATUS_MODERATE)、[dumpsys](https://developer.android.com/tools/dumpsys)。

## ユーザー操作による追加再現 (01:36–01:43)

- ユーザー提案の「個別曲を再生 → アルバムへ移動 → 1曲をライブラリ追加・DL」を約7回実施し、少し暖かくなったと報告。報告時も再生中。最後の曲・アルバム名、各操作の厳密な時刻、ダウンロード完了時刻は未取得。
- 01:36:42から採取開始、01:38:00に開始可能と再案内。01:42台に暖かくなった報告を受け、表示したまま操作を止めて30秒待つよう依頼。停止した厳密な時刻は確認していない。
- `capture_reproduction.py`で5秒ごとにプロセス/スレッドstat、15秒ごとにthermalservice/gfxinfo、PID限定logcatを受動採取。meminfoを測定中に呼ばず、調査が起こすExplicit GCを避けた。スレッドは同じTID・開始時刻のCPU tick差分を使用。システム全体のCPUや別プロセスの音声処理を含まない。
- 採取は01:43:51に明示終了。約430秒、同じPID17583。診断先 `build/diagnostics/physical-heat-names-20260930/reproduction-013642/`。`summary.json`を`analyze_reproduction.py`で生成。時刻とユーザー申告は`user-events.jsonl`に保存。

| 区間・指標 | 結果 |
| --- | --- |
| 操作開始前付近の低負荷区間 (18サンプル) | CPU平均0.28%、最大0.60%。再生/画面状態を操作区間と同一には統制していない。 |
| 操作開始後から終了まで (65サンプル、約325秒) | 平均444.49%、最小322.57%、最大541.20%。100%=1コア。 |
| 採取最後の30秒 (6サンプル) | 平均455.10%、最小409.91%、最大500.80%。採取終了時まで低負荷へは戻らなかった。 |
| 外装相当SKINのHAL現在値 | 開始32.5℃ → 終了40.6℃、最大40.7℃。画面表面の直接測温ではない。 |
| バッテリーBATのHAL現在値 | 開始31.3℃ → 終了39.2℃。USB接続・充電中。 |
| 温度制御 | Status 0 → 1。最初の受動調査でのStatus 2とは別の再現区間。 |
| 採取ログのGC | 全1461回、そのうちBackground GC 1456回。最初のGC 01:38:20.061、最後01:43:52.241 (停止処理中までのログを含む)。 |

操作後区間のスレッド群の平均CPU（100%=1コア）:

| スレッド群 | CPU |
| --- | --- |
| DefaultDispatcher系の汎用処理 | 170.85% |
| arch_disk_io系 | 142.52% |
| HeapTaskDaemon / ReferenceQueueDaemon | 109.23% |
| main | 9.03% |
| RenderThread | 6.16% |

- サンプル間に終了したスレッドは内訳から漏れる。分類名は実行関数を特定せず、DefaultDispatcher全体を名称処理と同一視しない。arch_disk_ioはRoom等が使用する実行基盤だが、正確なSQL・DAO呼び出しはこの採取では未取得。
- 最後30秒のmainは5.80%、RenderThreadは1.27%。観測されたアプリCPUの大半を描画スレッドやmainが消費していたわけではない。GC/参照処理だけで約1コア以上を占める。
- 初回調査では後の区間で低CPUへ戻ったが、今回の再現は採取終了時まで高CPUが継続した。前回の低下を今回へ一般化しない。
- **OBSERVED:** 約7回の通常操作の組合せで発熱・高CPU・大量GCを再現。単なるスクロール描画負荷として許容しない。データ更新・集約・一時割当を重点的な原因候補とする。
- **未確定:** 個々のステップの寄与、名称処理の具体的な割当元、DL処理の残り、名称遅延との因果関係。実機DB相当データまたは高負荷中の詳細プロファイルによる検証が必要。
- 採取プロセスとその子のlogcatは終了済み。端末の入力・アプリ状態を変更せず、操作制限の解除を通知。今回もソース編集・ビルドは行っていない。

## バックアップ失敗と実行中SQLの確認 (01:48–01:59)

- ユーザーに標準のバックアップ機能によるDownloadへの保存を依頼。01:48、01:50の2ファイルは0バイト。ユーザーも失敗/別状態と回答した。
- PID限定の警告ログでは01:48:48.841、01:50:59.967に `SQLiteDatabaseLockedException: database is locked (code 5 SQLITE_BUSY[5])`。`DatabaseSnapshot.createDatabaseSnapshot` の `beginTransactionNonExclusive` で失敗している。対応R8 mappingは既存coreReleaseの `mapping.txt`。
- `adb shell dumpsys dbinfo -v com.dd3boh.outertune` のsong.db接続で、名称公開結果のUPDATE→COMMIT→Room通知→`SELECT * FROM metadata_name`を確認。名称候補は35,150行。01:52:23–30の各接続の直近操作に全件読取が反復している。CursorWindow分割を1回ずつの独立した全件クエリとして数えない。
- 同じ診断には01:44:28の `TRANSACTION-IMMEDIATE took 2112ms` がある。ただしこの1件の呼出元は特定できない。接続プール累積625,996文/818,930msはこの再現区間だけの値ではない。
- INSERTのUNIQUEエラーに続くUPDATEはRoomのupsert経路なので、バックアップのBUSYエラーと混同しない。
- ユーザー自身が強制停止→再起動→曲の再生/追加前に再バックアップし、成功を報告。`OuterTune_29_20260930015950.backup` は7,530,515バイト。端末への入力・終了操作はエージェントから行っていない。
- 標準出力経由でZIPをメモリへ取得し、`song.db`だけを無視対象の診断ディレクトリへ保存。設定/認証を含む `settings.preferences_pb` は展開・解析していない。0バイトの失敗ファイルや以前のバックアップは調査DBに使用していない。
- SQLiteはローカルのコピーを `mode=ro` で開いた。46,608,384バイト、schema version 29、`PRAGMA integrity_check` は `ok`。端末・コピーのDB内容変更なし。
- 生のdbinfoには指定アプリ以外の診断セクションも含まれるため、原因分析はOuterTuneのsong.db部分だけを使用。生ログを追跡文書へ転載しない。

## 実機DBを使った既存処理の計測

### 入力と再現方法

- 保存された行数: song 1,173、artist 303、album 341、metadata_target 7,977、metadata_name **35,157**、metadata_original_publication 7,973、metadata_fetch 19,135。song行数はライブラリ登録曲数と同義ではない。名称キャッシュには閲覧等で観測した未登録対象も含まれる。
- 名称候補の内訳: ja 17,245、en 10,650、und 7,262。元データの証拠JSON合計5,374,429バイト。最新の原語候補7,163行のうち、現在の評価入力に対して未評価/古い行は2行だけだった。
- 公開結果の証拠JSONは合計23,094,507バイト。最大はartist 466,729バイト、album 277,852バイト。実際の表示名以外に判定・参照元の証拠を保持している。
- 無視対象の `audit_backup.py` がDB検査・集計とベンチ入力TSVを作成。診断先: `build/diagnostics/physical-heat-names-20260930/OuterTune_29_20260930015950/`。
- `MetadataBench.java`から**既存の** `app/build/tmp/kotlin-classes/coreDebug` の本番関数を直接呼び出した。Java source launcherが診断用Javaだけを処理し、Gradle・アプリコンパイル・APK生成・実機インストールは行っていない。対象Kotlinソース最終更新09/27 15:21以前、既存classは09/27 15:22:51。09/29の変更はUIのみ。
- Microsoft OpenJDK 21.0.10、`-Xmx1536m`。同じ保存DB由来の入力を使用し、各処理は1回ウォームアップ後3回を測定。追加プロファイル/保持処理は各5回。時間はPC上の値。`ThreadMXBean.getThreadAllocatedBytes`は呼出スレッドの累積割当差分であり、保持メモリ/PSSや同時使用量ではない。Android ARTの割当量・実行時間をこの数値と同一とはみなさない。

| 本番関数/処理 | 平均wall/回 | 呼出スレッドの一時割当/回 |
| --- | ---: | ---: |
| `originalPublicationInputKey` | 70.845ms | 83.338MiB |
| `latestOriginalRows` | 33.703ms | 47.187MiB |
| `originalAssessmentInputs` | 61.578ms | 120.344MiB |
| 入力作成と全候補の評価有効性確認 | 120.710ms | 289.903MiB |
| `prepareOriginalPublications` (DBの既存公開結果を渡す) | 858.027ms | **1,535.007MiB** |
| その出力を既存結果として、入力無変更でもう一度公開準備 | 812.956ms | **1,534.808MiB** |
| `OriginalPublicationInputs` の構築 | 69.806ms | 103.221MiB |
| 1つの同一取得元の再観測3行に `retainOriginalAssessments` | 160.038ms | **256.408MiB** |

- 最初の公開準備は7,975件を返し、DBの公開結果との差は4件だけ。再度の公開準備では結果が完全に同一 (`same_publications_on_repeat=true`) でも約1.5GiBを割り当てる。言語モデル/ネットワーク/Room/描画を呼ばない処理だけでこの量になる。
- 最後の保持処理はNevermindの同じ取得元 `ljUtuoFt-8c` の3候補について、名称・ID・アルバム関係を変えず観測時刻だけ+1し、保存済み評価を保持する本番関数を呼んだもの。人工的な全候補未評価化はしていない。この関数は `saveOriginalTitle` のRoom書込トランザクション内で呼ばれる。
- 表の計測は互いを内部に含む関数もあるため、全行を加算して1操作の総コストとはしない。

### 割当元の特定

- 公開準備だけを既存関数で再実行し、JFR `profile` の `jdk.ObjectAllocationSample` と `jdk.ExecutionSample` を保存。計測5回平均790.852ms / 1,530.872MiBで、上記と同じ規模を確認。
- 対象関数をスタックに含む割当サンプル1,739件を分類。重み付きサンプルなので厳密な全割当比率ではない。ウォームアップも含み、1回あたりの値ではない。
  - JSON再解析 約49.6%。`ArtTrackOriginalNameCodec.decode`、`OriginalNameAssessmentCodec.decode`、評価入力検証等。
  - 公開結果の証拠JSON生成 約25.2%。`publicationEvidence`。
  - 正規表現の構築 約13.9%。原語候補decodeの動画ID確認で `Regex("[A-Za-z0-9_-]{11}")` を都度生成する経路など。
  - その他 約11.3%。
- `OriginalPublicationInputs`、直接/プロバイダ参照/プレイリスト参照の評価集計がそれぞれ `latestOriginalRows` / `originalAssessmentInputs`を作り直しており、同一snapshotのJSONを何度もdecodeする。
- `prepareOriginalPublications`は全対象の証拠を生成してから既存結果との一致を確認する。出力0変更でも作成コストを避けられない。`evaluateOriginals`も処理対象グループがなくなった最終周回で公開準備を呼ぶ。
- 保持処理と公開の競合検証はトランザクション内でも全件snapshotの解析・fingerprint構築を行う。実機でのwriterロック競合に寄与し得るが、バックアップ失敗時のロック所有者をこの証拠だけで単一関数へ断定しない。

## Nevermindの保存状態と遅延に関する限界

- 対象アルバム `MPREb_jPOYfjGgApr`、アーティスト `UCrPe3hLA51968GwxHSZ1llw` を実DBで確認。バックアップ時点でNevermind、Nirvanaおよびアルバムページ13曲すべての英語名が公開結果に保存済み。
- 13曲のページIDは直接Art Trackではないものを含み、取得元の原語とページ曲を結ぶ参照経路が使われている。直接取得状態がEMPTYでも、参照による英語名の公開と矛盾しない。
- 保存された公開結果の時刻は10曲が01:20:47、先頭3曲が01:34:55–58。アルバム01:20:47、アーティスト01:23:35。英語名候補の取得成功時刻は多くが01:17台。これらは保持されている最新の観測/公開時刻であり、最初に画面が英語化した時刻や各リクエストの待時間を意味しない。
- 全体処理の過剰な再解析・割当は取得後の判定/公開も遅らせる有力な原因。ネットワーク待ち、参照取得、再試行待ちの寄与を分離した計時は未実施。特定の待ち時間すべてを上記関数へ帰属しない。

## 今回の調査結論と次の改修対象

- **OBSERVED:** 通常操作約7回で平均約4.44コア分のCPU、大量GC、SKIN約8℃上昇。描画だけの負荷として許容する状態とは判断しない。
- **REPRODUCED (PC/実機DB):** 原語名の集約・公開に、変更0件でも約1.5GiB/回を割り当てる過剰処理を特定。実機の全名称SQL反復とGC主体の負荷に整合する有力な主要原因であり、単なる仮説から再現可能な改修対象へ進んだ。ただし実機での全CPUの寄与率や修正前後の因果比較は未測定。
- 優先する改修は、(1)同一snapshotの解析・評価入力を共有して重複decode/正規表現生成を除く、(2)変更対象と依存関係へ公開処理を限定して無変更の証拠JSONを再生成しない、(3)書込トランザクション内の全件処理を除き必要な依存関係だけで整合性を検証すること。観測/撤回・参照・アルバム依存・古い結果による上書き防止は保持する。
- 保存対象一覧の同値再通知による全対象scheduleも候補として残るが、今回は寄与率未計測。名称処理の上記再現を先に改善し、際限なく別課題を追加しない。
- 合格条件案: 同じDBで無変更更新・1曲追加の割当/CPUが大幅に減ること、原語公開/参照/競合の既存正当性を維持すること、実機の同じ約7回操作でCPUが操作後に落ち着き発熱が改善すること、更新中バックアップが失敗しないこと。具体的な性能閾値は改修時に基準値と比較して定める。
- 改修着手前は調査と診断ヘルパー・この記録の更新のみ。アプリソース、APK、端末DB/設定を変更していない。バックアップ取得完了後は追加再現不要・端末を自由に使用可能と通知済み。

## 09/30 改修: 原語処理の重複解析と無変更の公開準備を削減

ユーザーの「改修に入って」により、上記で再現した名称処理の修正・テスト・APK作成へ移行。
9/29のキュー文言/再生アニメーション差分は維持。表示名の判断基準とDB schemaは変更しない。

### 実装

- `OriginalEvidenceCache`にJSON文字列・対象kind/ID・名称をキーとして、原語候補、評価、評価入力情報の解析結果だけを保存。JSONツリーを保持せず、16,384エントリ/キー文字列合計8,000,000文字の両方でLRUを制限。参照/更新/容量管理を同期し、解析はロック外で行う。同じIDでも証拠変更・撤回・別名なら再利用しない。
- 原語候補の動画ID用正規表現を共有し、decodeごとの生成を除去。
- `OriginalAssessmentInputs`の候補/アルバムfingerprintを必要になった時点で作り、一つのsnapshot内で再利用する。保持判定3行のために全7,163原語分をhashしない。fingerprintの内容・versionは従来と同じ。
- 直接原語、プロバイダ参照、プレイリスト参照の判定集計で同じ`OriginalPublicationInputs`を共有。
- repositoryの直列公開workerが`OriginalPublicationPreparer`を所有。対象自身の全行、参照元の証拠、依存するアルバム曲集合、既存公開結果を比較し、変化していない対象の証拠JSONを再生成しない。snapshot全体と既存公開結果が同一なら入力集計も省略する。
- 公開cacheは毎回現在の対象集合へ置き換え、過去snapshotを蓄積しない。保持量は現在のDB/依存関係に比例するため、このcacheまで固定容量とは説明しない。保存直前の既存transaction内競合検証は維持。
- `saveOriginalTitle`では必要な`und/art-track-original:*`行だけを読み、翻訳・表示候補をトランザクション内で読み込まない。書込中の原語snapshot読取自体は残るが、繰り返しJSON解析と無関係な全候補hashを削減。
- 保存対象の再通知は従来のまま。単純な一覧の重複除去は、同じID集合でも保存状態変更によってアルバム取得資格が変わる場合を遅らせ得るため採用しない。

### 検証方法と中間結果

- 新規JVM回帰テスト: 証拠cacheの対象/名称/payload分離とeviction、公開cacheの同一アルバム依存変更・pending/完了・参照撤回・外部公開結果変更。既存の厳密なcodec/言語判定/参照/原語保持テストも実行。
- JVM関連15クラス176件はPASS、失敗/skipなし。Gradleの初回はsandbox既定のホームとnetwork制約でwrapper起動に失敗したため、既存`C:\Users\callu\.gradle`を明示して許可されたビルド環境で`--offline`実行。以後Gradleは常に単独実行。
- 実機DBの7,975公開結果を、変更前の既存classで固定時刻 `2000000000000` に生成して保存。改修後の全kind/ID/英語名/証拠JSON/evaluatedAtと完全一致。比較TSVのSHA-256は双方 `f9cb4e17fd2cb82673308c338e146af5810e0bede96b490913926924afbd76f9`。
- PC上の同じDB、同じ診断ハーネスで無変更公開準備は約1,534.8MiBから約0.55MiB、1取得元3行の評価保持は約256.4MiBから約8.06MiBへ減少。新しい1取得元の再観測時は約59.5MiB。実機のPSSや温度の改善量ではなく、呼出スレッドの割当差分。
- 一時AVD `Pixel_9_API_35`を`-read-only -no-snapshot-save -no-window -no-audio`で起動。arm64変換を持つAPI35エミュレータ。既存の実機用ADBサーバーを再起動せず、専用ADB port5038/localhost:5557で接続した。エミュレータの時間・温度を実機性能として扱わない。
- Android初回37件中36件PASS、`OriginalAssessmentBatchingDeviceTest.anAlreadyForegroundAlbumsLateOriginalsInterruptTheRemainingBackgroundBatch`だけtimeout。診断phaseを追加した再現では `English foreground names` 待機で、model呼出しは `bgtrack0000→bgtrack0001`、前景3曲の日本語表示は済んでいた。
- 原因はテストが別々のRoom collector（表示と原語評価）の順序を仮定していたこと。表示更新だけを待って最初のmodelを解放すると、原語入力通知が届く前に次の背景model gateへ入り得る。Runtimeへ原語入力通知送信後の観測callbackを追加し、テストは前景3曲の観測完了を待って解放する。sleep/timeout延長で回避せず、前景が次に処理されるassertは維持する。
- 独立したコードレビューでも、証拠キーの分離、公開依存の比較、保存前の競合検証、cacheのスレッド所有、原語限定DAOを確認。重大な不正再利用や依存漏れは見つからなかった。

### 最終ソースと確認結果

- 対象: branch `restart/artist-20260905`、HEAD `74a37dc3e1437ae6da9a254ec3a0491a6fd19055` + 9/29の既存差分 + 上記9/30改修。未コミット。DB version/schema変更なし。
- 最終Gradleは `--offline` で、`:app:testCoreDebugUnitTest` の下記選択と `:app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease --console=plain` を一度の実行へまとめた。`BUILD SUCCESSFUL in 3m 7s`、799 tasks中40実行。最終ログは無視対象 `build/diagnostics/physical-heat-names-20260930/build-resource-verified.log`。
  - `com.dd3boh.outertune.models.metadata.*`
  - `com.dd3boh.outertune.repositories.Original*`
  - `com.dd3boh.outertune.repositories.Metadata*`
  - `com.dd3boh.outertune.repositories.ProviderSongReferenceTest`
  - `com.dd3boh.outertune.repositories.PlaylistSongReferenceTest`

| 確認 | 最終結果 |
| --- | --- |
| JVM関連テスト | **PASS: 15クラス176件**、失敗/エラー/skip 0。テスト本体合計1.596秒。 |
| Android/Room/非同期テスト | **PASS: 37件**、21.241秒。`OriginalAssessmentRetentionDeviceTest`、`OriginalAssessmentBatchingDeviceTest`、`MetadataPublicationLifecycleTest`、`AlbumOriginalRecoveryTest`、`MetadataPartialIdentityTest`、`MetadataRelatedProofRetentionTest`、`MetadataManualProtectionTest`、`DatabaseSnapshotDeviceTest`。最終debug/test APKをAPI35の一時AVDへインストールし、`am instrument -w -r -e class <上記クラスをカンマ区切り> com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner` で実行。ログ `android-resource-verified.log`。 |
| 実機DBの出力比較 | **PASS: 7,975件の全出力が変更前とbyte単位で一致**。最終ビルドclassで再生成した `verified-prepared.tsv` と変更前 `baseline-prepared.tsv` のSHA-256が上記 `f9cb4e…76f9` に一致。 |
| 配布APK | **PASS:** `coreRelease`、`arm64-v8a`のみ、package `com.dd3boh.outertune`、versionName `0.10.2-b1`、versionCode `71`、minSdk24/targetSdk36、9,202,061 bytes。`apksigner verify --verbose --print-certs`成功、APK Signature Scheme v2。 |
| 配布APKの起動・保存済み表示 | **PASS:** 配布先へコピーしたAPKをAPI35/arm64変換対応AVDへ上書きインストール成功。cold start `Status: ok`。ライブラリ→保存済みNevermindへ遷移し、Nevermind/Nirvana、13曲の件数、画面内の先頭曲名が英語で表示されることをUI hierarchyで確認。対象PIDのlogcatでFATAL EXCEPTION/ANR一致0。実通信成功や実機での速度をこの確認に含めない。 |

### 最終の負荷比較（PC・同じ実機DB）

Gradle終了後に同じ `MetadataBench.java` を最終コンパイルclassで実行。ウォームアップ後の5回平均。JSON読取を含むPC JVMの対象関数内の累積割当であり、実機のPSSや1操作全体の消費量ではない。

| 対象 | 改修前の割当/回 | 最終版の割当/回 | 最終版のwall time/回 |
| --- | --- | --- | --- |
| 無変更の公開準備 | 1,534.808MiB | **0.549MiB**（約99.96%減） | 2.537ms |
| 同じ1取得元3行の評価保持 | 256.408MiB | **8.059MiB**（約96.86%減） | 42.452ms |
| 1取得元3行の観測時刻だけ変更して公開準備 | 同条件の改修前計測なし | 58.896MiB | 172.410ms |

- 記録: `OuterTune_29_20260930015950/verified-incremental-benchmark.jsonl`、`verified-retention-benchmark.jsonl`。PCの短時間計測であり、タイマ精度・JIT・環境差があるためwall timeを実機の高速化率へ換算しない。
- キャッシュが温まった反復処理の改善を測定したもの。初回起動の全処理コストが0.549MiBになったという意味ではない。

### 配布物と未確認事項

- 配布APK: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-metadata-resource-20260930.apk`
- SHA-256: `4dbfb05bc6d887fd121833ae2505640db58b617ea26211a2eb6245ba87aaea93`。ビルド出力と配布用コピーが一致。
- 署名証明書SHA-256: `45a8c1d0b4e914882ff085b18098cd917679bafedda7debd8a3c48b01727026d`。
- **BLOCKED（実機未接続）:** 最終検証時のADB一覧にSamsung実機はなく、実機へ更新していない。同じ「個別曲再生→アルバム→1曲をライブラリ追加・DL」約7回でのCPU・GC・温度の改修前後比較、操作後CPUの収束、更新中のバックアップ成功は未検証。自動テスト合格だけで発熱解消とは判断しない。
- **未検証:** Nevermind等の新規取得から英語表示までの実機待ち時間。保存済み名称の一致とrelease上の表示は確認したが、通信・再試行を含む待ち時間短縮は別途計測が必要。
- releaseの実通信は検証PCの既知の証明書制約に従い反復確認していない。証明書検証の変更なし。
- 次の確認: 実機へ上記APKをデータ保持で更新後、温度が落ち着いた状態から同じ約7回操作を行い、従来と同じCPU/GC/温度採取方法で比較する。操作終了後も短く採取し、バックアップ保存を確認する。これは実機での合格判定として残す。
- 終了処理: `git diff --check`合格。一時AVD `emulator-5556` を `emu kill`（`OK: killing emulator, bye bye`）で終了し、専用ADB port5038も停止。既存の実機用port5037は停止していない。エミュレータ操作終了・自由に操作可能とユーザーへ通知済み。

## 初回修正版の実機再現: OOMを確認して中止

- ユーザーの操作開始希望により実機へ再接続。USB承認後の古い未承認transportを対象実機の `adb reconnect` で更新し、接続成功。最初の修正版APKの実機SHA-256が `4dbfb05b…aaea93` と一致、最終更新12:41:13、version71。これにより旧APKでの再現とは区別できる。
- 読取採取は12:44:15開始、ユーザーへ12:44:59に開始合図。初期のHAL SKIN32.8℃、USB充電中。画面操作はユーザーが担当し、こちらから実機の画面・アプリ状態を変えていない。
- ユーザーから「何度も強制終了、温度も高い」と報告。追加再現を止め、画面を消すこと、ログ取得後に設定からOuterTuneを強制停止することを案内した。操作回数は未確認。
- PID20939の最初のcrashは**12:45:47の `OutOfMemoryError`**。heap growth limitは268,435,456 bytes（256MiB）。exit-infoとcrash bufferに、12:46:05/12:46:31/12:47:01の後続プロセスでもOOMによる終了を確認した。
- 同じcrash bufferには今回確認した最終更新時刻より前の12:39:43にもOOMがある。ただし、その時点で実行されたAPKの同定はできていないため、改修前後の因果比較には使わない。今回配布した初回修正版でOOMが発生した事実を合格条件のFAILとして扱う。
- R8対応表を採取フォルダへ保全し、12:45:47の該当フレームを照合。`scheduleIdentityEnrichment` → `metadataNameSnapshot` → Roomの全行Entity生成中に割当失敗。最後の割当場所は特定できたが、それだけで保持メモリすべての所有者を特定したわけではない。
- 採取はプロセス消失により12:45:50に自動終了。最初のプロセスだけの有効なCPU採取は約90秒。操作前40.015秒は時間加重平均0.05%、合図後44.985秒は136.40%、末尾29.985秒は204.64%（最大288.48%、100%=1コア）。合図と実際の操作開始には差があり、境界を跨ぐ5秒区間は操作別集計から除外した。
- 最初のプロセスのGC111回、うちBackground97回。採取中HAL SKIN最大35.6℃。後続プロセスの負荷/温度や操作終了後の収束はこの集計に含まれない。前回325秒/約7回とは長さ・完了状態が異なり、数値が低いことを改善率として扱わない。
- 証跡は無視対象 `build/diagnostics/physical-heat-names-20260930/reproduction-124415/`。`metadata.json`、`phases.jsonl`、`postfix-summary.json`、`crash-buffer.txt`、`app-exit-info.txt`、`crash-mapped-app-frames.txt`に保存。端末とホスト時刻は秒精度で一致、双方+09:00。

## OOM追加対策

- 1取得元の同一性補完・取得中の再確認・playlistの取得元文脈を `metadataNamesForSource` へ限定。provider参照は対象/取得元/競合参照元を単一SQLで読む。ネットワーク待機中にライブラリ全体のsnapshotを保持しない。通信後の同じ取得元の再検証・旧参照撤回は維持。
- 公開preparerは大きな証拠JSONの旧世代を保存せず、名前・評価時刻・証拠SHA-256の状態だけを保持。cache hitは今回のDBから読んだEntityを返す。未保存の結果と同じ名前/時刻の外部proof変更も再検証する。完全同値の高速経路はnames snapshot一世代を保持するが、公開証拠の強参照は残さない。
- commit guardで全公開証拠をもう一度読み込むことをやめ、変更対象だけを単件照会して既存結果を比較する。DB schema変更なし。
- Java memory helperは同じnames/publications TSVを毎回stream読込して新しいEntity/Stringを作り、10回更新。`-Xmx256m -XX:-CompactStrings`、強制GC後の保持量と代表weakrefを検査。PC JVMはARTと同等ではなく、読込ピークにはTSV/Base64変換の一時領域が含まれる。
- 対策前: 10回後94.35MiB保持、観測ピーク238.70MiB。初回snapshot/公開証拠が生存し、preparer解放後29.49MiBへ低下。これは無限に世代が蓄積する証拠ではなく、現在のDB再読込とキャッシュ旧snapshotが同時に残る問題。
- 対策後の初回測定: 10回後62.91MiB保持。旧公開Entity/証拠weakrefは回収。preparer解放後29.62MiB。ログ `memory-after-oom-fix.jsonl`。名前・証拠を含む7,975件出力TSVは引き続き変更前と同一SHA-256。
- 対策後は公開証拠のSHA計算を毎回行うため、無変更公開準備の割当は48.370MiB/回、1取得元再観測107.712MiB/回。初回対策の0.549MiBをこの版の性能値に流用しない。割当総量だけでなく、保持量と実機上限への収まりを優先して検証する。
- JVM179件PASS（15クラス、失敗/skip0、テスト合計2.284秒）。Android関連試験、大規模DB試験、最終releaseはこの時点では未完了。

### 件数はダウンロード曲数ではない

- ユーザーから「フォルダ曲を含むため35,157/7,973件なのか、DLはもっと少ないはず」と質問があり、同じバックアップをread-onlyで集計。バックアップ時点のsongはオンライン766曲、ローカル407曲。オンラインの `dateDownload IS NOT NULL` は**25曲**、`inLibrary IS NOT NULL`は26曲。現在の端末上のダウンロードファイル数を改めて数えた結果ではない。
- metadata_nameの対象: SONG3,654（15,627行）、ALBUM2,402（10,061行）、ARTIST1,921（9,469行）。publicationはそれぞれ3,654/2,402/1,917行。1対象に複数言語・複数取得元の候補があり、DLやライブラリ保存とは別のキャッシュ。
- metadata_nameのSONGをsongへJOINするとオンライン766曲/4,474行、ローカル曲IDの一致0。残る2,888曲/11,153行はsong本体へ保存されていないオンライン対象の名前情報。フォルダ曲407件が今回のSONG名称候補の件数を増やしているわけではない。
- この量のキャッシュ自体を「ユーザーが大量DLしたため」と扱わず、1曲の補完で全件を実体化する実装側を改修対象にする。

### 追加対策版の最終検証・配布

- 上記に加え、入力変更判定を「証拠JSONを除いた行＋型付き原語候補」に変更。評価だけの更新を入力変化にしない従来の意味、時刻/優先度/対象/取得元/元証拠の変化、参照撤回、無効な元証拠のraw JSON、並び順を維持し、全原語のJSON再encodeを除去した。8件の回帰テストを追加。
- 公開状態の証拠hashは、直列preparer内の8KiB bufferでUTF-16 code unitを順にSHA-256へ渡す。全文を別のUTF-8配列へ変換する一時割当を除去。内部cache専用の比較値であり、保存される証拠JSON/入力fingerprint/schemaは変更しない。
- 最終ソース: 同じHEAD `74a37dc3e1437ae6da9a254ec3a0491a6fd19055` 上の未コミット差分。9/29キュー/描画修正を維持。実機データの削除・設定変更は実施していない。

| 確認 | 最終結果 |
| --- | --- |
| JVM | **PASS: 16クラス187件**、失敗/エラー/skip0、テスト本体計1.937秒。上記と同じ選択条件に新InputKeyテストを含む。 |
| Android | **PASS: 64件**、136.954秒。先の37件に`AlbumProviderSongReferenceTest`、`AlbumPlaylistReferenceTest`、下記`MetadataLibraryScaleDeviceTest`を追加。最終debug/test APKで実行。 |
| 大規模DB/小ヒープ | **PASS:** 元の35,157 names / 7,973 publications / 証拠23,094,507 bytesを別名DBへコピー。heap上限**201,326,592 bytes（192MiB）**をassert。1取得元の再観測20回について毎回の公開更新、50曲のdetail取得・DB保存、最後の公開更新を確認し、背景例外なし。元DBのSHA不変も確認。 |
| 表示名の意味 | **PASS:** 7,975件全出力を最終classから再生成。`oom-final-prepared.tsv`のSHA-256が改修前と同じ `f9cb4e17fd2cb82673308c338e146af5810e0bede96b490913926924afbd76f9`。 |
| ビルド | **PASS:** 関連JVMテスト＋`:app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease`、`--offline`、BUILD SUCCESSFUL in 2m44s。799 tasks中41実行。 |
| release APK | **PASS:** `coreRelease`/`arm64-v8a`のみ、versionCode71/versionName0.10.2-b1、minSdk24/targetSdk36。署名検証成功、従来と同じ証明書。配布コピーをAVDへ上書きインストールしcold start成功、ライブラリ→保存済みNevermind/Nirvana/13曲および画面内の英語曲名表示を確認、対象PIDのFATAL/ANR一致0。 |

- 大規模試験は外部DB引数 `-e metadataScaleDatabase /data/local/tmp/metadata-scale-song.db` を渡す任意診断。引数を渡して実行し、今回はskipしていない。通常suiteで引数がなければこの診断だけskipする。
- Runtimeは通信なしの応答fixtureと決定的な言語detectorを使い、現世代の表示名/alias mapを本番同様に保持。native言語モデル、再生、画像、全UIを含む実機の完全再現ではない。sampled heap peakは165,328,352 bytes（約157.7MiB）。1秒間隔のsampleは瞬間peakを保証せず、エミュレータの時間/温度を実機の改善率へ換算しない。
- 最終PC測定: 同じ10回再読込試験のGC後保持量**63.022MiB**（対策前94.355MiB）、代表の旧公開Entity/証拠weakrefは回収。全文JSONを保持する世代cacheの排除を確認。これはアプリ全体の保持量ではない。
- 最終PC割当: 無変更公開準備**3.137MiB/回**、1取得元再観測62.111MiB/回、入力変更key2.067MiB/回。最終memory/割当測定は `memory-final-oom-fix.jsonl`、`oom-final-incremental-benchmark.jsonl`、`oom-final-key-benchmark.jsonl`。上記中間値0.549MiB/48.370MiBを最終版の値として扱わない。
- 最終ログ: 無視対象の`build/diagnostics/physical-heat-names-20260930/build-oom-final.log`、`android-oom-final.log`、`android-oom-final-memory.txt`、`oom-final-release-*.txt/xml`。一時AVDと専用ADB port5038は終了。実機は途中から未接続となり、追加対策版を実機へインストールしていない。
- **新しい配布物:** `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-memory-fix-20260930.apk`（9,202,061 bytes）。SHA-256 **`5ee1720ba5b3f31584a979f24cf27ba3fb5111e2fd8d135ece87436626dbc224`**。ビルド出力と配布コピーが一致。証明書SHA-256は従来どおり `45a8c1d0b4e914882ff085b18098cd917679bafedda7debd8a3c48b01727026d`。
- **残る確認:** 実機での通常操作/再生中の安定性、CPU/GC/温度の比較、新規取得から英語表示までの待ち時間、操作中バックアップ。今回の中止後は実機での追加再現を要求していない。releaseの実通信は既知の検証PC証明書制約に従い未検証。強制終了・発熱が実機で解消したという完了判定は保留する。

## ユーザー報告による未解決状態とコミット時点

- ユーザーより「実機ではまた，問題があることを確認しました．いったんここまでをcommitしてください．」との依頼を受領。実機の問題が残っている状態を保存するチェックポイントとし、解決済みとは扱わない。
- この最新報告については具体的な症状、発生時刻、端末にインストールされたAPKのSHA-256、新しいクラッシュログを取得していない。前回確認したOOMと同じ原因か、追加対策版で発生したかは未確定。
- コミット対象は基点 `74a37dc3e1437ae6da9a254ec3a0491a6fd19055` 以降のキュー・描画・名称処理の改修、回帰テスト、検証記録。上記のJVM187件・Android64件の結果を保持し、この依頼では追加の改修・再試験・実機操作を行っていない。
- APK、実機バックアップDB、診断ログは無視対象のローカル成果物として保持し、コミットに含めない。
- 調査再開時は最新の症状と使用APKを照合し、対応するログから原因を確認する。現時点では実機の合格判定を行わない。
