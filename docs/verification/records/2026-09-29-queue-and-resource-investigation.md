# 2026-09-29: キュー表示・null通知と操作中の端末負荷の調査

## 現象・範囲・合格条件

- ユーザーの質問: キューの確認場所が分からず、曲をスワイプすると「nullを追加しました」と出る。音楽プレイヤーとして端末の計算リソースを多く使っているように見える。
- 追加回答: 負荷を感じるのは「アプリを表示して曲やアルバムを操作しているとき」。
- 今回は現在のキュー導線と通知の原因、現在残る負荷要因を調査する。修正・APK生成は調査結果と区別する。
- 合格条件: キューの開き方を具体的に説明する。null表示と実際の追加処理を分けて原因を示す。負荷についてコード上の事実・測定値・実機で未確認の事項を区別する。
- 開始時ソース: `74a37dc3`、追跡ファイルの未コミット差分なし。前回の画像・キュー保存軽量化を含む。
- 開始時はADB接続端末なし。実機のCPU、メモリ、電池消費は未測定。

## 測定環境

- 静的調査をキュー、再生・UI、名称取得の3経路に分けて実施。
- 既存coreRelease / arm64-v8a APK (71) を対象に、一時エミュレータ Pixel_9_API_35 / emulator-5556 を `-read-only -no-snapshot-save -no-window -no-audio` で起動。元AVDへ変更を保存しない。
- APK SHA-256: `5d6d0b5d134ebcf0bc99d37e195082408b8efa574bd589dd1c271bf6340f8b73`。
- 診断出力: 無視対象 `build/diagnostics/queue-resource-20260929/`。
- エミュレータはx86_64上のarm64変換であり、そのCPU・描画時間をユーザー実機の値として扱わない。既知のrelease通信証明書問題は再調査しない。

## キュー導線と通知の原因

- ミニプレイヤーをタップ → 全画面プレイヤー下端の `⌃` をタップ（または下端を上へドラッグ）。独立したキュータブはない。展開後の下部のキュー名から保存した複数キューも開ける。
- 根拠: `ui/component/BottomSheet.kt:155`、`ui/player/Player.kt:250`、`ui/player/Queue.kt:196-202,929-960`。以下のコードパスは `app/src/main/java/com/dd3boh/outertune/` からの相対パス。
- `ui/component/AnchorDraggable.kt:87,101` は Media3 の nullable な `item.mediaMetadata.displayTitle` を通知に渡す。一方 `extensions/MediaItemExt.kt:31,48,65` は現在の表示曲名を `.setTitle(...)` に設定し、`displayTitle` は未設定。同名のアプリ独自拡張とMedia3フィールドの取り違え。
- `798822afbbba291ca954c8ba6a58601a99ad1dfa` で2箇所の `.title` が `.displayTitle` に変わっていた。最小修正案はその2箇所を `.title` に戻し、未使用importを削除すること。変換時に表示設定を反映したtitleが渡されるため、原表記優先設定は維持できる。
- **再現確認 / 配布APK:** ローカルの「遺サレタ場所」をスワイプすると `"null"をキューの最後に追加しました`。キューを開くと末尾に正しい曲名があり、13曲→14曲。そこから曲を選び `PLAYING / error=null` と再生位置の進行を確認。通知の誤表示と曲追加は別経路である。
- 証跡: `swipe-notice.png/.xml`、`player-queue-entry.png/.xml`、`queue-open.png/.xml`、`playback-before.txt`。
- **別の未実行境界:** `QueueBoard.kt:324-327` は現在のキューなしで末尾追加するとreturnするが、呼出元は成功通知を出す。次に追加は `MusicService.kt:606-624` で未初期化キューを作る。今回再現した既存キューへの追加とは分ける。

## 現行コードに残る負荷候補

### 測定で絞り込んだ優先候補: 覆われた曲一覧の再生アニメーション

