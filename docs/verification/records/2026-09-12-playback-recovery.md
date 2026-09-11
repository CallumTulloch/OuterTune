# 再生不能・再試行経路の見直し

## 着手時の課題

- 現象：ユーザーから「曲が再生できなくなる」と報告。曲ID・操作順は未特定。
- 期待：一時的な通信障害や再生URLの失効後も再試行でき、通信復帰を無期限に待たない。サービス終了後の処理が次の再生を妨げない。
- 対象ソース：`7bc8bfa5847652d9cd6e7883de562574be36147e` と既存の未コミット変更。既存変更は保全する。
- 確認済み根拠：`MusicService` のURLキャッシュはエラー時に破棄されない。ネットワーク待機開始後、接続状態が既に true のとき再開コレクタは動かない。`onDestroy` がサービスのコルーチンとネットワーク監視を終了していない。`ThumbnailPlaybackError` は `retry` を受け取るが使用しない。
- 未確認：これらのどれがユーザーの観測した症状に一致するか。実際のYouTube通信・端末操作による再現。

## 範囲・合格条件

1. HTTPで拒否されたURLを再試行時に使い回さず、再取得を無限に繰り返さない。
2. 接続がある通信エラーで、発生しない接続イベントを待ち続けない。オフライン待機は復帰時に同じ曲の再開だけを行う。
3. 再生エラー画面の再試行操作と、サービス終了時の監視・非同期処理の終了を確認する。
4. 回帰テストで一時URLの失効・無効化・再試行回数の境界を検証し、端末未確認は明記する。

## 確認した不具合と変更

1. **キャッシュ途中の欠落をオンライン取得できない**：再生側は最初の512 KiB、ダウンロード側は要求範囲（長さ不明なら1 byte）だけを見て未解決の曲IDを返す。Media3の `CacheDataSource.openNextSource` は、同じ `open` 内で保存済み範囲から欠落範囲へ移るため、その後のupstreamが未解決IDを直接開く。URL解決をキャッシュの内側へ移し、欠落範囲を開くたびに位置・長さ・ヘッダー・曲IDを保ったままURLだけを置換する。再生・ダウンロードで同じfactoryを共有する。初回取得だけ512 KiBに制限する処理も撤去。
2. **拒否された一時URLを使い回す**：再生エラー／ダウンロード失敗でURLを無効化し、HTTP 401/403/410は再生中の選曲につき自動再取得を1回まで行う。URL期限は単調時計で管理し30秒の余裕を取る。長時間利用でURLが増え続けないよう64件に制限。
3. **音声の異なる形式を連結し得る**：AUTOの回線判定や音質設定変更後の再取得では、同じ曲IDでも異なるitagが選択され得た。保存済みバイト／途中位置がある再生と、保存済みバイトを利用するダウンロードでは既存itagを要求し、別形式を選ばず他クライアントへフォールバックする。形式DB更新は新しいバイトを開く前に完了させる。形式の付加情報欠落による `!!` / 配列参照の例外も回避。
4. **ネットワーク復帰を無期限に待つ**：接続済みのままソケットエラーになると、発生しない接続変更を待っていた。実際にオフラインで、通信に関連するエラーのときだけ待機する。接続状態更新とplayer操作は同じMain scopeで行い、復帰時もユーザーのpauseを上書きしない。
5. **再試行操作がない／終了した曲を再生できない**：エラー画面へ既存の `retry` 文字列を使ったボタンを追加し、URL無効化→prepare→playへ接続。曲末尾で `STATE_ENDED` になった再生ボタンは現在の曲の先頭へ戻して再生する。
6. **サービス終了後も処理・監視が残る**：ネットワーク監視を同期初期化し、サービス破棄時に解除。サービスscopeをキャンセルしてからplayerを解放する。保存は独立した `runBlocking(IO)` なのでキャンセルされたscopeへ依存しない。空キューの終了で `last()` が例外になる経路も防止。
7. **再生中の言語追従**：`YouTube.localeUpdates` で選曲中の人物クレジットを再要求。既存のMediaItem URIとcache keyを維持した表示更新がMedia3の `ProgressiveMediaSource.canUpdateMediaItem` で音声再読込なしに扱われることをコード確認。
8. URL確認用HTTP HEADのresponseを `use` で閉じる。
9. 旧ダウンロード移行は稼働中DownloadManagerのindexを再利用。indexを読むだけのために2台目のmanagerを作っていたため、保留中ダウンロードが未解決URLの別経路で起動し得た。追加managerと無処理resolverを撤去し、移行中も既存のURL解決・形式保持経路へ統一。

## 回帰テスト

