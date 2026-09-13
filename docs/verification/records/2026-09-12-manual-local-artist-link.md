# 2026-09-12: ローカルアーティストの手動オンライン紐付け

## 依頼・開始点

- 基点 `f9326e28`。ユーザー承認した手動操作の紐付け・変更・解除を実装する。自動の候補決定/紐付けは行わない。
- アーティストの3点メニューから名前検索/YouTubeアーティストURL指定、候補の名前・画像・代表アルバム、対象のローカルアーティストと曲数を確認して保存する。
- 元のローカルID・名前・曲/アルバムとの関連を維持。画像と既存アーティストページのオンライン表示切替で対応先を利用する。
- YouTubeアカウント同期停止は維持。検索・ページ取得だけでは手動の紐付けを保存しない。

## 合格条件

- 新規紐付け・変更・解除を画面から行える。確認前のキャンセル/エラーで元の紐付けを壊さない。
- 再起動/通常再スキャン/最後の関連曲消失でも手動の対応を保持する。同じ名前のローカル人物を再利用する。タグで人物名が変わった場合は別人物として扱い、新しい人物へ対応を推測移管しない。
- 相手を変更/解除した後に遅れて届いた以前の候補やページを表示/保存しない。表示言語や認証条件の変更も検証する。
- ローカル表記と既存の保存/いいね/再生処理を保持。オフラインでも解除と保存曲表示は利用可能。
- DB24→25は追加表のみの移行で既存データを保持する。schema JSONは最終コンパイルで生成・確認する。

## 検証予定

- 隔離Roomで保存内容・再スキャン時の人物再利用・削除保全・変更競合・解除を確認する。
- 候補検索/URL解析・選択/認証変更・古い応答の排除を合成データで検証する。
- 最終core-release / arm64 APKを生成し、検証用エミュレータで手動操作と再起動後の保持を確認する。ユーザーのスマホは操作しない。

## 最終実装

- `local_artist_link` に端末内ID・公開YouTubeアーティストID・確認時の名前/画像・選択revisionを保存する。元のArtistEntity/SongEntity/曲とアルバムの関連を置換しない。
- 名前検索、channel/browse URL、@handle URLに対応。候補取得とプレビューはmetadata observerを通知せず、明示確認でだけ対応表を書く。代表アルバムは取得したページ内の最大3件。
- URLを任意の接続先として取得せず、許可したYouTubeホスト・アーティスト形式から固定のresolve_url APIへ渡す。動画・プレイリスト・外部URL・非公開アップロード人物を拒否する。
- 選択revision・コンテンツ言語・認証revision・取消状態を保存時に照合。保存処理中の取消もtransactionをrollbackする。アーティストページの遅い応答は取得世代・文脈・選択revisionで破棄する。
- 画像を人物一覧・メニュー・曲内の人物選択・人物ページへ反映。オンライン切替先を手動linkから取得する。元の端末内表記を維持し、オンラインページには従来のコンテンツ言語処理を使う。
- 通常再スキャン・最後の曲削除・ローカル取り込みOFF→ONでも明示linkを保つ。ファイルタグの人物名変更では別の人物を作り、linkを自動移管しない。
- DB24→25は追加表だけ。既存entityのschema JSONが一致することと、実際のアプリ用builderで既存曲を維持して移行できることを確認した。
- 追加指示：人物画面の見出しを「ライブラリ内の曲」から **「ローカルの曲」** へ変更。英語は `Local songs`。他画面のライブラリ表記は変更しない。

## 実行結果

対象ソース：`f9326e28` + この課題の未コミット差分。Gradleはrootが逐次実行。Pixel_9_API_35 / emulator-5556 の使い捨てデータのみ操作。x86_64エミュレータのarm64変換で実行し、スマートフォンは操作していない。

