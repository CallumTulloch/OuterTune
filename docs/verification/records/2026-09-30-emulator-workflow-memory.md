# 2026-09-30: 検索・アルバム・DL・保存の連続操作とメモリ

**最新実機結果（19:20–19:24）:** 追加改修APKでも、操作停止後にCPU平均206.7%とGC反復が継続。SKIN最大42.1℃、温度制御status 2。通知操作のPendingIntentは4件で維持されたが、発熱・負荷の問題は未解決。詳細は末尾の実機再確認節。

**追加改修のエミュレータ結果:** 通知操作のPendingIntentを4件に抑え、同条件の7回操作では観測heap最大143.1→131.8MiB、平均CPU135.8→125.9%を記録。保存/DL/再生、Billie Jeanのアルバム内の曲ID、回帰試験と新release APKの起動・保存一覧確認はPASS。操作後のidle CPUは0.3→4.8%で改善していない。最新APKと条件・限界は末尾の追加改修節に記載し、以下の旧版結果は履歴として保持する。

**前回の実機結果（15:32監視終了）:** 同一APKの実機検証でOOM/クラッシュ/ANRのログは未検出だが、高CPU・GC反復・温度上昇と通知用PendingIntentの増加を観測。ユーザーの終了時の体感は「ほんのり熱いくらい」。途中でユーザーが削除・再インストールしており、前後を同一データ条件の改善比較にはしない。後述の実機受動監視節に記録。

- 起点: `528e4e7f`、開始時の差分なし。直前の追加対策版でも実機問題は未解決との報告。
- 依頼: 実機へ再確認を頼む前に、エージェントがエミュレータで曲検索・アルバム表示・DL・ライブラリ追加を繰り返して修正する。
- 前回のrepository単独試験では、通常Applicationの全repository・UI・画像・再生・DL・native言語モデルを同時に動かしていなかった。
- 今回はPixel_9_API_35の一時read-onlyセッション、emulator-5556、専用ADB port5038を使用。既存実機バックアップのsong.dbだけをdebugアプリへコピー。元バックアップ・元AVD・実機を変更しない。
- 合格条件: 実画面から複数曲の検索→アルバム→曲の保存/DL→再生を反復し、強制終了しないこと。2回目以降は前の曲を再生したまま次の検索へ進む。処理の進行、操作後のCPU/GC収束、メモリの推移を記録する。見つけた原因に対する回帰試験を追加し最終ソースで再実行する。
- 制約: x86_64上のarm64変換、managed heap上限192MiB。CPU値は実機の温度・電力へ換算しない。実通信はdebugを使用し、既知のrelease証明書制約は再調査しない。
- 診断先: 無視対象 `build/diagnostics/emulator-workflow-20260930/`。
- 元データ: 実機由来のversion29 DB、名称35,157行・公開7,973行・公開証拠23,094,507 bytes。原本は変更せず、一時debugアプリのDBだけを毎回同じコピーへ戻した。

## 再現方法と制約

- この環境ではdebugの実通信でも証明書検証失敗/接続タイムアウトがあり、YouTubeからの検索・DLを最後まで実行できなかった。証明書検証は変更していない。
- `WorkflowReplayDeviceTest` は明示的な `-e workflowReplay true` でのみ起動するandroidTest。通常Application、画面、全repository、Room、実native言語モデル、Media3の再生/DLを同時に動かし、HTTP応答のみ12アルバム×12曲の固定応答へ差し替える。アカウント情報は使わない。
- 音源は120秒の固定WAVをplayerCacheに用意。画像は512×512の固定画像。画面のDownload操作が本番の解決処理/DownloadManagerを通ってdownloadCacheへコピーする。実インターネットからの音源転送、実YouTube応答の変更、圏外/回線切断、多数の異なる高解像度画像はこの試験の対象外。
- 操作はADB UI hierarchyと画面から、検索→Songs→曲メニュー→View album→曲メニュー→Add to library→Download完了→Playを別の7アルバムについて繰り返す。リリースへfixtureやHTTP差し替えを混入させていない。
- 5秒ごとのART使用ヒープ/GC、`/proc/<pid>/stat` のCPU時間、画面XML/PNG、MediaSession、終了時meminfoを保存。ヒープ値はサンプル最大で瞬間最大ではない。GC時間はART累積指標であり、画面停止時間ではない。CPUは1core=100%。同じホスト上のビルドや一部区間のsampling profileがあるため、厳密な速度/発熱改善率として使わない。

## 原因と変更

1. 名称監視Flowと評価workerが同時に全件Entityを保持していた。監視はRoomの軽い存在確認queryによるinvalidate通知とし、全件読込みを直列評価workerへ集約。自己の評価出力による通知は入力keyで抑制する。診断用の全件callbackはテストで指定した場合のみ。
2. 公開証拠約23MBを毎回全件Entityへ展開していた。Cursorで順に読み、変更された旧/新証拠だけを最大64件保持してcommitする。残件/競合は再実行し、元の依存関係/旧公開値のtransaction内照合を維持する。初回の公開生成にも同じ上限を適用。
3. アルバムの参照補完が全ライブラリのsnapshotを通信待ち中も保持していた。対象曲と関係する取得元の読取り、playlist単位の撤回照合、通信前の小さな候補リストへ縮小する。
4. 中間版ではメモリは下がったがCPU/GCは改善しなかった。追加profileでは、同じ証拠のString.equals→charAtが目立った。decode cacheの完全一致hit後に、同内容の現在のStringへ参照を差し替え、同じsnapshot内の再decodeで古いStringとの全文比較を繰り返さないようにした。内容・hash・保持文字数・対象IDの条件は変えない。
5. 評価結果と公開結果をtransactionで保存した直後、自己の評価更新だけを再読込みして全公開証拠をもう一度処理していた。入力が不変で未評価群がなくなった場合は終了する。外部入力の変更、分割commitの残件、競合時のretryは省略しない。
6. 原語評価が依存しない日本語表示名17,245行まで読んでいた。評価/transaction内の照合用queryを、既存 `OriginalPublicationInputs` の依存条件と同じ英語alias・und証拠（manual以外）に限定。元DBでは35,157→17,912行になる。表示用queryは維持。英語名と最終取得元が両方消えたときも、既公開の対象はCursorから訪問して撤回する。

## 検証経過

| 段階 | 7操作の時間 | 観測heap最大 | GC回数の差分 | GC累積時間の差分 | 平均CPU |
| --- | ---: | ---: | ---: | ---: | ---: |
| 起点528e4e7f | 421.2秒 | 192.0MiB | 338 | 146,233ms | 195.7% |
| メモリ対策のみの中間版 | 421.0秒 | 148.2MiB | 331 | 150,081ms | 196.7% |
| 文字列比較/重複処理を減らした中間版 | 367.7秒 | 143.7MiB | 303 | 112,024ms | 170.0% |
| 評価queryも絞った最終版 | 367.2秒 | 144.8MiB | 297 | 98,205ms | 160.6% |

- 起点でも今回の7回ではOOM/クラッシュは発生しなかった。上限近くのメモリ圧迫とGC負荷を再現したのであり、実機の最新不具合を同一原因のOOMと断定しない。
- 中間版は7曲の保存、7件の完成したDL index/全音源cache、再生を確認。操作後のidle CPUは0.1%（短い20.7秒区間）。CPUの改善とせず追加対策へ進んだ。
- 最終版の再実行結果は後述する。最終版でない上記結果を最終PASSへ流用しない。