`MainActivity.kt:989-998` は `navHost()` の上へプレイヤーを重ね、背面画面をcompositionに残す。背面の現在曲行が残ると、`AlbumScreen.kt:486-494` → `SongItems.kt:135-143` → `Items.kt:766-777` → `PlayingIndicator.kt:125-129` の再生インジケータも存在する。`PlayingIndicator.kt:72-83,99-100` は3本の棒のアニメーションを毎フレーム描画する。上にプレイヤーが重なったことを条件に停止しない。

同じローカル曲と全画面プレイヤーで、背面をアルバム詳細→ホーム→アルバム詳細へ変えた比較で、描画回数が毎秒約60→2→60回、CPU平均71.24%→13.05%→64.35%を観測した。再測定前は先頭近くへシークしたが、同じ曲・同じアートワーク・同じプロセス。短い曲名・人物名で長文marqueeが原因とも考えにくい。再生/停止比較だけでなく、プレイヤーを表示したまま背面を変えた結果がコードの条件と一致する。

**結論:** 不要な描画を抑える優先箇所として根拠が強い。見えている曲行のアニメーションは保ち、プレイヤーに覆われた画面・非表示画面では止める修正を提案する。traceによる各処理の寄与率や修正後の改善幅は未測定で、唯一の原因とまでは断定しない。

### その他の静的調査結果

1. **画像なしのローカル曲の代替画像が大きい。** `utils/CoilBitmapLoader.kt:100-107,139-149` は2000×2000、ARGB_8888のBitmapを作ってから要求サイズへ縮小する。約15.3MiB/要求の一時割当は寸法から計算できる。キャッシュミスごとの経路であり、全曲・全描画で毎回作るという意味ではない。今回の実機負荷への寄与や発生回数は未測定。
2. **音声再生とは別にメタデータの定期集計がある。** `App.kt:89-93` がrepositoryを起動し、`AlbumMetadataRepository.kt:82-101` と `ArtistImageRepository.kt:57-70` が毎分一覧を走査。`MetadataNameRepository.kt:250-255` は5分ごとに名称更新と評価を要求。画面のライフサイクルから独立したscope。各TTLに従うため毎回全曲へ通信するわけではない。
3. **名称更新時に全体の再読込・集約が残る。** `MetadataNameRepository.kt:213-220,278-354,451-464` は全名称のprojection/map、原題の有効性確認、証拠JSON・公開結果などを組み立てる。再判定対象0件でも公開準備は行う。`MetadataNamesDao.kt:94-106`。9/27に全履歴の定期通信、無関係な変更での全件モデル再判定は改善済みであり、旧問題を現行の事実として扱わない。
4. **曲情報の保持量は閲覧に応じて増える。** `ArtistCreditRepository.kt:70-77,103-128,158-179` のmap群、名称観測キャッシュには全体のLRU上限を確認できない。一部responseキャッシュには上限・期限がある。長期利用の増加候補であり、時間だけで増えるメモリリークとは未確認。
5. **停止中のUIポーリングが残る。** `ui/player/Player.kt:764-772`、`MiniPlayer.kt:102-110` はSTATE_READYで500ms間隔。一時停止も含む。`ui/component/Lyrics.kt:195-218` は停止中もdelay後continue。歌詞設定は125/33/16ms (`constants/Settings.kt:268-272`)。値が同じなら毎回画面を描き直すわけではなく、これだけで大きなCPU消費とは言えない。

画像キャッシュは `App.kt:124-127` でCoilの `maxSizePercent(context, 0.3)` により上限を設定している。端末の物理RAMの30%を占有するという意味ではない。RAMには意図した再利用用キャッシュも含まれる。名称の「原表記優先」OFFは表示選択を変え、取得・判定を止めないため、軽量化設定として案内しない。

## 実測手順・制約