| 検証 | 結果 |
| --- | --- |
| `:innertube:test --tests com.zionhuang.innertube.YouTubeArtistUrlTest` | PASS、4件、試験本体0.184秒 |
| `:innertube:test --tests com.zionhuang.innertube.MetadataObserverArchitectureTest` | PASS、2件、0.185秒。既存のobserver入口を維持 |
| `:app:testCoreDebugUnitTest --tests '*LocalArtistLink*'` | PASS、Repository 8件 + ViewModel 8件、0.266秒。URL testを含む初回Gradle全体2分16秒 |
| 取消補強後 `:app:testCoreDebugUnitTest --tests com.dd3boh.outertune.repositories.LocalArtistLinkRepositoryTest` | PASS、9件、0.166秒。検証用APK更新を含むGradle全体45秒 |
| `:app:assembleCoreDebug :app:assembleCoreDebugAndroidTest` とobserver test | PASS、Gradle全体38秒 |
| AndroidJUnitRunnerで LocalArtistLinkDatabaseTest / ArtistPageLinkTest / ArtistImageDatabaseTest / ArtistCreditDatabaseTest / ContentSourceFilterDatabaseTest / YouTubeSyncDisabledTest | PASS、計29件、試験本体3.118秒 |
| LocalArtistLinkUiSeedTest、`seedLocalArtistLink=true` | PASS、1件、0.6秒。エミュレータに専用IDの人物と6秒の自作WAVを登録 |
| core-debug 実通信・画面 | PASS、名前検索→Sheena Ringoの名前/画像/3アルバム確認→明示保存→画像反映→オンライン切替→Tokyo Incidentsへ変更→@ringosheenaofficial URLからSheena Ringoへ再変更 |
| core-debug 再起動/障害 | PASS、再起動後に選択が保持。オフライン検索失敗で旧link維持。解除キャンセルで旧link維持。オフライン明示解除で元の人物画面へ戻る |
| 実操作後のDB | PASS、元の椎名林檎のID/name/isLocal/onlineId、合成曲title/isLocal、SongArtistMapが維持。linkだけがUCRQX-dpFt_osBpH71ItuuvAを保持 |
| 最終 `:app:assembleCoreRelease` | PASS、文言追加前3分17秒、追加後2分15秒。最終APKに新見出しを反映。lintVital通過 |
| 最終core-release 画面/再生 | PASS、合成バックアップをアプリの復元画面で取り込み、ライブラリ→人物で「ローカルの曲」、元の名前と曲を表示。自作WAVがPLAYING/速度1.0/6000ms読み込み済み、プレイヤー位置0:02。プレイヤーの人物名から元の人物ページへ戻れる |
| 最終core-release 通信失敗 | PASS、証明書エラーで検索に失敗しても既存link・元の曲を保持し、解除確認へ進める |
| 最終core-release 解除/再起動 | PASS、明示解除後に再起動し、人物メニューが新規「YouTubeアーティストを紐付け」に戻る。曲・人物名は維持 |
| schema・成果物 | PASS、既存entity schema一致、追加表1個のみ。APK内のnative ABIはarm64-v8aのみ、署名v2検証成功 |

近傍テストは計52件（単体23、Android29。seedは別）。Android DB/ページ試験後の機能変更は保存取消補強（Repository単体9件で再検証）と見出し文言（最終配布版で目視確認）のみ。成功済みの全試験の再実行はしていない。

## 実施範囲・制約

- 実際のコンテンツ言語/認証通知を伴う全操作の組合せは未網羅。ページの文脈変更は疑似context＋実ViewModel/Roomで検証し、通知経路はコードレビューで確認した。アプリ表示言語だけでは手動linkを書き換えない。
- 再スキャンはproduction DAOと重複整理関数の呼出しで検証。端末の実ファイルスキャナーを使った長時間運用や任意タグ形式の全網羅を意味しない。
- 同名人物で異なる明示linkがある場合、重複整理は両方を維持してその名前グループの残りをスキップする。スキャン全体は継続する。選択の自動統合はしない。
- YouTube.artistのpage IDは要求IDを保持する既存仕様。入力/返却IDの比較を、外部応答による独立した同一性証明とは扱わない。取得したページの名前・画像・アルバムをユーザーが確認して選択する。
- **BLOCKED（配布版の実通信確認）**：このPCのHTTPS検査環境で `SSLHandshakeException / CertPathValidatorException: Trust anchor for certification path not found` を確認。配布版の信頼設定は変更していない。実際の検索・URL解決・候補確認・新規保存/変更・画像取得は、この検証環境の証明書に対応した既存core-debug構成で確認済み。最終配布版の実通信は、検査証明書の問題がない接続環境で別途確認が必要。

## 配布成果物

- `build/distributions/OuterTune-manual-artist-link-20260912-arm64.apk`
- `coreRelease` / `arm64-v8a` / `0.10.2-b1 (71)` / `com.dd3boh.outertune`
- SHA-256: `6ecf465d4ccbf718dee9eaefceb5c0f394190fb8869beaeaacf0035f207a2e1e`
- 対応mapping: `build/distributions/OuterTune-manual-artist-link-20260912-mapping.txt`
- 検証ログ/画像/合成バックアップは `build/diagnostics/manual-link-20260912`、Gradleログは `build/manual-link-*.log`（すべてgit管理外）。実ユーザーの認証・バックアップ・音源は利用していない。
- エミュレータは確認終了後に停止し、操作制限の解除をユーザーへ案内済み。