最終版は同じ7操作で観測heap最大が47.2MiB低下し、GC累積時間も減った。中間段階の小差やCPU比率を厳密なベンチマーク値とは扱わない。最終版の計測中はビルドやsampling profileを実行していない。最終インストールの `base.apk` のSHA-256も下記debug APKと一致した。

## 最終ソースの回帰試験とビルド

- **PASS: JVM 191件/16クラス、失敗・エラー・skip 0。** 名称/原語policy、codec、証拠cache、公開入力key、公開/参照撤回、対象別証拠保持、fetch scheduling/queueを含む。新規に分割公開の完走、外部による同名/同時刻の証拠変更、同内容の新String読込みとevictionを検査。
- **PASS: Android 85件、131.940秒。** `OriginalAssessmentRetentionDeviceTest`、`OriginalAssessmentBatchingDeviceTest`、`MetadataPublicationLifecycleTest`、`AlbumOriginalRecoveryTest`、`MetadataPartialIdentityTest`、`MetadataRelatedProofRetentionTest`、`MetadataManualProtectionTest`、`AlbumProviderSongReferenceTest`、`AlbumPlaylistReferenceTest`、`MetadataStartupTest`、`MetadataOriginalRetryTest`、`MetadataRefreshScopeTest`、`MetadataFetchEfficiencyTest`、`MetadataNameRepositoryTest`、`MetadataLibraryScaleDeviceTest`、`DatabaseSnapshotDeviceTest`。
- 新規Androidケースは150対象の初回公開が64件上限を越えて完走することと、テーブルの存在確認がtrueのままでも追加対象のinvalidate通知が届くことを確認。既存ケースで遅延した旧入力/認証/国変更、共有取得元の撤回、保存表示の維持を確認。
- 大規模試験も同じ実DBを使い、20取得元の再観測と50件の詳細取得を完走。独立repository試験のsampled peakは128,947,040 bytes（123.0MiB）、heap上限201,326,592 bytes。全Application/UI試験の値とは区別する。
- Gradleは既存cacheを使って `--offline`、常に単独実行。最終本体のdebug/test/release＋JVMは `build-scoped.log`（4m27s）。Androidログは `android-scoped-final.log`、大規模メモリ記録は `android-scoped-scale-memory.txt`。その後の本番ソース変更なし。
- 最終debug APK SHA-256: `c0f83048b0d4a0a1aa6921410b286a6b2e1494745ebee9c35500919f47c7b534`。
- 配布APK: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-workflow-memory-20260930.apk`。SHA-256: `dd63e1fe7d481e7cdaf2409a6d2dcc6b6a5fcf6f2a2d40087dcfe1838f9a767f`。ビルド出力とコピーが一致。
- 署名証明書SHA-256: `45a8c1d0b4e914882ff085b18098cd917679bafedda7debd8a3c48b01727026d`。ABIはarm64-v8aのみ、release DEXにfixture名/HTTP宛先が含まれないことを確認。
- Android試験用に一時debugデータを初期化した後、音声アクセス権限ダイアログで最初のUI試行が検索前に停止した。これは試験環境の準備不足として区別し、権限を付与して元DBへ戻し、別ログ `replay-verified` / `verified-full` で最初からやり直して完走。評価queryを絞った最終版は `replay-scoped` / `scoped-full` で別途再実行した。

## 最終UI・release確認

- **PASS: 最終debug APKの全Application/UI試験1件、578.483秒（idle観測込み）。** 異なる7曲で検索→アルバム→保存→DL完了→再生の全eventが揃い、毎回MediaSessionの対象曲名とPLAYINGを照合。instrumentationも `saved=7 downloaded=7` と `OK (1 test)` を記録。Download indexだけでなく音源全長のdownloadCache保持を確認した。
- **OOM/FATAL EXCEPTION/ANRなし。** 操作終了後200秒を観測。最後の120秒音源が終了した後の82.5秒区間は平均CPU **0.8%**。終了時meminfoのDalvik Heap Allocは58,656KiB。メモリ解放を伴うmeminfoは連続操作のsampled peakとは別に、最後に一度取得した。
- **PASS: 実際の配布release APKを一時AVDへ更新。** Wi-Fi/mobile dataを切った状態でcold起動、保存済みアルバムと13曲の表示、DLフィルタ画面、曲のライブラリ追加を確認。再起動後も追加した曲が保存一覧に残り、曲数1→2と名称を確認した。端末上のrelease `base.apk` SHA-256も配布コピーと一致。release画像/XML/logは `release-final-*`、`release-persisted-library.*` 等。
- releaseの実通信検索/DL/再生は既知の制約に従って未検証。debugの連続操作も上記のHTTP replay条件に限定する。実機の温度・電力や実通信下の最新不具合を解決済みとは断定しない。
- 元の実機バックアップ、元AVDの永続データ、実機には変更を加えていない。raw DB・ログ・PNG/XML・APKは無視対象に保存。
- 一時エミュレータemulator-5556と専用ADB port5038は終了。通常のエミュレータを利用可能。コミットはこの依頼では行っていない。

## 再実行

一時的なPixel 9/API35エミュレータ（1080×2424、UIは英語）を使う。個人の常用インストールには実行しない。debug/test APKをインストールし、停止したdebugアプリの `databases/song.db` にcheckpoint済みのDBコピーを置き、対応する古いWAL/SHMを除く。原本/実機を操作しない。初期ウィザードと国/言語はfixtureで設定する。

```text
adb -P 5038 -s emulator-5556 shell am instrument -w -r \
  -e class com.dd3boh.outertune.repositories.WorkflowReplayDeviceTest \
  -e workflowReplay true com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner
```

別プロセスでリポジトリの `app/src/androidTest/tools/workflow_replay.py` を実行する。

```text
python app/src/androidTest/tools/workflow_replay.py --adb <adbのパス> \
  --serial emulator-5556 --port 5038 --cycles 7 \
  --output build/diagnostics/<今回の出力先> --finish --idle-seconds 200
