# 2026-09-19: 反復再生後にライブラリ・再生が進まなくなる

## 報告・合格条件

- 色々な曲を繰り返し再生していると、ライブラリ読み込み・曲再生などができなくなる。操作順は未特定。
- ユーザー追記: 完全終了・再起動、スマホ設定の「メモリ解放」で復旧した。ディスクキャッシュ削除による復旧とは確認できていない。
- DB読み込み・更新と再生が相互待ちで止まらず、連続操作で保存ジョブが際限なく積み上がらないこと。データ削除を回避策にしない。
- 基点: `14e73317` + アーティスト紐付けUI/アルバム先頭曲重複の未コミット修正。以前の差分は保持する。
- 過去のURL失効・キャッシュ途中欠落対策は `2026-09-12-playback-recovery.md` を参照。今回の報告と同一原因と決めつけない。

## 初期監査

- `SongsDao.incrementPlayCount` は同期Room transaction中に `runBlocking { getPlayCountByMonth(...).first() }` で非同期Flowを待つ。`onPlaybackStatsReady` はRoom queryExecutor上から呼ぶため、DB読み取り用executorが自身の終了を待つ候補。決定的な再現を確認してから修正する。
- `QueueBoard` の保存処理は「5秒後に最新だけ保存」という意図に反し、DEFAULT開始のJobをすべて即実行する。別scopeのdispatcherとblocking delay、共有PriorityQueueも残る。連続選曲・クレジット更新でDB仕事が積み上がる実在の制御不具合。
- `QueueDao.updateAllQueues` のtransaction内で別scopeを起動しており、呼出しの完了と保存完了が一致しない。
- `HybridCacheDataSinkFactory` は同じsinkのcache有効→無効の再利用で、閉じたdelegateを使い回す。今回の全停止とは未確定だが、実cacheで回帰確認する。
- URLキャッシュは64件上限、cacheのspan競合は待機せずupstreamへ進む構成。通常の反復再生からcache-lock全停止へ至る根拠は未発見。
- Repositoryのプロセス内mapや補完要求には追加の負荷候補がある。現時点で本症状との因果関係は未確定で、人物仕様やTTLの広範な変更は行わない。

## 結果

### 実機で原因を確認

- ユーザーがオンライン紐づけ・アーティスト画面閲覧後に再現し、USB接続したSM-S931Zで読取診断。アプリの再起動・設定変更・データ変更・APK入替えは行っていない。
- 動作中APKのSHA-256は `8a6214d4852ae7dd2b6ee8d9e00fb4f1d82ea91d73e20d58ab459af94503ed92`。前の `OuterTune-artist-link-ui-20260919-arm64.apk` と一致。
- `song.db` の接続待ちが22:34から4分以上継続。`arch_disk_io_0/1/3` と `DefaultDispatcher-worker-5` が書込み接続待ち。OOM記録なし、取得時Java heap使用量は約39 MB。
- Android標準bugreportから対象アプリのthread dumpを抽出し、上記APKと一致するR8 mappingで難読化解除。`arch_disk_io_2` は `MusicService.onPlaybackStatsReady:1131 → MusicDatabase.query:67 → SongsDao.incrementPlayCount:533 → runBlocking` で停止。外側Room transactionがDB接続を保持したまま、同じexecutorで処理すべきFlowを待っていた。
- 他の3本のDB query workerはtransaction開始待ち。再生Loaderも `resolveStreamDataSpec` 内の形式情報DB保存待ち。main threadは通常のイベント待ちで、cache lockは停止原因ではない。
- 原因: 再生統計保存の循環待ちによりDB workerが全部埋まり、ライブラリ・再生のDB処理が停止した。人物ページ等のDB更新が重なると発生し得る。キャッシュ破損と断定しない。
- 診断原本は `build/diagnostics/playback-stall-20260919/device` のみに保存。bugreport全体や私的なログはgit管理・配布APKに含めない。診断完了後、実機の状態維持は不要とユーザーへ連絡済み。

