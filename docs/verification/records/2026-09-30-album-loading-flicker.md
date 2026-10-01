# 2026-09-30: アルバム初回表示の1曲→全曲の切り替わり

## 現象・根拠・範囲

- ユーザー報告: プレイヤーからアルバムへ移ると、再生中の1曲が先に表示され、その後アルバム全体の曲が表示される。
- 着手時ソース: `c7e8172e`、作業ツリーはclean。前回の負荷・曲ID修正はコミット済み。
- 再生曲の保存は `songCount=1` / `hasTrackList=false` の仮アルバムを作る。従来のAlbumScreenはアルバムレコードがあれば関連曲を表示し、取得済みの正式な曲一覧と区別しなかった。通信後の全曲保存でRoomが再通知し、一覧が差し替わる。
- 今回はオンラインアルバムの表示準備状態と、それに依存する操作だけを変更する。動画由来アーティストの扱い、音源選択、DB定義、保存された関連曲は変更しない。

## 方針・合格条件

- `isLocal || hasTrackList` の場合だけ曲一覧を表示。オンラインの未取得状態は既知の見出し・画像を保持し、曲一覧部分を読み込み表示にする。仮の「1曲」は曲数欄にも表示しない。
- 未取得時の全曲再生・シャッフル・DL・全曲メニューを無効にする。アルバム単位のお気に入りは利用可能。
- 完全なキャッシュは更新通信中も維持。正式な1曲アルバムと端末内アルバムは即表示。
- 通信失敗・空の応答は再試行できる。DBの通知より通信終了の通知が先に届いても、一瞬エラーや仮の1曲へ切り替えない。
- 取得待ち→失敗→再試行→全曲→キャッシュ再表示を、既存Billie Jean fixtureと通常UIで確認する。実通信の初回待ち時間短縮とは区別する。

## 結果

- **PASS:** `AlbumScreen`が未取得のオンライン曲一覧を表示対象から外し、曲数・再生・シャッフル・DL・More・選択操作へ同じ準備条件を適用。見出しと画像、お気に入り、下部プレイヤーは維持する。`AlbumViewModel`は空の成功応答も失敗状態へ移し、無期限の読み込みを防ぐ。
- ソースは上記commit＋今回の未コミット差分。最終本番2ファイルのSHA-256を `build/diagnostics/album-loading-flicker-20260930/verified-main-source.json` に保存し、検証終了後の一致も確認。DB schema・アーティスト採用規則・通信件数は変更していない。
- coreDebugビルド56秒、coreReleaseビルド2分43秒、AndroidテストAPK25秒、すべて成功。途中のcoreDebugはUIの固定応答検証に使用し、配布はcoreRelease / arm64-v8a。
- **PASS:** 既存 `AlbumSourceIdentityDeviceTest` のHTTP・音声fixtureを再利用。最終実行PID8074、`normalAlbumLoadingUiDoesNotExposeThePlayingSongAsACompleteAlbum` は1テスト成功、184.479秒。再生中Billie Jeanから通常操作でThrillerへ遷移し、通信待ち・通信失敗・空応答・全9曲・キャッシュ再訪・正式な1曲・端末内1曲の7場面を確認。画面のXML/画像と実DBを照合し、待機中にBillie Jeanが下部プレイヤー以外の曲行へ出ないこと、仮の1曲を曲数として出さないこともassert。
- 取得前のDB関連曲1件は保持したまま画面だけを待機表示にする。取得後は正式な全9曲となり、再訪して応答を保留しても9曲表示を維持。端末内albumは `hasTrackList=false` でも1曲を即表示。正式な1曲albumは更新失敗時も曲と操作を維持して再試行を併記する。
- **PASS:** Billie Jeanの保存・再生IDと295秒、再open/取得失敗時の維持を確認する既存回帰1件、2.837秒。今回の成功数はUI1件（7チェックポイント）＋ID回帰1件で、前回の244件を再実行したとは扱わない。
- 証跡は同診断先の `ui-instrumentation.txt` / `ui-checkpoints.txt` / `01-loading`〜`07-local` のXML・PNG / `identity-regression.txt`。logcatは以前のPIDを含むため、最後のREADYと同じPID8074の7件のみを成功根拠とする。single/localチェックのログの `tracks=9` は主アルバムThrillerのDB件数であり、その画面の曲数ではない。
- **ホスト操作の失敗を分離:** 最初の実行は端末内albumへ移る前のtapで別画面を検査して失敗。2回目は展開プレイヤーの背後に残る検索結果の同名曲を選択して失敗。`first-run/` / `second-run/`へ保存し、PASSや自然crashには含めない。既存のUI取得用 `ui.py` を再利用するホスト補助を修正し、新しいXMLが取得できたこと・対象画面見出し・表示位置を確認してから操作/チェックを進めた。本番コードを変えずに3回目で上記7場面がすべて成功した。
- **PASS:** 配布APKの署名・ABI・packageを確認。通常版へ上書きインストールし、通信を切った状態で起動（cold start1,420ms）→保存済みNevermindの13曲とPollyの選択表示をXML/画像で確認。PID9367の取得ログにOOM/fatal/ANRなし。homeの通信失敗表示はオフライン条件によるもので、再試行していない。`release-start.txt` / `release-album.*` / `release-smoke-logcat.txt`。
- 配布: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-album-loading-20260930.apk`、9,218,445 bytes、SHA-256 **`239b50b3cf9c196af4c040fde558e45d8cdb991343414c978dcc2cd8ce502485`**。通常package `com.dd3boh.outertune`、versionCode71、非debuggable、arm64-v8aのみ。署名証明書は前回と一致し上書き更新可能。
- 実通信の初回待ち時間・実機の描画フレームは未計測。今回確認したのは誤った中間一覧の非表示とキャッシュ維持であり、初回通信を不要にしたわけではない。実機操作・インストールは行っていない。
- **終了:** 22:44:20 JST、今回起動した一時エミュレーターPID48260/35668とその補助プロセスを起動引数・親子関係・生成時刻で確認して終了。残存なし、専用ADB5038停止済み。通常のエミュレーターを利用可能。`emulator-cleanup.json`。

- **ユーザー確認:** 2026-10-01、配布後にユーザーから「修正が確認できた」とコミット依頼を受領。

再実行は、使い捨てエミュレーターのdebugデータで次のopt-inを指定する。各画面の実XMLをアプリの `files/album-loading-<phase>.xml` へ置き、同名の `check-<phase>` ファイルで検査を要求する。応答制御/終了ファイルと詳細手順はテストのコメントに記載。

```text
adb -P 5038 -s emulator-5556 shell am instrument -w -r -e albumLoadingUi true -e class com.dd3boh.outertune.repositories.AlbumSourceIdentityDeviceTest#normalAlbumLoadingUiDoesNotExposeThePlayingSongAsACompleteAlbum com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner
```