```

- scriptは画面上の操作結果と各曲のMediaSession PLAYINGを検査し、idle観測後にテストへ終了signalを渡す。instrumentation側も独立に7曲の保存と7件のDL完了/完全cacheを検査する。
- instrumentationログ末尾の `OK (1 test)` と全操作eventの双方を確認する。ADBコマンド自体のexit 0だけではPASSにしない。
- UI画像、ログ、実DBは `build/diagnostics/` の無視対象へ保存。DBや個人ライブラリの中身はコミットしない。

## 後続の実機受動監視（15:10–15:32）

- ユーザー操作で曲検索・アルバム遷移・保存/DL・再生を検証。SM-S931Z、USB充電中。インストール済みAPKは上記の最終配布SHA-256と一致し、再インストール後も同じSHAを再確認した。
- 15:10:20開始、ユーザーの停止指示により15:32:45終了。採取プログラムの`STOPPED`とハートビートの`PAUSED`を確認。260サンプルとアプリ/システムログを `build/diagnostics/physical-workflow-20260930-151020/` に保存した。
- 読取のみ。エージェントによる実機のタップ、再起動、インストール、データ変更、logcat消去、meminfoによる明示GCは行っていない。実機UI dumpはidle待ち失敗で取得できず、後続画面の静止画は取得したが問題発生時の画面とは扱わない。
- 既存 `capture_reproduction.py` の処理を別ファイルへ複製して拡張した。ユーザーから再利用について指摘を受け、既存への小さな改修で対応可能だったことを説明した。監視途中の補正ではその採取プログラムを直接改修し、新たな採取プログラムを増やしていない。
- 15:22:54にUninstallerActivity / `deletePackageX`、15:23:09に新規インストールを記録。ユーザーも途中の削除・再インストールを確認。15:23:11の短命プロセス終了はSPEGによるEXIT_SELFで、クラッシュとは分類しない。
- プロセスの変化にはCPU採取が追従したが、当初のUID固定logcatは再インストール後のUID変更に追従しなかった。15:29:37に同じ採取先で再開し、`/proc/PID/status` のUID変化でログ対象を切り替えるよう補正。新UIDの15:23:09以降のログを端末の保持ログから回収した。採取プログラム切替中のCPUサンプルには短い空白がある。新UIDの別名回収ファイルとapp-logcatへの追記は重複集計しない。

| 区間 | CPUサンプル平均 / 最大（1コア=100%） | SKIN最大 | 温度制御最大 | GCログの回収後使用ヒープ最大 |
| --- | ---: | ---: | ---: | ---: |
| 削除前 15:10:21–15:22:50 | 132.0% / 412.8% | 40.7℃ | 1 | 172MiB |
| 再インストール後 15:23:16–15:32:41 | 210.6% / 408.1% | 40.5℃ | 1 | 104MiB |

- 前後で操作・データ条件が変わったため、平均値の差をAPK変更の効果と解釈しない。SKINは画面表面の直接測温ではない。GC後ヒープは瞬間最大ではなく、プロセスRSSとも異なる。
- 終了直前の最後の温度サンプルはSKIN39.7℃ / status1。ユーザーの終了時体感「ほんのり熱いくらい」を併記する。
- **新しい具体的な異常:** 再生通知のCUSTOM_NOTIFICATION_ACTIONに対応するPendingIntentが増加。Androidの警告カウントは削除前1,800、再インストール後は15:23:56の300から15:32:32の4,400へ増加した。
- `MusicService.updateNotification()` は4つのカスタム操作を毎回作り`setCustomLayout`へ渡す。組込みMedia3の `DefaultMediaNotificationProvider.java:563–567` は通知生成ごとに操作を作り、`DefaultActionFactory.java:169–181` は毎回requestCodeを増やして新しいPendingIntentを発行する。ログの連番と一致する。`MediaSessionImpl.java:532–536` はlayout転送に同値抑制がなく、legacy経路で通知更新に進む。
- metadata/name更新、artist credit更新、currentSongのDB通知等がアプリ側の通知更新入口。どの入口が過剰更新を支配するか、自己増殖するループの有無、発熱全体への寄与率は未確定。通常の再生位置timerは`notify=false`で、それだけを直接原因にしない。**この監視では本番修正していない。**
- 再インストール後15:23:32のHTTP400 `get_transcript` は字幕歌詞の取得経路。後続providerへ進む処理があり、これを曲再生やDLの失敗と分類する根拠はない。
- Billie Jean 4:55からのアルバム遷移で再生中表示が付かない報告も受領。楽曲版/MV版IDの不一致をAPIで確認し、[別の調査記録](2026-09-30-billie-jean-album-identity.md)に整理。名称問題や発熱との共通原因は未証明。
- **判定:** エミュレータでの条件付き試験PASSを、実機の発熱・負荷・音源ID問題の解決に置き換えない。今回の実機ログではOOM/クラッシュ/ANRは未検出だが、修正対象は残っている。

## 実機報告後の追加改修と最終検証

- ユーザーの改修指示を受け、通知更新と証拠文字列比較を修正。実機監視は停止したまま。診断先は `build/diagnostics/workflow-repair-20260930/`。
- `NotificationLayoutUpdater` は同一の操作表示を再送しない。Media3の等値比較がextrasを無視するため、extras付きは抑制しない。
- `PlaybackNotificationProvider` は同じMediaSessionの4種類のpayloadなし操作について、Media3が生成したPendingIntentを再利用。タイトルとアイコンは毎回反映し、セッション変更・終了時に破棄。未知の操作・payload付き・標準再生操作は従来のfactoryへ委譲する。Media3 submoduleは変更しない。
- 現行配布debug APKで新しく採ったprofileでも、cache keyとEntityの証拠String比較がCPUを消費。前段の「最新Stringへ差し替え」は複数snapshot間で比較が往復するため、完全一致した証拠をDBの大きなsnapshot読取り時点で同じStringへ揃える。16,384項目/8,000,000文字の上限と完全一致条件を維持する。取得時刻・証拠変更・撤回の判断は省略しない。
- 最初の `baseline` はprofile取得用。6回完了後、エミュレータのネットワーク検証が失効して検索がローカルへ切り替わり中断した。通知のdumpsysは重複行を除くpackage集計で205件。後続の試行も検索先・操作タイミングの問題で中断し、成功や性能比較には数えない。
- 既存 `workflow_replay.py` を補強し、検索入力の出現を待ち、Onlineを明示、Songsの下部ナビゲーションと検索filterを区別。監視は既存 `emulator-workflow-20260930/monitor.py` をそのまま再利用。
- HTTP fixture試験を安定させるため、一時read-onlyエミュレータだけでcaptive portal検証を無効化してネットワーク接続を再作成した。TLS/アプリのネットワーク実装は変更していない。この条件では実ネットワーク切断判定を検証しない。常用AVDや実機に設定を保存しない。
- 対象は上記起点からの未コミット差分を含む追加改修版。アルバムの取得元ID対応も同じAPKに含むが、根拠・仕様・個別回帰は [Billie JeanのアルバムID記録](2026-09-30-billie-jean-album-identity.md) に分ける。上記の旧APK結果を追加改修後のPASSに流用しない。

### 回帰試験と同条件の連続操作

- **PASS: JVM 226件。** app 177件、innertube 46件、availability追加3件。証拠の同時snapshot、同一hashでも異なる証拠、同時刻の変更・撤回、cache上限・evictionを含む。
- **Androidは初回103件中102件PASS、通知1件FAIL。** Java interfaceのdefault methodがKotlinの委譲を経由しない経路を検出し、通知を消す操作を元factoryへ明示的に転送するよう修正。その後、影響する通知6件を最終ソースで再実行して全件PASS（0.896秒）。未解決の失敗はない。初回全件PASSとは記録しない。ログは `device-regressions.txt`、`device-notification-retry.txt`。
- **PASS: `baseline-stable` と `after-stable` の両方で7回のUI操作とinstrumentationが完走。** 同じ元DB・HTTP fixture・操作手順を用い、両方とも7曲の保存/DLと再生を確認。修正後は `saved=7 downloaded=7`、`OK (1 test)`（idleを含め609.971秒）。この2回の計測中にビルドやsampling profileは実行していない。

| 同条件の7操作 | 操作時間 | 観測heap最大 | GC回数の差分 | GC累積時間の差分 | 平均CPU | 操作後idle平均CPU |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 追加改修前 `baseline-stable` | 364.2秒 | 143.1MiB | 296 | 80,003ms | 135.8% | 0.3% |
| 追加改修後 `after-stable` | 347.1秒 | 131.8MiB | 290 | 72,984ms | 125.9% | 4.8% |

- heapはサンプル最大、CPUは1core=100%、GC時間はART累積指標。上表は同条件での観測差であり、端末一般の改善率や実機の発熱・電力には換算しない。
- **idleの改善は確認できていない。** 修正後の約77秒のidle区間は、アプリ起動から約10分となる09:12:06 UTC付近の短い再試行を含み、browse回数295→304、GC累積回数324→329へ増加した。それ以前の多くのサンプルはCPU0–1%だが、再試行区間を除外して良好な数値へ置き換えない。操作開始までの準備時間が異なり、再試行時刻をまたぐ条件は揃っていない。
- **通知PendingIntentの増加抑制を確認。** 改修前の終了時はカスタム操作84件／対象package計89件、改修後は早期・終了時ともカスタム操作4件／計9件。さらに通知回帰試験で2,000回の再構築でも4件に収まり、表示変更・payload付き操作・セッション終了時の破棄を確認した。発熱全体への寄与率は未確定。

### 曲IDのUI確認と配布APK

- **PASS: Billie Jeanの通常UI試験1件、163.460秒。** 検索の4:55版から再生→プレイヤーの曲名→Thrillerへ移動し、対応するアルバム行の再生中表示、保存、DL、行からの再生を確認。確定した音源IDは `Kr4EQDVETuA`、長さ295秒。debugの実際のプロセス再起動後も保存ID・長さ・DL表示が維持された。
- このUI試験はHTTP fixture条件。再起動後の再生とreleaseの実通信は未確認。既知のTLS制約への回避設定や証明書検証の変更は行っていない。
- 最終debug APK SHA-256: `b2da94d15f1979ca976598025fc052f725d9dc475302a6cbc87bbf0513bb446d`。
- 最終配布APK: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-workflow-album-20260930.apk`、9,202,061 bytes。SHA-256: `abb309c3265037bd4edbcacf86c3fae2a6f59a1761f1851d74a24370b99ad560`。releaseビルドは約3分。ABIはarm64-v8aのみ、fixture assetsを含まないことと署名を確認。署名証明書SHA-256は上記の従来署名と一致。
- **PASS: 配布releaseの起動→保存一覧→プロセス再起動→保存一覧。** 既存の保存曲1件を維持し、インストール済み `base.apk` のSHA-256が配布APKと一致した。ログ・画面は `release-*` に保存。
- 実機と元バックアップは変更せず、実機監視は停止・PAUSEDのまま。今回確認したのは条件付きの負荷低下、通知資源数の上限、曲IDの経路であり、実機の発熱問題を解決済みとはしない。一時エミュレータemulator-5556と専用ADB port5038は終了済み。コンソール終了は認証エラーとなったため、今回起動したポート・read-only引数・親子関係を確認したプロセスだけを終了し、補助プロセスも終了したことを確認した。通常のエミュレータを利用可能。