- 一時AVDだけに既存配布APKを更新インストール。debugはforce-stop、Wi-Fi/モバイル通信を無効化してローカル操作を測定。
- 既存の音源調査inventoryから異なる表紙ごとに1曲、計34曲 / 183,305,802 bytes を一時AVDへコピー。通常UIのスキャンを使い、保存済みオンライン分を含む33アルバムの一覧を使用。元音楽を変更しない。
- `measure.py` は `adb shell top -b -d 1 -n N -p PID -o PID,%CPU,RES,ARGS` の初回値を捨て、20または25個の1秒区間値を取得。CPUは対象アプリプロセス、100%=1コア、エミュレータは4コア (400%=全体)。端末全体や他プロセスのオーディオ処理を含まない。
- `dumpsys meminfo` の前後値と `dumpsys gfxinfo ... reset` / `framestats` を保存。PSSは測定終了後の値であり平均・ピークではない。指標の定義は [Android公式dumpsys資料](https://developer.android.com/tools/dumpsys?hl=ja) を参照。
- スクロールは25秒区間の開始1.5秒後から16回。x=540、y=1750↔700、450ms、4回ごとに方向を反転し、間隔250ms。操作時刻をJSONへ保存。残りの約10秒には停止後の状態も含む。
- `local-library-scroll-repeat` は終端に次画面へのタップが重なったため、比較用の正式な値から除外。ログを保持し、同条件の確認を別ラベルで行う。
- この短時間観測で1分/5分周期の全負荷、オンライン初回取得、数千曲ライブラリ、長時間のRAM蓄積、実機の電力消費は評価できない。旧APKとの比較もしていない。

## 測定結果

| 条件・ラベル | CPU平均 / 最大 (1コア=100%) | 終了後PSS | 描画数 / 区間 |
| --- | --- | --- | --- |
| 保存済み1アルバム、未再生 (`initial-library-idle`) | 0.00% / 0% | 65.93MiB | 0 / 20秒 |
| 33アルバム一覧、操作なし (`local-library-idle`) | 3.10% / 46% | 129.12MiB | 0 / 20秒 |
| 同一覧を16回スクロール (`local-library-scroll-first`) | 38.16% / 91% | 113.13MiB | 718 / 25秒 |
| 後で同じ16回の操作を再確認 (`local-library-scroll-recheck`) | 31.72% / 70% | 128.99MiB | 763 / 25秒 |
| アルバム詳細上の全画面プレイヤーで再生 (`player-visible-playing`) | 71.24% / 77% | 120.74MiB | 1,488 / 25秒 |
| 同画面で一時停止 (`player-visible-paused`) | 0.28% / 1% | 115.32MiB | 0 / 25秒 |
| 同じ曲を画面消灯で再生 (`player-screen-off-playing`) | 10.60% / 12% | 124.64MiB | 0 / 25秒 |
| ホーム上の全画面プレイヤーで再生 (`player-over-home-playing`) | 13.05% / 16% | 123.64MiB | 41 / 20秒 |
| アルバム詳細へ戻して再生 (`player-over-album-repeat-playing`) | 64.35% / 72% | 126.88MiB | 1,185 / 20秒 |

- 全有効測定はPID=4166、期待した20/25サンプルを取得。画面消灯再生は `screen-off-playback-before/after.txt` でPLAYING・同じ曲・約27.6秒の位置進行を確認。再確認時も `playback-repeat-after.txt` のPLAYING・error=nullを確認。
- CPU区間とgfxinfo区間はmeminfo取得分だけ僅かに異なる。スクロールの平均には停止後も含まれる。スクロール初回は操作中の1秒値がおおむね59～91%、操作終了後は0%へ戻った。
- PSSの上下はキャッシュ、GC、共有ページ等を含む単発値。今回の結果からメモリリークや長時間の正常性を判定しない。1アルバムと33アルバムは別データ量であり、改善前後の比較ではない。
- フレーム締切超過は初回スクロール35/718 (4.87%)。エミュレータの値であり、実機の体感判定にはしない。描画の少ない状態のjank率は解釈が不安定なので比較指標として採用しない。

## 調査結果と終了状態

- キューの入口、null通知、正しい曲の追加・再生を配布APKで確認。通知修正は未実装。
- 前景操作に関連する不要な描画の有力候補を、コードと条件を戻す比較で確認。改善実装前なので「性能改善済み」とはしない。
- 改修優先順位: 通知の参照先修正、全画面プレイヤーの背面に隠れた再生アニメーション停止。代替画像・名称集計は別の測定・修正候補として扱う。
- 調査のみ。アプリのソース・設定・DB schemaは未変更。Gradle、新規APK生成、commit、pushは実行していない。追加した追跡候補は本記録のみ。
- すべての採取が終了した後、releaseをforce-stopし、一時エミュレータを `emu kill` で終了。元AVD、元音楽、実機のアプリ・データは変更していない。

## 承認後の改修 (2026-09-29)

ユーザーから「改修に入ってください」と指示あり。上記の調査時点と区別する。

- 対象は通知の曲名参照と、覆われた曲一覧の再生アニメーション停止。代替画像・名称repository・空キュー時の末尾追加は今回の差分に含めない。
- 合格条件: 次に追加／末尾追加の通知が曲名を表示し、キューの追加・再生を維持する。全画面プレイヤーに覆われた一覧では描画を止め、戻った一覧・表示中のキューではアニメーションが動く。画面のライフサイクルが非表示状態なら両アニメーション実装を停止し、STARTEDへ戻れば再開する。
- `AnchorDraggable.kt`: Media3の設定済みtitleを通知に使用。追加処理は維持。
- `MainActivity.kt`: phoneのNavHostに限定して、全画面プレイヤーが実際に存在し、完全に展開されている場合にアニメーション許可をfalseにする。部分展開、常設プレイヤーと一覧が並ぶtablet、プレイヤー内のキューは別スコープとして表示を維持。
- `PlayingIndicatorAnimation.kt` / `PlayingIndicator.kt`: Composeの表示許可とライフサイクルSTARTED以上の両方を条件に、従来の3本の棒と検索画面の共有フレーム時計を動かす。非表示時はループをキャンセルする。一時停止時に同じ高さへ繰り返し動かす旧ループも止める。visible時の棒の動き方や音声再生は変更しない。
- 端末回帰テストと同条件のrelease実測を実施してから、最終結果を追記する。DB/schema変更なし。

### 最終差分のビルド・回帰テスト

対象は `74a37dc3e1437ae6da9a254ec3a0491a6fd19055` と上記4ソース、新規 `PlayingIndicatorVisibilityTest.kt` の差分。

| 確認 | 結果 |
| --- | --- |
| `:app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease` (`--offline`) | PASS: コマンド258.574秒、788タスク中36実行・752 up-to-date。cleanなし。lintVitalとrelease縮小処理を含む。 |
| `PlayingIndicatorVisibilityTest` | PASS: 3件。legacy/sharedの実描画が覆われたscopeで停止・再開すること、compositionと共有時計の同一性、scope外の可視キューの動き、STARTED→CREATED→STARTEDの停止・再開。 |
| `SearchResultPresentationTest` | PASS: 3件。長い名称のテーマ/幅/文字サイズ別表示、通常行への影響なし、遅れて現れる行のアニメーション同期・一時停止・再開。 |
| Androidテスト合計 | PASS: 6件、テスト本体14.260秒、コマンド17.226秒。Pixel_9_API_35 / emulator-5556、API35、arm64変換実行。 |
| APK検査 | PASS: coreRelease、arm64-v8aのみ、versionCode 71、versionName 0.10.2-b1、minSdk24、targetSdk36、APK v2署名有効。 |

```text
gradlew.bat --offline :app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease
adb -s emulator-5556 shell am instrument -w -r -e class com.dd3boh.outertune.ui.PlayingIndicatorVisibilityTest,com.dd3boh.outertune.ui.SearchResultPresentationTest com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- 診断出力は無視対象 `build/diagnostics/queue-resource-fix-20260929/`。テスト後はdebugを停止し、CPU測定時はGradle処理も終了済み。
- 新しい一時AVDセッションに同じ34曲をコピー。ビルド待ち中に旧インストール済みreleaseで通常UIから準備スキャンし、完了後に最終release APKを更新インストールした。最終APKで33アルバムを確認。準備時の `setup-*` は修正後の検証証跡には数えない。
- 改修版SHA-256: `260cda5285ca08d90d4b3224f122a6b3ae41253b0685fb07e30b937a1dfc632a`。

### 最終releaseでのキュー・性能確認

- **PASS / 通知と追加:** 長い右スワイプで `"遺サレタ場所"をキューの最後に追加しました`、短い右スワイプで `"遺サレタ場所"をキューに追加しました` を表示。キューは13→14→15曲。追加した曲を選びPLAYING・error=null、同じ曲の位置が5,999ms→33,019msへ進むことを確認。次への追加後は現在曲14/15の直後に同じ曲を追加したことを画面で確認。
- 画面証跡: `final-swipe-end-notice.png/.xml`、`final-swipe-next-notice.png/.xml`、`final-queue-added.png/.xml`、`final-queue-next-added.png/.xml`、`final-player-playing.png/.xml`。再生は `playback-before.txt` / `playback-after.txt`。
- **PASS / 覆われた一覧の停止と再開:** 同じ曲・33アルバム・同じ14曲キューで、アルバム詳細の上に全画面プレイヤーを表示。続いてプレイヤーを閉じてアルバム行を表示し、次への追加後にプレイヤー内キューを表示、閉じて全画面へ戻す流れで確認。

| 最終releaseの条件 | CPU平均 / 最大 (1コア=100%) | 終了後PSS | 描画数 / 区間 |
| --- | --- | --- | --- |
| アルバム詳細を全画面プレイヤーで覆う (`final-player-over-album-playing`) | 14.56% / 17% | 90.98MiB | 51 / 25秒 |
| プレイヤーを閉じた可視アルバム詳細 (`final-album-visible-playing`) | 60.75% / 63% | 91.35MiB | 499 / 8秒 |
| 可視キュー (`final-queue-visible-playing`) | 60.88% / 66% | 94.38MiB | 490 / 8秒 |
| キューを閉じ、全画面プレイヤーへ戻す (`final-player-over-album-repeat-playing`) | 14.80% / 16% | 94.12MiB | 42 / 20秒 |

- 全区間PID=5101、期待した25/8/8/20サンプルを採取。実画面でも見えている再生インジケータは動き続け、覆ったときだけ継続描画が止まる。
- 改修前の同条件ではCPU64.35～71.24%、描画は毎秒約60回。改修後は14.56～14.80%、描画は毎秒約2回。再度覆った条件でも再現し、見えないアニメーションを止める効果を確認した。
- CPUはエミュレータ上のアプリプロセスで100%=1コア。実機の電池削減率・CPU使用率へ換算しない。PSSの前後比較は画面操作履歴・GC・別起動の影響もあるため、メモリ改善率として使わない。
- 低頻度描画区間のgfxinfoのjankは45/51 (88.24%)、39/42 (92.86%)と高い値。全ログを残し、今回のCPU・描画回数の削減とは分ける。実機のスクロールや滑らかさ全般が改善したとは判定しない。
- lifecycle停止・再開と可視scope維持はAndroidテストでもPASS。tablet実画面、実機、オンライン実通信、長時間の消費電力は未確認。既知のrelease証明書制約を再調査していない。

### 配布・終了状態

- 配布APK: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-queue-resource-20260929.apk`。
- coreRelease / arm64-v8a、9,202,061 bytes。元の検査対象APKと配布コピーのSHA-256はともに `260cda5285ca08d90d4b3224f122a6b3ae41253b0685fb07e30b937a1dfc632a`。
- すべてのテスト・測定は上記の最終ソースとAPKで実施。以後の変更は本記録のみ。独立レビューと差分の空白確認に問題なし。commit・pushは今回行わず、4ソース・新規Androidテスト・本記録を未コミットで保持。
- 最終測定後にdebug/releaseを停止し、一時エミュレータを終了。`-read-only -no-snapshot-save` のため検証用の音源・アプリ更新・設定・DB操作は元AVDへ保存しない。実機・元音楽は未変更。操作占有を解除する。