### 修正と回帰

- 再生回数更新を同じtransaction接続の `INSERT IGNORE` + `UPDATE` に変更。Flow読込とrunBlockingを撤去し、月次の既存回数・削除済み曲の保護を維持。
- 未修正版の `PlaybackHistoryLivenessTest` は3件すべて期待どおり停止を再現（初回記録、24callback連続、4worker同時）。callback完了0件、並行ライブラリ読込未完了のまま5秒で失敗し、stackが実機と同じ経路を示した。テスト本体21.245秒。
- 再生キューの保存は単一writerに集約し、保持する要求を最新snapshotと現在のキューごとの曲変更印だけに制限。位置・並替えはheaderのみ、曲の変更時だけ独立copyを取る。削除・並替え・曲変更を同じ保存順序に統一。
- キュー終了時はmain上でsnapshotを取り、保存worker終了後に最終状態を一つのtransactionで保存。`updateAllQueues` はtransaction外へ仕事を逃がす別scopeを撤去。
- 再読み込みも旧worker停止→最新snapshot保存→DB読込→Mainで新board採用の順にする。IOだけの共有Deferredを終了時にjoinし、Main継続処理を待つ循環待ちを作らない。初期読込前・永続化OFFで起動した空boardを、終了時に保存済みキューへ上書きしない。
- ローカル削除前に直近のオンライン変更も保存し、削除中はキュー再読込を待機。取消時も後処理で待機を解放する。削除前に始まった非同期再生要求は世代の変化で破棄し、削除済みローカル曲をsnapshotから再挿入しない。曲除外後の再生位置・シャッフル順も補正する。
- 空キューのheaderと、同じ曲一覧を再シャッフルした場合の曲順も保存する。レビュー中に発見したこれらの保存漏れは同じ保存経路の回帰として修正。
- HybridCacheDataSinkの再open/close時に古いdelegateを保持しないよう修正。これは今回の実機停止とは独立の、再利用時に確認した不具合。
- ユーザー追加依頼の履歴・統計も対象とする。履歴は `incrementPlayCount` の直後に保存されるため、同じ停止で更新できない。最終版で回数・履歴・統計まで検証する。

### 履歴・統計の追加調査

- 履歴・曲詳細の再生回数が止まる直接原因は上記の循環待ち。停止中に保存されなかった過去の再生は、修正後に自動復元できるとは扱わない。
- 曲ランキングは `IN (並べ替えたsubquery)` の外側に順序指定がなく、再生時間順が保証されなかった。eventを集約して外側で順序・limit・offsetを適用する。
- アーティストは月次回数を集計していたため、1週間指定でも同月の古い再生が混ざった。eventの実日時で指定期間を絞り、既存のcanonical artist view経由で再生回数を集約する。複数の紐づけ元・オンラインクレジットが同じ人物を指しても1回とする。
- 統計画面だけのYouTubeアーティスト限定フィルタを取り除き、実際に聴いたフォルダ由来アーティストも表示する。ホームのオンライン推薦用フィルタは維持。
- 統計から曲を選んだとき、ListQueueの開始位置が未指定で常に先頭曲だった。選択した曲の位置を渡す。
- 再生履歴の記録閾値・一時停止設定は維持。曲の長さが未知のメタデータに対する記録条件は別の未確認条件として残し、今回の実機原因と混同しない。

## 最終版の検証