- `PlaybackUrlCacheTest`：6件。拒否URLの無効化・他曲保持、期限境界、短命URLの置換、上限、1回だけの自動再試行、pause/永続エラーの扱い。
- `PlayerExtTest`：4件。曲末尾の再生再開、エラー停止後prepare、ネットワーク待機中pause、通常pause/resumeの位置保持。
- `PlaybackFormatsTest`：4件。回線／音質変更後のitag保持、未キャッシュ時の音質選択、既存itagのないクライアント拒否、映像形式除外。
- `PlaybackCacheDataSourceTest`（androidTest、実際の共有factoryと一時SimpleCacheを使用）：4件。download/playerそれぞれの保存済み先頭から欠落部分を取得し512 KiB以降まで全バイト一致、再読込の通信不要、2つのキャッシュをまたぐ途中seekの位置・長さ・ヘッダー・key保持、完全キャッシュのオフライン読込。
- `git diff --check`：PASS（最終編集後）。
- `PlaybackMetadataContinuityTest`（androidTest）：1件。実ExoPlayer、生成WAV、保存済み先頭を使い、日本語→英語→日本語のMediaItem表示変更後も位置が250ms以上進むことと、8秒位置へのseek後の再生を確認。音声通信だけをfixtureに置換。
- 最終Gradle：PASS。`build/language-playback-review-final-2.log`、1分8秒。app単体284件成功（本体2.632秒、上記14件を含む）、innertube 57件成功・既存13件skip。
- 最終端末：PASS。PlaybackCacheDataSourceTest 4件、PlaybackMetadataContinuityTest 1件。Pixel_9_API_35 / emulator-5556、arm64-v8a debug APKをx86_64＋arm64変換端末で実行。`build/language-playback-device-tests.log`。同じrunの別RepositoryクラスでJUnit宣言エラーがあったが本5件は成功。当該宣言だけ直した後、Repository 3件も成功（`build/language-repository-device-final.log`）。本番ソースは以後未変更。
- 途中ビルド：`build/language-playback-review-final.log` の `:app:compileCoreDebugKotlin` がFAIL（32秒）。DownloadUtilの移行経路に残った不要resolverへのimportを削除していたことが原因。移行経路を既存manager再利用へ統一して修正。別担当のOnlinePlaylistViewModel型推論エラーも同ログにある。最終結果は再実行後に判断する。

## アプリで確認する再現候補

実機確認前にユーザー報告の根本原因を特定済みとは扱わない。

- PBR-1：一曲を途中までストリーミングし別曲へ移動→元の曲を先頭／未取得位置から再生。途中からダウンロードを開始するケースも含む。保存済み範囲の先へ進み、最後まで再生・保存できること。
- PBR-2：ストリーミング中の回線断→復帰、復帰前のpause、接続表示が維持される一時エラー。復帰は同じ曲で行い、pause後に勝手に再開しないこと。
- PBR-3：エラー画面の「再試行」で再取得し再生できること。一時URL失効は短命URL／HTTP 403注入での確認が必要。
- PBR-4：1曲の終了後に再生ボタンを1回押すと先頭から再開。サービス終了・再起動を繰り返した後も再生とネットワーク復帰が有効。
- PBR-5：途中再生→AUTOのWi-Fi/従量回線切替、または音質設定変更→ダウンロード／再開。同じ形式のバイトを継続すること。同一曲の初回再生とダウンロードを完全同時に開始する競合は端末未確認。

既存形式がどのクライアントでも返らなくなった場合は、別形式のバイトを既存キャッシュへ連結せずエラーにする。破損済みの過去の音声キャッシュの自動修復は今回実装していない。

## 配布版

core-release / arm64-v8aビルドPASS（3分15秒）、署名・単一ABI・インストール・Welcome画面起動・クラッシュ記録なしを確認。配布物は `build/distributions/OuterTune-language-playback-20260912-arm64.apk`、SHA-256 `001fd1cc4645ad1b0a9dda23745ccb016a1f64f2bbc1f603facc20ebc37761f3`。

配布版でのYouTube実通信によるPBR-1〜5は未確認。PBR-1の読み継ぎと表示変更・seekは上記debug端末の実cache/ExoPlayerで検証、URLと音質選択と曲末尾操作は単体検証。利用者の曲ID・操作順が判明した場合は、その条件で配布版を確認する。詳細な生成・端末ログは同日のmetadata-language-review記録を参照。

後続のアルバムラジオの短い応答による例外修正、再生表示の言語切替、更新した最終APK・確認範囲は[コンテンツ言語の網羅記録](2026-09-12-content-locale-coverage.md)を参照。
