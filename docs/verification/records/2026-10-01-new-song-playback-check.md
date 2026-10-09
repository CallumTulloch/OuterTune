# 2026-10-01: 新規曲のHTTP 403と音声URL検証の改修

## 依頼と範囲

- 実機で新規曲を再生できないとの報告。エミュレータで試し、再現できなければ実機のエラーコードを受け取る。
- 対象ソース: `0e16cca9`。開始時の作業ツリーはクリーン。当初は再現調査のみ。その後、ユーザーから改修の承認と実機エラーログを受け取り、音声URLの検証・代替方式への切り替えを改修する。
- 検証端末: `Pixel_9_API_35` / `emulator-5554`、Android 15/API 35、x86_64上のarm64変換。rootが操作担当を告知して起動。
- 使用APK: 前回の最終coreDebug / arm64-v8a / 0.10.2-b1 (71)、`com.dd3boh.outertune.debug`。再ビルドやアプリデータの初期化は行っていない。

## 操作と結果

- まず `J6EDW5WFb2M` のwatch URLをアプリへ渡したが、既存の検証DBに同IDがあるため、新規曲としての判定には使わない。
- debugアプリを停止し、アプリDBとWALの読み取りコピーから、`dQw4w9WgXc` がsong/formatの両テーブルに存在しないことを確認した。
- アプリを再起動し、通常のVIEW intentで `https://music.youtube.com/watch?v=dQw4w9WgXc` を開いた。この操作は `youtubeNavigator` → `YouTube.queue` → `ListQueue` → プレイヤーの通常経路を使う。
- `OBSERVED`: debug版でも `SSLHandshakeException` / `CertPathValidatorException: Trust anchor for certification path not found` が発生。曲情報を取得できず、対象曲の再生キュー作成に到達しない。旧曲のミニプレイヤー表示が残る。
- `BLOCKED`: 新規曲の音声取得・再生処理そのものを実通信で確認できない。実機の「新規曲だけ再生できない」原因との一致は未確認であり、この環境の証明書エラーだけで実機の原因を確定しない。
- 今回の操作のcrash bufferは空。DB制約例外・チャンネル行の不整合例外によるクラッシュは観測していない。
- 既知の証明書制約の再調査・設定変更・release版での同じ通信の反復は行わない。既存曲のオンライン再生との成功比較も未確認。

## 差分点検

- `0e16cca9` は音声URL取得、音声format選択、動画ID/customCacheKey生成を変更していない。新規曲で増えた投稿者クレジット回復・DB保存は別コルーチンの処理で、ストリームURLを返すための必須条件ではない。
- コード上の例外候補は、従来からある再生時間の数値変換と、既存CS行のsourceChannelId/isChannel不整合。今回のログにこれらが発生した根拠はない。推測に基づく改修は行っていない。
- 後続の実機ログで音声GETのHTTP 403を確認。証明書エラーとは別原因として扱う。

ログ・操作ヘルパー・画面・読み取りDBコピー: `build/diagnostics/new-song-playback-20261001/`（Git対象外）。

この再現調査ではrootの操作を終了し、今回起動したエミュレータだけを停止した。

## 実機報告と確認した欠陥

- ユーザー報告: Samsung SM-S931Z / Android 36、0.10.2-b1 (71) / core release / `com.dd3boh.outertune`。Media3 `Source error` の原因は `Response code: 403`。
- ログのR8 map IDは前回配布APKのmappingと一致する。`e5.b0` は `HttpDataSource.InvalidResponseCodeException`、`g5.b` は `OkHttpDataSource`。キュー情報取得のTLS失敗ではなく、音声ソースを開くHTTP要求の拒否。
- 音声URL選択は0e16cca9以前からの処理。末尾IOSだけ検証を省略し、再生不能URLでも成功データとして返せる。HEADでの確認は実際のGETの拒否を検出できない。
- 主クライアントの要求例外も、代替クライアントへ進む前に処理全体を終了していた。
- 新規曲はキャッシュがないため音声GETが必要。完全キャッシュ済み曲はHTTP要求を省ける。ただし実機の既存曲がすべて完全キャッシュ済みか、どのクライアントが403だったかは未確認。
- 403時は一度URLを破棄して再取得する既存処理があるが、同じ検証漏れにより拒否URLを再採用し得る。今回のログから実際に再採用が起きたとは断定しない。

## 範囲と合格条件

- 全クライアントの候補をGET / Range `bytes=0-0` / `Accept-Encoding: identity` で検証し、HTTP 2xxを返した候補だけ採用する。responseを閉じ、音源全体を検証のためにダウンロードしない。
- 末尾候補も拒否・通信失敗なら失敗として返す。主要求例外では代替へ進む。キャンセル、音質選択、既存音声のitag固定、ログイン必須方式の扱い、診断情報を保つ。
- 主応答が取得できたときは従来の主メタデータを保持し、主要求失敗時は検証に成功した代替応答を使う。
- 単体テストでHEAD200/GET403、GET206、全候補403、末尾通信失敗、主要求失敗、キャンセル、itag固定を確認。
- エミュレータではサーバー応答のみ合成し、本番の候補選択・GET検証・Media3 HTTPデータソース・キャッシュ・デコーダーを通して未キャッシュの曲が再生位置を進めることを確認。TLS信頼設定は変更しない。
- 実機で対象曲が再生できることは別の合格条件。検証PCの既知のTLS制約により、ここでのテスト成功を実機問題の解決と同一視しない。