- 単体テスト29件 PASS（本体合計0.226秒）: QueueSaveScheduler 9、QueueRestore 5、PlayerExt 4、PlaybackUrlCache 7、PlaybackFormats 4。1000回の保存要求でも最新状態だけを保持し、保存中の追加要求・失敗後の再試行・終了待ち・独立snapshotを検証。
- Android DB/cacheテスト34件 PASS（本体4.890秒）: PlaybackHistoryLiveness 3、QueuePersistenceDatabase 4、StatsDatabase 4、HybridCacheDataSink 3、PlaybackCacheDataSource 4、LocalArtistLinkDatabase 8、AlbumTrackMembership 8。停止を再現した3件がすべて解消し、月次既存回数+24回の更新、4worker同時更新、並行ライブラリ読み込みも完了。キュー削除・scan削除後の保存・紐づけ・既存アルバム修正の回帰も確認。
- 実MusicService連続再生テスト1件 PASS（15.752秒）: 3秒WAVをA/B/A/Aの順に実デコード・再生し、履歴4件、月次/曲詳細用回数A3・B1、曲/アルバム統計、ライブラリ応答とサービス終了を確認。
- 実MusicServiceキュー境界テスト1件 PASS（3.931秒）: 独立DBで、5秒保存待ち前の再読込、ローカル削除中に待機していた古い再生応答の破棄、直前変更の終了時保存、DB再openを確認。
- 合計65件 PASS。AndroidはPixel_9_API_35 / emulator-5556（API35、arm64翻訳実行）、実機へのAPKインストール・データ変更はなし。
- 最終mainソースのunit/debug/AndroidTest/releaseビルドは6分4.710秒。テスト終了処理のfixtureだけを修正したAndroidTest追加ビルドは25.660秒。本体ソースはその間変更なし。
- 途中のビルド失敗（DAO戻り値変更に伴う委譲クラスの再コンパイル）と、実再生テストの旧cleanup失敗は最終PASSに含めない。旧cleanupは通知用自己接続が残り、終了を待ち切れなかった。fixture内でMediaSessionをreleaseしてからAndroidの実onDestroyを待つよう修正し、両テストが完了した。

### 配布APKの画面・再生確認

- 最終coreReleaseを同じエミュレータへインストール。独立生成したschema28 DBと8秒WAV2曲で、通信を使わず確認。DBのみの検証backupには設定やユーザーDBのコピーを含めない。
- 履歴の初期4件（A3/B1）、統計の曲順A→B、フォルダ由来アーティストとアルバムの表示を確認。
- 統計の2曲目Bを選択して、Bが再生されることを画面で確認。8秒完走後、プレイヤーを閉じてセッションを終了すると履歴が5件（A3/B2）に増え、曲詳細の再生回数も1→2になった。記録タイミングは従来のPlaybackStatsListenerのセッション終了時を維持。
- アプリのプロセス終了・再起動後も履歴5件が残り、ライブラリの2曲が読み込めることを確認。提供backupのSHA-256は `5cb75e95b76c4bda1916a1d611d0985201e14d284b8e40fc71ba276f35c057c8` のままで変更なし。
- 画面証跡（すべて架空の検証データ）: [初期履歴](../images/2026-09-19-playback-library-stall/history-before.png)、[統計](../images/2026-09-19-playback-library-stall/statistics.png)、[2曲目の再生](../images/2026-09-19-playback-library-stall/statistics-selected-b.png)、[再生後の履歴](../images/2026-09-19-playback-library-stall/history-after.png)、[再生回数2](../images/2026-09-19-playback-library-stall/play-count-after.png)。
- releaseのYouTube実通信は既知の検証PC証明書制約に従い再試行していない。修正APKの実機での長時間利用は未確認。

### 成果物

- `build/distributions/OuterTune-playback-history-20260919-arm64.apk`（9,136,525 bytes、coreRelease / arm64-v8aのみ、署名検証PASS）。
- APK SHA-256: `07d985230991f8487c9c8094c897dd598ace9ca18f7ac25a563f723ac9058eef`。
- 同名prefixのR8 mappingも保全。SHA-256: `5b14e146bb6f13f0b45f12dc87e006480b14d5885d1e5170415149aa845fe8d4`。
- 以前のアーティスト紐づけUI・アルバム曲一覧修正も含む。今回の停止/履歴修正による追加schema変更はなし（アルバム修正の27→28維持）。
- `git diff --check` PASS。原本診断・合成DB・テストログ・APK/mappingはbuild配下の無視対象に保持。追跡対象の画像は上記合成データのみ。