## 追加改修APKの実機再確認（19:20–19:24）

- ユーザーの「まあまあ暖かいので正常か確認」に応じた単発の受動調査。SM-S931Z / RFCY1103M9H、PID14927。インストール済みAPKのSHA-256は追加改修版 `abb309c3265037bd4edbcacf86c3fae2a6f59a1761f1851d74a24370b99ad560` と一致した。
- **操作条件の訂正:** 選択肢回答では一度「検索・アルバム表示・保存・DLを操作中」とされたが、その後「依頼した時点から操作はしていない」と明示された。最初の読取り19:20:43より前からUI操作なしとして判定する。未完了のバックグラウンドDLまでは否定できない。19:22頃のMediaSessionはSTOPPED。
- 既存 `physical-workflow-monitor-20260930.py` を再利用。今回の期待APKハッシュを第3引数で指定できる小変更だけ加えた。新規記録先は `build/diagnostics/physical-heat-check-20260930-1920/`。過去の実機・エミュレータ記録へ追記していない。
- 初回19:20:43はcurrent HALのSKIN41.7℃ / BAT40.6℃、thermal status 2。19:22:05–19:24:24の28サンプルではSKIN41.7–42.1℃、BAT40.2–40.5℃、全温度サンプルがstatus 2。USB給電・充電状態。SKINは端末が報告するセンサー値で、外装の直接測温ではない。
- **高負荷継続:** 同じ約139秒のCPU差分は平均206.68%、最小188.00%、最大230.99%（1core=100%）。`arch_disk_io_0..3` 合計平均130.34%、`HeapTaskDaemon` 27.23%。スレッド名からDB読取り・メモリ回収経路を次の調査対象とするが、具体的なqueryやループの原因は未確定。
- 同時間窓のGC完了ログは72回、GC後使用ヒープ70–100MB。単純なヒープ単調増加とは断定せず、大量の割当て・回収が続く負荷として扱う。RSSをJavaヒープへ読み替えない。取得ログではOOM / FATAL EXCEPTION / 対象アプリのANRを検出していない。
- 通知のカスタムPendingIntentは前後とも4件、対象アプリの全レコードは10件。通知の増殖対策はこの観測でも有効だが、高CPUと発熱を解消していない。
- **判定:** 放置後も約2コア分のCPUとGCが続き、温度制御も動作しているため、正常として完了にはできない。status 2は[Androidの中程度の熱制限](https://developer.android.com/reference/android/os/PowerManager#THERMAL_STATUS_MODERATE)。緊急・シャットダウン段階は今回観測していないが、充電だけを原因とする根拠もない。
- アプリ・画面・再生・データ・端末設定は変更せず、meminfo、明示GC、logcat消去も行っていない。採取は19:24:29にSTOPPED、採取プロセスの正常終了を確認。定期ハートビートは再開していない。アプリ修正・ビルドはこの確認では行っていない。

### 持続負荷のコード調査（根本原因の確度）

- **確定した増幅経路:** `MetadataNamesDao.metadataDisplayNames` はmetadata_name/metadata_original_publication変更時に全件JOINを再実行する。別のmetadata_name変更通知は `MetadataNameRepository` 245–258行から英語/undの全件snapshotを読む。入力key比較は読取り後であり、`conflate` も生成済みの結果を間引くため、Cursorから文字列・Entity・Listを作る費用は残る。
- 原語判定自身の保存も同じテーブルの変更通知を起こす。評価中の通知確認、各batchの終了、transactionの照合にもsnapshot読取りがある（同336/359/385行）。アルバム補完中の `saveOriginalTitle` も曲ごとにoriginal-source全件snapshotを読む（同1217行）。`mergeMetadataNameCandidate` には同値UPDATEを避ける条件がなく、観測時刻だけの更新も入力keyを変え得る。
- 前回のproof文字列canonicalizationはDB読取り完了後に実行される。保持重複・後段の文字列比較は減らすが、DBからの一時オブジェクト生成はなくならない。
- 別経路では、保存曲/アルバム一覧のRoom Flowが複数の関連テーブル変更を監視し、全件と関連Entityを再取得する。遷移後も購読が継続するFlowがあり、小さなartist等の更新が読取りを増幅し得る。
- **未確定:** 以上はコードとRoom生成コードで確認した設計上の増幅要因であり、実機の持続負荷を支配した特定SQL・更新元・残処理量は未採取。有限の残作業なのか、更新の循環なのかはまだ区別できない。前回入れた入力key比較・自己評価終了条件・公開変更なし判定は存在し、無限ループを証明したとは扱わない。実測CPU/GCと整合する原因候補であり、後述の新規データ試験でも今回の実機の根本原因は確定していない。

### 原因確定を先行する追加調査（19:40–19:56）

- ユーザーから「修正の前に原因を確定」と指示があったため、この調査では本番コードの追加変更・ビルドを行っていない。ブランチ `restart/artist-20260905`、HEAD `528e4e7f431d88a0bd8a0c6a3e0ac344b0364173` と既存未コミット差分を維持。既存の監視・UI再現プログラムと前回生成したdebug/test APKを再利用した。
- **実機のデータ条件を訂正:** 再インストール後のバックアップ復元は行わず、検索・保存・DLで追加したとの回答。再インストール前の01:59バックアップを使った調査結果は、今回の実機を再現した根拠から除外する。
- 後から回収した同じ実機PID14927のログでは、GC完了の最後の記録は19:25:20.807。Activityの `stopped(true)` は19:29:48.879で、それより前に大量GCの記録が途切れている。永続的な自己ループと断定する根拠はなく、有限の未処理作業が消化された可能性も残る。GCログだけから負荷終了の正確な時刻・原因は断定しない。
- 試験先は一時read-only/no-snapshot-saveの `emulator-5556`、専用ADB5038。実機は操作していない。診断先は `build/diagnostics/idle-cause-20260930/`。debug APK SHA-256は `b2da94d15f1979ca976598025fc052f725d9dc475302a6cbc87bbf0513bb446d`、test APKは `d2296c03cff8ea98874fea87f885bb5e18d76a4502b20cd87b862f0efa7943bf`。
- 旧DBを使った最初の試行はsampling profiler取得中にnative SIGSEGVで終了し、traceも解析できなかった。これは原因の特定に使用しない。profilerなしの旧DB試行も、復元なしという回答後に中止した。中止時のinstrumentation `Process crashed` はエージェントによるforce-stopと区別し、実機の自然クラッシュとして数えない。
- **PASS: 空のデータから通常UIで7回の検索→アルバム→保存→DL→再生を実行。** バックアップ未復元の `fresh-ui` / `fresh-run` では全eventが揃い、instrumentationが `saved=7 downloaded=7`、`OK (1 test)`（554.478秒）を記録。実際の通信応答を固定fixtureへ差し替え、120秒の試験音源をcacheに保持した条件であり、実通信・実際の音源転送・応答欠落や再試行量を再現したとは扱わない。
- 操作時間354.5秒、観測heap最大66.4MiB、GC回数差分33、GC累積時間差分10,805ms、平均CPU58.5%（1core=100%）。操作終了後も試験音源の再生は続くため、終了から120秒後以降を保守的なidle区間とした。その56.8秒の平均CPUは0.088%（丸めて0.1%）、最大区間CPU0.776%。実機の放置中約207%は再現していない。
- debugのJavaスレッドを一度取得した時点では、4本の `arch_disk_io` はすべてqueue待機。取得時は短時間VMを停止し、再開・detach後のUI進行も確認した。この単発stackを実機の高負荷stackの代わりには使わない。
- `SQLiteTime` の集計では、操作中の完結記録23,300件・累積10.186秒に対し、操作終了後はmetadata全件queryが0件。再生終了後からテスト停止前はalbum/artistの計3記録・1msのみ。記録数はCursorWindowの再実行を含むSQLite operation数で、DAO呼出し数ではない。長いSQLの末尾欠損93件は起動・操作中にあり、取得できた時間の合計は下限であってCPU時間や性能比較値ではない。終了処理10:56:09 UTC以降は除外。最終集計は `fresh-run-summary-105730.json`、既存metricsの結果は `fresh-ui/metrics.json`。
- **結論: 今回の実機の根本原因は未確定。** DB変更による全件読取り増幅はコードで確認したが、新規データと固定応答では処理が収束する。実機で継続した更新元・対象データ・通信応答の不足/再試行条件を照合する必要がある。今回の実データのバックアップをDownloadへ保存するよう依頼済みで、現時点では未受領。旧バックアップによる高負荷を今回の原因に置き換えない。
- 新しい本番修正は保留。再開時は現在のバックアップを既存 `physical-heat-names-20260930/audit_backup.py` で読取り、実データと応答条件を合わせて再現する。設定・認証情報は採取せず、元DBは変更しない。
- 計測とUI driverは終了済み。一時エミュレーターはコンソール終了の認証失敗後、起動引数・ポート・親子関係を確認した今回の本体プロセスだけを終了。関連補助プロセスの終了と専用ADB5038の停止も確認した。通常のエミュレーターを利用可能。

### 現在の実データの照合と実機診断の準備（20:33以降）

- ユーザー指定のPC Downloadsから `OuterTune_29_20260930203310.backup` を読み取った。既存 `physical-heat-names-20260930/audit_backup.py` にPCの選択済みバックアップを読み取る入口だけ追加し、DB以外は展開していない。元ファイルは変更せず、採取先は同ディレクトリーの `OuterTune_29_20260930203310/`。DBは29,650,944 bytes、schema29、integrity_check=ok、SHA-256 `5f9ef38345c605cecf731be6419d0e78d5f8552a36eeb387be155c5224deedea`。
- **曲数の説明を訂正:** ライブラリ登録95曲、dateDownload設定済み95曲。song表817行には未登録722行が含まれる。817をユーザーの保存曲数として扱わない。DAOの定期更新対象queryをこのDBで実行すると、95曲・22アルバム・15アーティストの計132対象。
- 一方、名称キャッシュは5,440対象・24,182行で、22,504行は現在の定期更新対象外。全件の表示JOINは24,182行、原題評価入力は12,506行、公開証拠は約13.8MBを返す。公開証拠に埋め込まれたsource snapshotは計11,915個、固有4,449個で、同一証拠が複数の公開先へ重複する。DB集計・読み取り専用のquery計測は `idle-cause-20260930/current-db-shape.json` に保存。PC上のSQL時間を実機CPU時間に換算しない。
- **高負荷時間との照合:** 保存95曲のinLibrary時刻は18:59～19:18。19:20～19:26に最終更新時刻を持つmetadata_fetchの764行・374対象はすべて現在の定期更新対象外で、761行SUCCESS・3行EMPTY。metadata_nameは1,142行・532対象の更新が残り、現在の定期更新対象に属するものは8行のみ。同時間窓の公開評価は460行。
- 最後の名称観測・取得状態更新は **19:25:19.234 JST**、最後の公開評価時刻は19:25:11.546。実機PID14927の最後のGC記録 **19:25:20.807** の約1.6秒前に取得・観測列も途切れ、次の記録は19:45:58以降。DBの時刻は各行の最終値なので、764を総HTTP呼出し回数としたり完全な時系列履歴としたりしない。それでも、無操作中に保存対象外への補完が続いていたことの直接的なデータ証拠となる。
- **対象を広げるコード:** `MetadataNameRepository.start()` のobserver処理（148–192行）はAPI応答から得た全候補を保存してscheduleする。`metadataNameCandidates()`（1487行以降）は曲をalbum/artistにも展開し、`saveMusicResult()`（642–677行）も取得した関連対象をscheduleする。検索・候補・home/next/related・artistページの一部だけを画面に表示する場合も、observerは応答全体を取り込む。`captureAlbumOriginals()` は保存曲が含まれるなどの対象アルバム全曲へ補完を広げる。
- **負荷を増幅するコード:** 50曲のHTTP batchでも `fetchBatch()`（617–620行）から曲ごとの `saveMusicResult()` transactionを実行し、小さな変更が全名称JOIN・全評価入力・公開証拠処理を起動する。Roomの通知合流はあるため必ず50回とは限らない。`OriginalNamePublication.publicationEvidence()` は同じsourceの証拠を公開先ごとに保持し、公開処理はcache判定前に過去証拠全文をハッシュする。変更件数64の上限は走査量の上限ではない。
- **現時点の判断:** 保存対象外への広い初回補完の残作業と、それによる全件読み直し・証拠処理の増幅が、無操作中の高CPU/GCを起こしたという経路を、コード・現在DB・実機時刻の一致が強く支持する。5,440対象全部の定期network取得や、無限巡回を証明したわけではない。成功TTL、内部取得のobserver抑制、評価keyの終了判定があり、有限の残作業が19:25に収束した状況と整合する。実機CPU約207%の処理別内訳はまだ未採取で、熱全体の寄与率は断定しない。

#### 現在DBを使った追加エミュレーター調査

- 同じdebug APKを一時emulator-5556へ入れ、現在DBのコピーのSHA-256一致を確認。実機と元DBは未変更。`current-run` / `current-ui` / `current-ui-retry` に保存。起動後、固定応答で補完の残作業を処理し、一旦待機へ戻った。
- 11:37:23–11:40:23 UTCのSQL記録では完結6,515記録・計10.704秒。そのうち表示全件JOINが100記録・計4.646秒（最大146ms）で最大項目。評価入力52記録・0.673秒、公開証拠Cursor60記録・0.529秒、原名42記録・0.474秒。詳細は `current-run-summary-114040.json`。CursorWindow再実行や欠損があり、記録数はDAO呼出し数ではない。
- **連続7回は未完走。** 初回はアルバム1の表示後にADB接続の一時エラーでUI driverが終了。同一プロセスでの再試行も検索filterのSongsへ進まず終了した。instrumentationを停止signalで終了し、必要な7検索未達のassertion failure（1件）を記録。これは7操作PASSや実機の自然クラッシュとして扱わない。更新時の全件読み込み負荷は観測できたが、実機と同じ持続時間の再現は未達。
- debugのJava stackを3回取得した時点はDB workerが待機中だった。VMを短時間停止して再開・detach済みであり、これらも実機の高負荷stackの代用にはしない。診断用のprofilerクラッシュがあった前段と区別し、今回はmethod tracingを使用していない。
- driver・instrumentation・monitorを終了し、今回起動したPID27712とそのqemu子プロセスだけを起動引数・親子関係確認後に終了。専用ADB5038も停止。通常のエミュレーターは利用可能。

#### 次の実機検証の提案（まだ実機へインストールしていない）

- ユーザーから「実機で確実に分かるか、必要ならデバッグAPKを提案してほしい」との依頼。再現と採取が重なれば処理別の確度を上げられるが、操作だけで必ず再現するとは約束しない。現在バックアップは補完終了後の状態なので、同じ既閲覧曲だけでは新しい残作業が生じない可能性がある。
- **準備済みAPK:** `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-debug-cause-20260930.apk`。前回生成済みdebugをコピーしたもので、新たな本番修正・ビルドは行っていない。SHA-256 `b2da94d15f1979ca976598025fc052f725d9dc475302a6cbc87bbf0513bb446d`。aaptで `OuterTune Debug` / `com.dd3boh.outertune.debug` / version71 / arm64-v8a / debuggableを確認し、apksigner verify成功。通常版と別パッケージで併存できる。
- 実機で行う案は、別アプリへ今回のDBコピーを用意し、ユーザーが新しい検索結果を含む検索・アルバム表示・保存・DLを数回操作してから手を止める。その前後でCPU/thread・GC・DBの更新と読み込みを記録し、高負荷時だけdebug stackを採取する。stack取得時の短いVM停止は区別する。バックアップに音源cacheは含まれず、通常版のダウンロード音源を別アプリへ移したとは扱わない。
- 監視は既存 `physical-workflow-monitor-20260930.py` を使い、第4引数でdebugパッケージを選べるようにした。通常版のdefaultは維持し、併存時にUIDを部分一致で選ばないよう完全一致へ修正。APK/packageの異なる採取先へのresumeは拒否する。構文確認済み、今回の実機でのdebug採取は未開始。meminfoによるGC・logcat消去・通常版のデータ変更は行わない。

```text
python build/diagnostics/physical-workflow-monitor-20260930.py <今回専用の記録先> 1 b2da94d15f1979ca976598025fc052f725d9dc475302a6cbc87bbf0513bb446d com.dd3boh.outertune.debug
```

### デバッグ版の実機検証（20:51–21:01、終了済み）

- ユーザーの「デバッグ版で検証開始」により、SM-S931Z / RFCY1103M9Hのuser0へ別パッケージ `com.dd3boh.outertune.debug` を新規インストール。通常版はUID10592で存在し、停止・削除・データ変更していない。debugはUID10593、インストール済みAPKのSHA-256は上記b2da94…と一致。
- 20:33バックアップから抽出済みの95曲を登録したDBをdebugへコピーし、コピー後SHA-256も原本と一致。認証情報と音源cacheはコピーしていない。設定はバックアップ中のoobeStatus=6 / preferEnglishOriginal=trueだけを選択してコピーし、実機のja-JPに合わせcontentLanguage=ja / contentCountry=JPを明示。debug側だけautoLocalScanner=falseを指定した。作業用 `/data/local/tmp/debug-cause-*` 2ファイルはコピー確認後に削除。
- **条件差:** 通常版の音源ファイルとMedia3 download indexがdebugにはない。autoLocalScannerを止めてもDownloadUtil生成時の再走査は残り、dateDownloadの古い印が消える可能性がある。inLibrary95を用いた名称更新対象は維持されるが、起動時の書込みはこの再走査も含む。匿名visitor取得による認証コンテキスト更新もあり、本番の19:20区間と同条件・同一時刻の再測定とは扱わない。
- 記録先は `build/diagnostics/physical-debug-cause-20260930-205021/`。既存監視プログラムをdebug指定で20:51:18から起動し、CPU/thread/RSS、温度、GC、UID別logcatを取得。SQLiteTimeは開始前の空値をsetup.jsonへ保存してVERBOSEへ変更。検証終了時は現在値がVERBOSEのときだけ元値へ戻す。起動前のthermal status0、SKIN30.3℃、BAT28.6℃、USB充電中。
- 20:52:15にdebugを明示起動、PID17649。起動後の未操作状態で20:52:30のCPU297.46%を観測し、arch_disk_io4本とDefaultDispatcher、HeapTaskDaemonが稼働。**ユーザーへ操作を渡す前**の20:52:54.168–20:52:56.093にJDIを1回使用。jdbの既定のuncaught Throwable停止を解除し、suspend/where all/resume/exitを単一処理で送り、`All threads resumed` とdetachを確認した。採取区間はCPU比較から除外。method tracing・meminfo・logcat消去は行っていない。
- **実行中の経路を捕捉:** `startup-java-threads.txt` のDefaultDispatcher-worker-1は `HashSet/LinkedHashSet`生成→`distinct`→`OriginalPublicationInputs.requiredRows:40`→`forTarget:47`→`OriginalPublicationPreparer.prepareInternal:112/142`→`MetadataNameRepository.publishCompletedOriginals:371`→`evaluateOriginals:356`。原題公開用の依存集合を組み立てる処理。4本のarch_disk_ioはstack取得の瞬間は待機しており、ずっとSQLを実行していたとはしない。
- 同じ起動区間の完結SQLiteTime記録は計21.885秒。そのうち表示全件JOINが332記録・14.622秒で、実行TID17695/17696/17694/17700はCPUを使っていたarch_disk_io_0/1/2/3と一致。公開Cursorは1.753秒、評価入力0.974秒。SQL記録時間66.8%をCPU比率へ読み替えない。詳細は `analysis-summary-205507.json`。これにより、今回debug起動時の全件読取りと原題公開処理への対応を確認できた。
- 20:53:26、同PIDのCPUは0%へ収束。20:55:10にユーザー操作の準備完了を記録し、新しい曲の検索→アルバム表示→保存→DLを3～5回行い、その後アプリを開いたまま「操作を止めた」と連絡する手順を案内した。以後、ユーザー操作中の画面変更・アプリ再起動・JDI停止は行わない。
- ハートビート `outertune` を今回のdebug package/hashと採取条件へ更新し、2分間隔でACTIVEへ再開。読取位置と通知済み事象はheartbeat-state.jsonへ保存し、意味のある変化だけ報告した。終了指示後は下記の通り停止済み。

#### ユーザー操作区間の観測と終了

- 20:57:13.479–21:01:29.510の約256秒では平均CPU369.55%、最大518.51%（1core=100%）。同区間のarch_disk_io4本合計の時間加重平均は123.65%、HeapTaskDaemon26.64%、main39.04%。GC完了ログ293件。初回の294は21:00:24のProfileSaverによるGC待ちログ1件を含んでいたため除外した。詳細は `operation-cpu-final.json`。RSSをJavaヒープ量として扱わず、GC後の使用量も割当ての瞬間最大とはしない。
- 起動後に一旦待機へ戻った時点のSKIN30.5℃から、操作区間の最大SKIN40.7℃ / BAT37.7℃へ上昇。21:00:02にthermal status0→1を記録。最後の温度採取21:01:19はSKIN39.7℃ / BAT37.6℃ / status1。ユーザーの終了時体感は「ほんのり熱い」。続ければさらに上がるという予想はユーザーの見立てであり、未測定の将来温度として扱う。
- **操作中も同じ全件読取りを直接確認:** 20:57:58.244–21:00:17.804の完結SQLiteTime操作記録94,835件・累積63.538秒のうち、metadataDisplayNamesの全件JOINが817記録・48.527秒（記録時間の76.4%）。実行TIDは今回の高CPUな4本のarch_disk_ioに一致。次点は評価全件196記録・3.127秒、公開全件180記録・2.636秒、source別検索207記録・1.565秒、原題source全件123記録・1.558秒。`analysis-user-sql-next-72217692.json` に保存。前半は `analysis-user-sql-2057-27978080.json` と分離して重複集計しない。
- SQLiteTimeの101,009 / 274,563は複数行SQLを含むログ行数であり、SQL操作数でもDAO呼出し回数でもない。上記操作記録もCursorWindow再充填を含み、並行SQLの経過時間合計をCPU比率へ換算できない。後半区間では長いSQL408件で所要時間の末尾が欠けており、累積値は取得できた範囲に限定する。
- **診断条件の限界:** debug実行と大量の詳細ログ（同139.56秒区間に約44.24MB）の上乗せ負荷は分離していない。今回のCPU値をそのまま通常版のCPU・発熱へ置き換えない。ただし通常版で既に採れているDB thread/GC高負荷、保存対象外の補完が19:25まで続いたDB時刻、今回の全件SQLと原題公開処理の実行stackは同じ増幅経路を示す。操作中に追加JDI停止は行っていない。
- 今回の範囲では対象debugのOOM・FATAL EXCEPTION・ANR・自然再起動は未検出。発熱と反復GCは観測したが、OOMを再現したとは扱わない。ユーザーは操作終了後の待機採取ではなく検証終了を指示したため、今回の操作後に負荷が何分続くかは未測定。
- **終了:** ユーザーの「もう十分」に従い21:01:32.142にstop-requestedを作成。21:01:32.189のSTOPPED、120サンプル、採取プロセスexit0を確認。ハートビートをPAUSEDへ変更。21:02:10にSQLiteTimeの現在値VERBOSEを元の空値へ復元し、再読取り一致を確認。終了後の端末への追加操作・JDI・再現試験は行っていない。通常版の状態も変更していない。
- 証拠は採取先の `end-summary.json` / `diagnostic-cleanup.json` / `heartbeat-state.json` と解析JSONへ保存。**本番コードの追加修正は未実施。** 確認できた負荷源は、広がった名称補完が小刻みにDBを更新し、そのたびに表示候補全件の読取り・原題公開用の依存集合生成・証拠読取りを反復する経路。CPUの全内訳・実機発熱への寄与率・無限ループまでは断定しない。
- 終了後の全操作窓集計は `analysis-final-user-operations.json`。完結SQL操作記録152,120件・累積102.096秒のうち、表示全件JOINは1,380記録・77.152秒（記録SQL時間の75.6%）、4本のarch_disk_io各18.740–19.731秒。評価全件340記録・5.059秒、公開全件330記録・4.407秒、原題全件294記録・3.493秒。操作窓の詳細ログ約72.5MBによる上乗せ負荷は未分離であり、SQL時間をCPU比率へ変換しない。

### 実機診断後の負荷改修（実装・エミュレーター検証完了）

- ユーザーの「改修方針を定め，修正して」に基づく範囲。実機監視は終了済みで、追加の実機操作は行わない。
- 方針: (1) observerは応答の名称を保存するが、保存・再生・前景アルバムの関心がない検索/推薦候補から補完を起動しない。明示した対象から得る直接のartist/album header、前景albumの収録曲補完は維持。(2) 表示名の初回/設定変更だけ全件読み込み、以後はtransaction内の対象別revisionと変更IDで差分読取り。(3) 同一queue応答を一括commitし、途中状態での再読取りを減らす。(4) 原題公開の依存集合を同一snapshot内で再利用する。
- DB29→30に表示revision表を追加しAutoMigrationで移行。95保存曲・音源・原題証拠は削除しない。初回journalが空でも既存名称を全件表示し、以後の削除/再追加をtombstoneで追跡する。
- 合格条件: 大量の無関係名称cacheがある状態で1対象変更に全件JOINを反復しない。原題の公開/保留/撤回、locale設定変更、削除再追加、保存対象/前景対象の補完を維持。現在の95曲DBコピーで移行と操作を検証し、既存の検索→album→保存→DL再現プログラムを一時emulator-5556で完走させる。CPU/GCの比較は同一条件の測定だけを用い、エミュレーターの温度を実機改善の証明にしない。
- 実装と下記の回帰・連続操作検証を完了。実機での温度改善は未検証。

#### 改修後の回帰検証

- JVM近傍テスト130件PASS（初回テスト本体計1.101秒）。observer/前景化guardを含む最終コードでも配布ビルドと併せて130件を再実行し、失敗0・計1.197秒。`unit-results-final.json`。
- 最終本番debug SHA-256 `6c80e4f3047e8be6e45f00a653c1a1603bec6efc1894e0ef4d719175be65005e`。実機には入れていない。`verified-main-source.json`で新規の主要本番ファイルとschema30のhashを保存。
- Android112件PASS、79.541秒。metadata表示差分、24,000候補からの1対象更新、証拠だけの更新、原子的公開、削除再追加、言語切替の破棄frame再表示、保存/前景scope、原題の公開保留/撤回、album provider/playlist identity、manual名保護、起動表示を含む。ログ `metadata-heat-fix-20260930/android-regressions.txt`。
- 20:33の実DB（95保存曲、metadata_name24,182行）のコピーをAutoMigration29→30で開き、初回に全件JOIN1回、1対象変更後はその対象8行のJOIN1回。変更後読取り5ms、初回530msは移行も含むため直接の速度比にはしない。保存95曲維持と入力DBのSHA-256不変をassert。`display-measurement.txt`。証拠と他targetの名称を切り捨てて速くしたわけではない。
- 独立レビューで発見したraceも修正: locale A→B→Aをconflateした際、Mainで捨てた表示frameを消費済みにせず次読取りを無効化する。明示保存rootの直接album/artistは親songの成功TTL中でも期限切れ失敗を再試行できる。album応答が前景通知より先でも直近cacheのtrack interestを復元する。saved ID集合が同じままinLibrary等の役割が変わる通知は捨てない。
- UI連続操作の初回 `current-run/current-ui` はエミュレーターのネット接続が未検証状態になり、正しくlocal検索へfallbackしたため検索fixtureへ到達せず未完走。driver1failureと停止後instrumentationの検索件数不足1failureを記録。OOM/自然crashとしてもPASSとしても数えない。
- `WorkflowReplayDeviceTest`内だけ、ローカルHTTP/audio fixtureに合わせActivityのnetwork状態を固定し、キャッシュから移すfixture DLの外部network要件を解除。終了時Activityを閉じ、DL要件を復元する。製品のNetworkConnectivityObserverやTLSは未変更。自動操作は既存 `workflow_replay.py`を再利用し、ADB一時エラー時は読取りだけ再試行、Enter未反映時は観測済みの同名候補をsubmit。meminfo採取を除去しGC評価への干渉を避けた。
- 現在の再実行先 `fixed-run/fixed-ui` は同じ元DBから再初期化した使い捨てコピー。固定通信/音源によるCPU・GCを実機の温度改善やrelease実通信の証明とは扱わない。

#### 固定応答UIの完走

- `fixed-ui/events.jsonl`：検索→album→保存→DL→再生を7回完走（361.9秒）。各回のMediaSessionが該当曲をPLAYINGで保持することをdriverがassert。固定応答の実instrumentationも `OK (1 test)`、584.142秒。7曲が保存済み、7音源がDL完了・cache全量保持。
- 原本と同一SHA-256のDBコピーから開始。終了後に使い捨てdebugを停止してDB/WALをPCへコピーし、SQLite integrity_check=ok、schema30、保存102件=元95件＋今回7件、元95曲の全ID保持、fixture DL7件を確認。総song901件は閲覧album全曲を含み、保存件数と区別。`fixed-final-db/validation.json`。
- 既存 `monitor.py` と `metrics.py`を再利用。対象PID10039、操作中CPU平均71.2%（1core=100%）、Javaヒープ観測最大78.6MiB。操作中のruntime GC増分168（採取355.3秒）、GC時間増分25,173ms。RSSではない。最終再生120秒が終了した後（操作完了+120秒以降）の77.2秒ではCPU平均0.1%へ収束。詳細 `fixed-ui/metrics.json`。meminfoや停止stack採取なし。
- 通信/音源/接続判定を固定したARM変換エミュレーターでの結果。修正前の空DB試験や実機SQLiteTime VERBOSE検証とは条件が異なるので、CPU/GCの改善率や実機温度の低下率を算出しない。構造的な全件読取り削減と、現在DBでの操作完走・待機収束を合格根拠とする。
- Billie Jean 4:55の採取済み応答から正規音源ID・album・295秒・保存/playlist/DL参照を保持し、DB再openと取得失敗後も維持する回帰1件PASS（2.799秒、`billie-regression.txt`）。これは今回の自動fixture確認であり、実機UIの再確認ではない。

#### 最終確認・配布成果物

- 独立検算 `workflow-validation.json`：PID10039の待機区間（12:49:19.571 UTC以降、実測77.173秒）はCPU平均0.1166%、runtime GC187→187、GC時間27,975→27,975ms、GC完了ログ0件。対象PIDのOOM・Java/native fatal・ANR記録なし。12:50:42.545 UTCのプロセス終了はテスト終了要求後の後片付けとして分離。
- 最終成功テスト計244件（JVM130、Android回帰112、連続UI1、Billie Jean回帰1）。前述の接続判定による初回harness失敗は成功に含めない。検索→album→保存→DL→再生7回と元の95曲保持を確認した。
- `:app:assembleCoreRelease`成功（最終JVM確認と合わせ2分59秒）。成果物は `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-metadata-heatfix-20260930.apk`、9,218,445 bytes、SHA-256 **`982c0e58cfd2bf0ca4af88d7a9bb1a3b114ed35c99505d4292ee720bf40a3b54`**。
- package `com.dd3boh.outertune`、versionCode71、versionName0.10.2-b1、arm64-v8aのみ、非debuggable。apksigner verify成功。署名証明書SHA-256 `45a8c1d0b4e914882ff085b18098cd917679bafedda7debd8a3c48b01727026d` は前回通常版と一致し、アンインストールせず更新可能。`release-apk.json` / `release-signature.txt` / `release-badging.txt`。
- 同じ使い捨てエミュレーターへ通常版を上書きインストールしてオフライン起動（PID16403、cold start1,269ms）を確認。保存済みNevermindの13曲・各曲名/時間・Pollyの選択表示をXMLと画像で確認。今回95曲のdebug DB検証とは別の、AVDにあった通常版の保存データ。ネットワークを切ったためhomeの取得エラーは想定内で、再試行せず保存画面の確認に限定。対象PIDログにOOM/fatal/ANRなし。`release-start.txt` / `release-home.*` / `release-library.*` / `release-album.*` / `release-smoke-logcat.txt`。releaseの実通信や実機の温度低下を確認したとは扱わない。
- 実機の追加操作・インストールは行っていない。監視プロセス終了を確認し、22:00:51 JST、起動引数・親子関係・生成時刻を照合した今回の一時エミュレーターPID55504/37504とその補助プロセスのみを停止、残存なしと専用ADB5038の停止を確認。通常のエミュレーターを利用可能。`emulator-cleanup.json`。
- 配布後、ユーザーから「修正を確認しました」とコミットの依頼を受領。これはユーザーの確認報告であり、実機の温度・CPUを再測定した結果ではない。ここまでの本番修正・テスト・検証記録をまとめてコミットする。続いて相談されたアルバム遷移時のちらつきと動画由来アーティストの扱いは別の調査として扱う。