## 改修後の検証

- 対象: `0e16cca9` を親とする未コミットの最終差分。音声候補の選択を `PlaybackStreamResolver.kt` に抽出し、製品とテストで同じ処理を使う。HTTP検証は `PlaybackStreamProbe.kt`。DB、証明書設定、アーティストの仕様は変更しない。
- `PASS`: `:app:testCoreDebugUnitTest --tests '*Playback*' --tests '*PoTokenSessionCacheTest*'`、42件、失敗/エラー/スキップ0、テスト本体0.978秒。新規probe5件・resolver9件を含む。認証変更時の再試行、診断の秘匿、format固定、URL期限と既存の再取得制限も確認。
- `PASS`: 最終coreDebugの `PlaybackStreamFallbackTest` / `PlaybackCacheDataSourceTest` / `PlaybackMetadataContinuityTest`、エミュレータで6件、3.774秒。
- 新規の端末テストは空の2音声キャッシュから開始。HTTP応答のみOkHttp interceptorで合成し、主候補GET403、代替GET206、本番の候補選択・probe・OkHttpDataSource・2段キャッシュ・WAV extractor/decoderを使う。READYと250ms以上の再生位置進行、媒体ID/URI/cacheKey保持、拒否URLが再生時に開かれないこと、音声キャッシュ完成後の再読込ではURL解決・HTTP不要を確認した。実MusicServiceのYouTube要求・DB登録を合成したテストではない。
- probeは `Range: bytes=0-0`、Media3初回の無制限GETはRangeなしという違いがある。両者のGETと `Accept-Encoding: identity`、各Range条件をassertする。少量probe成功だけで実ネットワークの以後すべての要求を保証するものではない。
- `PASS`: `:app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease`。単体テストとまとめたGradle全体4分36秒（276.5秒）、lintVital成功。既存のRoom query列・非推奨API・外部Compose mapping警告は残る。
- `PASS`: 差分チェック、APK v2署名、native librariesがarm64-v8aだけであること。端末crash buffer空。レビューでも認証snapshot/キャンセル/itag維持と全候補拒否時のFailureを確認。
- エミュレータはrootが起動・更新インストール・テストを担当。終了を告知し、今回起動した端末のみ停止。アプリデータ初期化、画面密度・文字倍率・証明書変更なし。
- `BLOCKED` / 未確認: 検証PCからのYouTube実音源再生は既知のTLS制約。実機の対象曲で403が解消するかは新APKで確認が必要。実機ログが示す403の取得クライアント・対象曲URLは不明のため、解決済みとは扱わない。

最終Gradle呼び出し:

```text
gradlew.bat :app:testCoreDebugUnitTest --tests '*Playback*' --tests '*PoTokenSessionCacheTest*' :app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease --console=plain
```

成果物:

- APK: `build/distributions/OuterTune-stream-403-20261001-arm64.apk`
- coreRelease / arm64-v8a / 0.10.2-b1 (71) / `com.dd3boh.outertune`
- SHA-256: `2891f909d2cdcd92bd6cfda3d7fa021f0cb7a4c19f6b5c1e66d7571086d0a3cb`
- mapping: `build/distributions/OuterTune-stream-403-20261001-mapping.txt`
- ログ・単体metrics・端末結果・署名確認: `build/diagnostics/stream-403-20261001/`（Git対象外）。前回配布APKを保持し、別名で保存した。
- 現在のブランチ: `restart/artist-20260905`。次の確認は同じ実機・対象曲で新APKを試し、残る場合は新しいプレイヤー診断を確認する。

## 2026-10-09: コミット整理

- ユーザーのcommit/push依頼により、保全していた本改修・単体2ファイル・端末1ファイル・本記録の計7ファイルを1コミットにまとめた。開始HEADは `433cda1f`。
- フォーク回帰の最終証跡 `build/diagnostics/fork-regression-phase2-20261001/source-final.json` と、本番/テスト等780入力のhashがすべて一致。ソース不変のため、既存の単体538件PASS・端末検証・署名済みcoreRelease確認を再利用し、同じ検証を再実行しない。
- 10月1日に配布した最終release APKにも本403対策が含まれる。最新のAPK hashと通常UI検証は [フォーク回帰記録](2026-10-01-fork-regression.md#配布用corerelease-apk) に記載。APK・診断ログ・mappingはGitへ含めない。
- 実機での403解消は引き続き未確認。コミット整理によって実通信の合格条件を達成したとは扱わない。
