# 時間経過・同期後のアーティスト名変化

## ユーザー報告と期待動作

ユーザーがアプリを1日使用し、最初は正常だったが、しばらく操作せず再度開くとアーティスト名が英語になり、所々アーティスト不明になったと報告。使用APKのハッシュ、Google同期後かどうかは未確定。

追記条件：日本語設定、既存の英語表記利用ON、対象例は「椎名リンゴ」（ユーザー原文）。表示された具体的画面・曲ID・実DBは未確認。

期待：端末に従う設定と英語の適否判定仕様を維持し、同期・再取得・時間経過で根拠の弱い名前が確定済み名称を上書きせず、応答欠落が既知の人物関係を消さない。既に欠落した状態からの再取得による回復も確認する。

## 対象と調査の区別

- 開始ソース：`3f1dbecf`。直前の条件名変更をユーザー依頼でコミットし、作業ツリーに差分がない状態から調査。
- metadata名の候補優先順位・7日cache、ArtistCreditRepositoryの再取得・認証cache、同期とDB関連の保存を独立に調査する。
- 以前の検証は起動直後・設定切替中心。正常なcredit JSONがある試験結果を、JSON欠損・古いcache・長時間経過・実アカウント同期の保証に拡張しない。
- 開発用Googleアカウントはユーザーの依頼で `.local/development-google.credential.xml` に Windows DPAPI 暗号化保存済み。現在のWindowsユーザーでのみ復号し、認証入力以外に出力しない。`.local/` はGit除外確認済み。パスワードを記録へ転記しない。
- 公式YouTube Musicの開発用アカウントで、日本語のアーティスト見出し・曲欄が「椎名林檎」となることを直接確認。公式ページ `https://music.youtube.com/@ringosheenaofficial`、ID `UCbrWU0y_rLsEOYgaTX5Y74A`。OuterTune内の実データで同じ応答を確認した事実とは区別する。

## 追加報告：アプリ内のログアウト・再ログイン

ユーザー確認：操作場所はOuterTuneアプリ内のアカウント設定。ログアウト後に発生したようにも見え、`Source error (2000): ログインしてボットではないことを確認してください` が再ログイン後も残る。

調査で判明した経路：認証3値の非同期保存・反映、ログアウト時の遅いvisitor取得が再ログイン値を上書きする競合、同じアカウントへ戻ったときの古い人物解決job/cacheの再利用。これらはコード上の欠陥候補であり、ユーザーの端末で2000エラーが発生した直接原因はまだ特定していない。

期待：ログイン・ログアウトを一つの認証状態として反映し、旧状態で開始した応答・再生URL・トークンを新状態へ持ち込まない。コンテンツ言語と既存の英語表記利用の意味は変えない。

## 観点と検証の対応表

前回は正常な保存データ・設定切替の確認に偏り、破損/旧cacheと認証の往復、遅延応答を組み合わせた試験が不足していた。以下を最終差分の検証結果へ対応付ける。全組合せの保証とはしない。

| 観点 | 境界・操作 | 検証対象 | 状態 |
| --- | --- | --- | --- |
| 取得元と鮮度 | 日本語detail → 1日後の英語一覧（ja応答） | MetadataDisplaySelection / MetadataNameCandidates / MetadataNameRepository | PASS（時計を進めた試験、実24時間放置は未確認） |
| 既存保存値 | 旧priority100一覧・7日cache・DB再open | MetadataNameRepository | PASS |
| 言語と英語チェック | 日本語選択、英語比較結果、チェックOFF | MetadataNameRepository、既存のoriginal判定試験 | PASS |
| 空・不完全な人物 | 旧言語の空RAW → 日本語COMPLETE | ArtistCreditTest / ArtistCreditRepository | PASS |
| 永続cacheと再取得制限 | 異言語cache＋将来のretry期限 | ArtistCreditRepository | PASS |
| 関係とIDの保全 | JSON欠損/破損＋空応答、正常な薄い再同期 | ArtistCreditDatabase | PASS |
| 認証の往復 | A → logout → A、各認証項目単独変化 | 人物/名前/album/画像repo、検索、YouTube認証試験 | PASS（合成認証情報） |
| 応答の到着順 | logout visitor取得保留 → login完了 → 旧取得完了 | AuthenticationRepositoryTest | PASS（応答の到着順を制御） |
| 再生の認証 | 旧URL/visitor/PoToken、取得中の認証変更、初期化失敗・timeout後の復旧 | player HTTP・URLcache・PlaybackSession・PoTokenSessionCache | PASS（ローカルHTTP/生成器の代替実装） |
| 実アカウント | 表記・再生 → logout/login → 表記・再生 → 再起動 | 最終APK | 未確認 |

## 再現候補と修正前の試験

1. 詳細ヘッダーで保存済みの日本語アーティスト名へ、同IDの一覧カードから英語名がrequestLocale=jaで到着する。ArtistItemが出所によらず同優先度なら、新しい一覧カードがja候補に選ばれる。チェックON/OFFとは別に、指定言語候補自体が入れ替わる疑い。
2. 曲と人物の関連はあるがartistCreditJsonがnull/破損または空RAWの行へ、欠落した再取得/同期応答が到着する。空artistsの保存が既存関連を削除する経路を調査中。
3. 空RAWの旧言語が保存されると、新言語の正常な応答が言語不一致として拒否され回復しない疑い。

## 合格条件

- 再現例を実際に失敗させ、通常の初期保存・同期・再取得のどの経路に原因があるか特定する。
- 日本語の確かな詳細名が、後から届く根拠の弱い一覧名で変わらない。適切な詳細更新と既存の英語表記利用は維持する。
- 応答欠落・失敗・旧cacheからの再取得で、既存人物の表示・関連・IDが失われない。正常な応答が戻れば回復し、再起動後も保持する。
- 最終ソースの関連試験と実画面を区別して記録し、ユーザーの実データと同一原因であることが未確認なら明記する。

## 結果

コード上で再現できた不具合を修正し、自動試験・配布ビルドを完了。ユーザーの実アカウント・実データで発生した原因が全て同じとは断定していない。

- 修正前：対象unit 49件中4件FAIL（一覧優先度3、空旧言語1）。Android 24件中6件FAIL（旧100一覧/翌日一覧2、空旧言語/異言語cache2、JSON欠損・破損と空応答の曲/album2）、正常系18件PASS。Androidにはその後追加した認証世代・snapshot更新試験はまだ含まれていなかった。
- 認証APIの途中検証：`account-auth-unit.log`、innertube近傍6クラス56件PASS、11秒。最終app/Android検証は別途記録する。
- 実サービスの対照：公式YouTube Musicで `4tlUwgtgdZA`（丸ノ内サディスティック）の再生位置6秒→57秒を確認して一時停止。プレーヤー表記は椎名林檎・無罪モラトリアム。OuterTune、Android上の配信、ログアウト往復の合格ではない。
- 保守上の残り：ArtistCreditRepositoryの旧世代のstate/album/source/loadedがプロセス終了まで残る。時間経過だけでは増えないが、大量の曲を複数世代で閲覧した場合のメモリ量は未測定。今回の症状の確定原因とは扱わない。

## DB保存境界の追加検証と修正

- 先に `ArtistCreditDatabaseTest` に、null/不正JSON＋既存日本語関連へ空RAWを渡す曲・アルバムの回帰試験と、日本語COMPLETEへ薄いプレイリスト応答を再挿入する対照試験を追加。修正前APKはルート担当がビルドし、端末での赤確認を実施する。
- 空RAWは未取得状態であり、人物が消えた根拠ではないため、曲・アルバムのapply入口で共通 `isEmptyByline()` 判定により保存を中止する。既存関連からCOMPLETEを推測して生成することはしない。
- お気に入り/ブックマークsnapshot更新でも、既存JSONがnullの場合に空placeholderを新たに保存しない。状態変更自体は適用し、関連・既存JSONは保持する追加試験を加えた。
- 現行online新規保存はJSONと関連を同一トランザクションで作るため、今回ユーザーの実DBにnull/不正JSON＋既存関連があったとは断定しない。結果・最終端末検証はルート担当が追記する。
- 修正前APKをルート担当が端末で実行し、曲・アルバムの空placeholder回帰2件のFAILと日本語COMPLETE対照のPASSを確認。snapshot更新部分は修正前APKに未収録のため、最終試験で別途確認する。

## 再ログイン経路の読取調査

- ユーザーからアプリ内logout/login後の可能性と、再ログインでも解消しないログイン要求付き再生source errorの報告を追加受領。認証値は出力しない。
- `App` のvisitor欠損時fallback取得は、再ログインで新しいvisitorを保存しても古い通信完了が無条件に上書き可能。認証3値の保存・メモリ反映も独立しているため、整合したsnapshotを使わない瞬間がある。
- DATASYNC_IDの成分はdelegated/userという役割を持つため、Loginの先頭選択と旧Appの保存値変換の違いだけをバグと断定しない。今回の新規loginは従来の先頭選択、既存保存値と高度編集は旧App互換変換を維持する。用途別の抜本再設計は実アカウント確認後とする。成分の参考：[yt-dlpの抽出処理](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py)。

## 認証保存集約の実装段階

- `AuthenticationRepository` が初期snapshotの同期適用、DataStoreの一括監視、ログイン3値の同一edit、logoutの即時反映を担当する。保存とYouTube原子適用を共通Mutexで保護し、古いflow値を再適用しない。
- visitor欠損時の補完はcollectLatestで取消し、通信が取消しを無視して完了しても保存前に取消し・snapshot一致・authRevision一致を検査する。
- `LoginScreen` は同じWebViewページから2設定値を1回のJavaScript評価で受け取り、cookieと一括保存・適用後にaccountInfoを取得する。プロフィールの保存にも世代検査を入れる。既存WebView cookieをアカウント忘却時に全消去する仕様変更は含めない。
- Appの3本の独立認証collectorとAccountFragの直接YouTube setterを集約。DATASYNC_IDは経路ごとの既存選択を維持し、null/空/文字列null/undefinedを共通除外する。認証snapshotのtoStringは値を出力しない。
- `AuthenticationRepositoryTest` 9件を追加：起動とloginの一括適用、取消しを無視した匿名visitor遅延応答、logout即時反映、同一アカウント再login後の旧プロフィール拒否、経路別互換、診断文字列の秘匿、visitor失敗後の監視継続、比較失敗時の再適用禁止、高度編集の一括保存。最終unitでPASS。実Googleログイン/実WebView操作成功を意味しない。

## 再ログイン時のPoToken生成器回復

- レビューで、再作成前にsession/revisionを更新していたため、factory失敗後に旧closed WebViewを再利用し得る経路、streaming生成失敗後に新generator＋旧streaming tokenが残る経路を確認。
- 実生成に使う `PoTokenSessionCache` はfactoryとstreaming tokenの両方成功後だけ状態を公開し、破棄時は先にcacheを空にする。単曲生成完了までMutexを保持し、別sessionの再作成が使用中generatorをcloseしない。暖機済みgenerator失敗時の1回再試行は反復処理とし、ロックへの再入をしない。
- PoToken公開APIをsuspend化。WebViewの終了は保留中要求を失敗完了し、初期化continuationは一度だけ完了、呼出し取消しで要求登録を除去する。破棄途中に例外が起きてもdestroyを試みる。
- `PoTokenSessionCacheTest` 7件を追加：factory失敗後の同一session再試行、streaming失敗時の新旧混在防止、close失敗後の再作成、並行session呼出し時の使用中close防止、取消し時の破棄、暖機済み生成器の1回再作成、初期化timeout後のロック解放と次回復旧。生成器全体は30秒でtimeoutし、WebView callback欠落が全曲を無期限に止めないよう通常fallbackへ戻す。実WebView通信成功やユーザー報告の同一原因を断定する試験ではない。

## 最終差分の検証結果と成果物

対象は `3f1dbecf` に今回の未コミット差分を加えたソース。DB schema/version変更なし、既存データ削除なし。認証と名前の既存設定の意味は維持。

| 検証 | 結果 | 範囲・限界 |
| --- | --- | --- |
| `:app:testCoreDebugUnitTest` | 343件PASS | 認証保存、名前選択、検索、再生URL・トークンの失敗/取消し/到着順を含む |
| `:innertube:test` | 73件PASS、既存13件SKIPPED | 実HTTP形式はローカルサーバーで合成認証情報を使用 |
| Android全体の初回 | 83件PASS、4件FAIL、2件SKIPPED、93.2秒 | FAILはSearchSessionNavigationTestのwindow focus待ち。SKIPはopt-inの実通信と手動システム言語変更 |
| 検索UIの切り分け | 同APK単独でも4件FAIL。その後同APK・同試験で4件PASS、14.0秒 | `dumpsys window`で通知パネルがfocus所有と確認。パネルを閉じた後に合格。コード/待ち条件の変更なし |
| Android実通信を明示実行 | 1件PASS、4.68秒 | `LivePlaybackProbeTest`。椎名林檎の日本語見出し取得と `4tlUwgtgdZA` の配信音声先頭4096byte読取。ログイン済み実アカウントの往復や長時間音声継続の証明ではない |
| coreDebug、AndroidTest、coreReleaseビルド | PASS、4分5秒 | arm64のみ。第三者Compose mapping収集の警告あり、ビルド・必須lintは成功 |
| 最終coreRelease | 署名検証・arm64確認・上書きインストール・cold start PASS | 初期設定画面の描画とプロセス稼働を確認。実アカウントログイン後のrelease主要画面は未確認 |

Androidは再試行・明示実行を含め88件の成功を確認。真の端末言語を操作中に変更する1件は今回未実施であり、アプリ単独言語変更・保存コンテンツ言語変更の実Android試験は合格している。初回FAILを消して一括成功とは扱わない。

実行記録はGit対象外の `build/artist-auth-final-build.log`、`build/artist-auth-final-android.log`、`build/artist-auth-search-ui-isolated.log`、`build/artist-auth-search-ui-focus.log`、`build/artist-auth-live-android.log`。実通信ログには資格情報や配信URLを出力しない。

配布ファイル：`build/distributions/OuterTune-account-recovery-20260912-arm64.apk`（core-release、arm64-v8a、9,090,162 bytes）。SHA-256: `8d261a9e73c9ba8dd4a053d48b729d2aaaa8295ea030b18e0a04e7124ee0fe16`。APKはGit除外確認済み。こちらが起動したread-onlyエミュレータは検証終了後に停止。

未確認の中心条件：ユーザー端末の実データを保った状態で、日本語・英語表記利用ON → OuterTune内でlogout/login → 椎名林檎と該当albumの表示・再生 → アプリ再起動 → 放置後の再確認。Source error(2000)のユーザー環境での解消はこの確認まで完了扱いにしない。今回の自動試験で証明できた欠陥修正と、報告された全症状の解決を区別する。

## ユーザー提供スクリーンショットの照合と追加診断

- 2026-09-12 17:40のスクリーンショット2枚を受領。ユーザーはWindowsスマートフォン連携を中止したため、以後は端末接続・画面操作を行わず画像を解析。
- 画像内のR8 map ID `0c1cffd2077a857e4f0bf893606caf0468a27e2823af79438b010173b1f07c8b` が上記配布版のmappingと完全一致。今回の修正版のビルドで同じ再生エラーが残ることを確認。端末上のAPKバイナリハッシュを直接取得したわけではない。
- mappingとAPK、画像から転記したstack、Retrace結果をGit除外の `build/diagnostics/playback-20260912/` に保全。SDK 36.1.0のRetraceで復元した発生箇所は `YTPlayerUtils.resolvePlayback(YTPlayerUtils.kt:224)`。Media3のSource error/UnexpectedLoaderExceptionの内側でアプリが投げたPlaybackException。
- この行は全取得候補の試行後、最後のIOS応答の理由を投げる箇所。IOSは認証非対応設定で、アプリにログイン済みでもCookie/Authorizationを送らない。したがってこの「ログインしてbotではないことを確認」は、アプリのGoogle認証が無効という証拠ではない。前段のWEB_REMIX等が失敗した理由は画像に残っていない。
- 画像の曲名は「丸ノ内サディスティック」、アーティストは「椎名林檎」と表示。他画面の名前・アルバムの問題が解消した証拠とはしない。
- 確認した診断上の欠陥：候補ごとの例外をgetOrNullで失い、HTTP検証の失敗をBooleanへ潰し、最後の応答だけを表示。releaseではLog.dが除去されるため、その情報を後から端末ログで復元できない。
- 今回の追加範囲：既存の候補順・認証・再試行条件を維持し、各候補の失敗段階・HTTP番号・認証ヘッダー使用有無・トークン取得有無だけを、展開/コピーできるエラー詳細へ残す。認証値、トークン、配信URL、HTTP本文、任意の例外メッセージは診断へ渡さない。診断追加を再生不具合の解消とは扱わない。
- 合格条件：前段の認証付き候補の失敗が最後の匿名候補で消えない。診断は有限件・不変snapshotで、秘密値を含む入力のメッセージ/原因を漏らさない。関連単体試験とcore-releaseビルドで確認し、実端末の詳細取得は次の確認事項とする。

### 追加診断の実装・最終確認

- `PlaybackDiagnostics` は7候補分を上限に、固定候補名・失敗段階・固定status・HTTP数値・itag・boolean・例外種別のみを保存。元例外をcause/suppressedへ結び付けず、不変文字列にする。HTTP/JSON例外は型判定でrelease難読化後も分類する。API拒否のHTTP番号と、音声URLのHEAD検証番号を区別して残す。
- `cookieHeaderConfigured` はCookie設定の有無であり、有効なログインの証明ではない。`authorizationAvailable` は実際の認証ヘッダー生成関数を共有し、生成可否だけを返す。cookie/visitor/sessionの中身、トークン、配信URL、応答本文は追加診断に入れない。元から存在する例外本文全般を消毒した変更ではない。
- `YTPlayerUtils` の失敗例外へ診断を付加。主候補の通信例外は従来の分類を保つためsuppressedへ付け、最終失敗はcauseへ付ける。既存の詳細表示・タップして全文コピーから取得可能。取得候補順、認証送信条件、HEAD実行条件、format選択、認証変更時の再試行は維持。取消しは伝播する。
- 初回：app19件＋innertube18件PASS、core-release成功（2分12秒）。追加HTTP試験ではAndroidのunitコンパイル環境にJDK専用HttpServerがないためコンパイルFAIL。同実行内のrelease作成は成功したが、全体成功とは扱わない（1分46秒）。試験用サーバーをloopbackのServerSocketへ変更し、再実行。
- 最終：app21件PASS（PlaybackDiagnostics7、PlaybackSession3、PlaybackFormats4、PlaybackUrlCache7）。innertube18件PASS（AuthenticationSnapshot4、CookieAuth8、PlayerAuthentication6）はその後対象ソース変更なし。計39件。401/403/429の実ローカルHTTP応答から番号を保持し、秘密値を含む本文・URL・例外原因が追加診断へ入らないことを確認。最終Gradleは9秒、core-release/必須lint成功、APKは最終runtimeソースに対してup-to-date。
- ログ：`build/playback-diagnostics-build.log`、`build/playback-diagnostics-final-build.log`（途中の試験コンパイル失敗を含む）、`build/playback-diagnostics-verified-build.log`。差分の空白検査PASS。
- 配布APK：`build/distributions/OuterTune-playback-diagnostics-20260912-arm64.apk`。core-release、arm64-v8aのみ、9,090,162 bytes、SHA-256 `3fc267c817037a30a4e2eecea1dd952eb4cbe7bc1d7432ccff7054f2cea44373`。v2署名検証成功。パッケージ・versionCode・署名証明書は前の配布版と一致。縮小後DEXに診断見出しとCookie設定フィールドが残ることを確認。端末への上書きインストール自体は未実施。
- この診断版のmap IDは `2b2b04e866d9251c2d0e24fbc5b018d4c180c3aec9134c57532f9e55249a4fa3`。対応mappingは `build/diagnostics/playback-20260912/diagnostic-mapping.txt` に保全。元の画像に一致する旧mappingは別名で維持。
- 残る確認：ユーザーが診断版で該当曲を再生し、エラー詳細の全文を取得。実端末の前段の認証付き取得が失敗する直接原因・再生復旧・名前問題との共通原因は未確定。今回は診断欠落を修正した段階であり、Source errorの解決完了とはしない。追加差分は未コミット。

### 診断版からの実端末報告：ログアウト中

- ユーザーが診断版の全文を提供。Samsung SM-S931Z、Android API 36、core-release 0.10.2-b1 (71)、map IDは診断版の `2b2b04e866d9251c2d0e24fbc5b018d4c180c3aec9134c57532f9e55249a4fa3` と一致。
- **追加確認の回答は「ログアウト状態だった」**。cookiePresent=false、authorizationAvailable=false、全候補cookieHeaderConfigured=falseはこの状態と整合する。今回のログをCookieの消失・ログイン保存失敗の証拠として扱わない。最初の調査方針をこの回答に従って訂正。
- visitorPresent=true、sessionPresent=true、authRevision=2、requiredItag=NONE。poTokenAvailable=true、signatureTimestampAvailable=true。生成結果は得られているが、YouTubeがトークンを有効と判定したことはこのbooleanでは分からない。
- ANDROID_VR、VISIONOS、WEB_REMIX、ANDROID、IOSの5候補がすべてstage=PLAYABILITY、status=LOGIN_REQUIRED。TVHTML5とTVHTML5_SIMPLY_EMBEDDED_PLAYERはログアウト条件によりSKIPPED_LOGIN。全7候補の結果が省略なく取得できた。
- これはAPI応答を受け取った後、再生可否の判定で拒否されたケース。音声format選択・音声URLのHTTP検証・デコードまで到達していない。http=NONEはこの段階でHTTP番号を記録しないことを示し、「通信していない」や「HTTPが失敗した」の意味ではない。
- このログで確定できるのは当該端末・当該リクエスト群の匿名再生にログインが要求されたことまで。全環境での匿名再生不可、アカウントの有効性、ネットワーク/IP制限、以前の再ログイン後の報告との同一原因は未確定。
- 次の比較：同じ診断版・同じ曲・同じ通信環境でOuterTune内へログインし、アカウント設定へ戻った状態と再生結果を確認。失敗した場合は新しいエラー詳細を取得し、認証付き候補のCookie設定・Authorization生成可否・失敗段階を今回のログと比較する。現段階ではこのログを理由に実装・ビルドを追加しない。

### ユーザー確認による再生エラー調査の終了

- ユーザー報告：OuterTune内へログインすると再生が復旧し、それ以降アプリを再インストールしても同様のエラーは再発しなかった。これはユーザーによる実端末確認であり、こちらの端末で直接再試験した結果ではない。
- ユーザーは端末内キャッシュ等の影響を推測し、この再生エラーへの追加対応は不要と判断。指示に従い追加実装・追加再現調査は行わず、ここまでの修正・診断・検証記録をコミットする。
- 確定事項はログイン後の復旧と、その後の再インストールでの非再発。端末キャッシュが直接原因だったと証明したものではない。アーティスト名・アルバム名の問題まで同一原因で解決したとの結論にも拡張しない。
- 今回のコミット作業では実行コードの変更なし。既存の最終検証結果を再利用し、記録・差分・コミット対象を確認する。APK・生ログ・対応mapping・ローカルの資格情報ファイルはGit対象外のまま保全する。

## 追加報告：曲のライブラリ操作と仕様の再検討

- 開始点 `67e69d4d`、作業ツリーは変更なし。ユーザーは、アルバムのアーティスト名は正しい一方、同期したアーティストの曲をライブラリへ追加・削除するなどの操作中、曲だけ不明になると報告。曲名・画面の指定は「特に指定はない」。実端末DB・具体的操作列は未取得。
- 読取調査中にユーザーが仕様変更を検討すると表明したため、実装は保留。再生エラーの終了判断は維持し、ここでは曲アーティストの表示だけを扱う。
- コード上で確認した状態差：`Song.toMediaMetadata()` はcredit JSONがあればその空artistsも優先するため、人物関連が別に残っていても表示へ使われない。`applyArtistCredit()` は非空RAW＋既存JSONなし/破損＋既知関連ありの場合、前回の空RAW保護を通り関連を削除し得る。ただし今回の通常操作でその前提状態が生じた証拠はなく、報告の直接原因とは断定しない。
- 通常のCOMPLETEデータについて、今回読んだライブラリ切替・同期更新は状態列の変更と既存credit保護を行っており、単純に毎回関連を削除しているわけではない。アルバム見出しと曲のbylineは別情報として解析・保存され、片方の正常表示はもう片方の正常性を保証しない。
- 表示側の追加確認：同じ空RAW＋既知関連でも `Song.artistDisplayText()` は名前へfallbackし、`Song.toMediaMetadata()` 経由は空一覧になるため、同じ保存曲でも経路差がある。またプレイヤーのmetadata/キューはDBと別snapshotで、MusicServiceのcurrentSong監視だけではタグを更新しない。DB修復後にArtistCreditRepositoryの更新通知が発生しない場合、プレイヤーへ修復結果が反映されない候補がある。どちらも実端末での今回症状を再現した結果ではない。
- 未決定の簡素化案：曲の保存済み表記を表示の基準とし、人物リンクの補完と分離する。同期・ライブラリ操作による再解釈を制限し、欠損補完や明示更新に範囲を絞る。英語＋指定言語の保存、端末に従う優先順位、既存の英語表記利用の意味は無断変更しない。仕様決定前に修正・試験・ビルドは行わない。

### USB接続による実端末の確認

- ユーザーが原因特定を先に行う方針とし、USBデバッグでSamsung SM-S931Zを接続。ADBはdevice認識、OuterTuneは起動・前面表示。配布版のためrun-asはnot debuggableと返り、アプリのprivate DBは直接読めない。
- ユーザーが「不明」表示を出せると回答。画面を読み取り、「Last-resort」、アーティスト欄「アーティスト不明」、再生位置0:07/3:26で一時停止中を直接確認。画像はGit除外の `build/diagnostics/device-artist-current.png` に保全。端末への入力・アプリの終了・再インストール・データ変更は行っていない。
- アプリPIDに限定したSyncUtils等の既存ログ31行をメモリ内で調べ、固定イベント名の件数だけを出力。同期失敗記録3件を検出したが、名前欠損との因果関係は未確認。ログ原文・認証値・URLは保存していない。名前関連repositoryに診断ログがなく、過去の更新経路はここから復元できない。
- 既存の「アーティスト情報」は開くと優先再取得を行うため、初期状態の確認には使わない。標準バックアップはDBとアカウント設定を含む一方、補完用SharedPreferencesは含まない。保存データの保全には利用できるが、解析対象は曲DBに限定し、設定ファイルは抽出・表示しない方針。ユーザーによる端末内バックアップ作成後、USB経由でDBを読み取り、対象曲のJSON・人物関連・アルバムとの対応を比較する。

### 実バックアップと公開応答による原因の絞り込み

- ユーザー提供 `OuterTune_24_20260912190305.backup` から `song.db` だけをGit除外の `build/diagnostics/artist-20260912/reported-song.db` へ抽出。`settings.preferences_pb` は抽出・内容読取をしていない。DBはSQLiteの `mode=ro` と `query_only=ON` で確認。schema 24、`quick_check=ok`。DBのSHA-256は `a48c15b2a3eee7d807e9d77cd232561b8b3103ab0d71b17b9683df2dfbba65cd`。
- 接続端末のインストール済みAPKを読み取り、SHA-256 `3fc267c817037a30a4e2eecea1dd952eb4cbe7bc1d7432ccff7054f2cea44373` が前述の診断配布版と一致。現在のruntimeソースは `67e69d4d` のまま。今回の調査で端末アプリのインストール・再起動・データ削除は行っていない。
- **実DBの対象曲**：`Ohf-kbf6cR4` / Last-resort。曲creditは `RAW`、rawText空、artists空、source `AlbumPage`、language `ja`、evidenceに `video-source:MUSIC_VIDEO_TYPE_OMV`。曲と人物の関連も0件。対応するアルバム `MPREb_D37btAezO0h` / Triggerには、`COMPLETE` の「天音かなた」と人物ID `UCPCiIrrrNJOKvi_5vr3G6PA`、アルバムと人物の関連が正常にある。
- **集計**：オンライン曲204件のうちCOMPLETE 73件、非空RAW 97件、空RAW 34件。空RAW 34件はすべてAlbumPage由来・OMVで、対応アルバムにはCOMPLETE creditと人物関連がある。全曲のJSON破損・JSONと人物関連の食い違いは確認されなかった。したがって先の「関連が残っているのにJSONが隠す」「修復済みDBとプレイヤーが食い違う」という候補は、このバックアップの対象曲を説明しない。
- 空RAW 34曲は `inLibrary=null`、`liked=0`。これはダウンロード曲数・ユーザーが登録した曲数ではない。`DatabaseDao.upsert(albumPage)` はアルバム内の曲を一括保存し、オンライン曲の新規作成時はライブラリ所属を付けない。アルバム表示・不足アルバム取得などでもこの保存経路を通るため、同期や削除の履歴をこの件数から推測しない。音声のダウンロードと表示用metadataの保存は別であり、未ダウンロード自体は名前を不明にする条件ではない。
- **現在の公開応答との比較**：PCから、アプリと同じWEB_REMIX版・ja/JPで対象アルバムの `browse` と対象曲の `music/get_queue` を各1回成功取得。Cookie、保存設定、ユーザー認証は使用していない。アルバム一覧のLast-resort行は第1列が曲名、第2列が空、第3列が再生回数。曲名と再生ボタンの両endpointがOMVを明示しており、曲アーティスト名はこの一覧行に存在しない。一方、同じ動画IDの個別queue応答には「天音かなた」とARTIST型の上記IDが存在する。取得日時点の匿名応答との照合であり、過去のログイン済み端末応答を復元したものではない。
- **コードで確認できる連鎖**：`PageHelper.artistRuns` が空の第2列を読み、`AlbumPage.getSong` が空RAWを作る。アルバム見出しは曲の出演者と同一とは限らないため自動流用せず、新規SongEntityへ空RAWが保存される。次に `ArtistCreditRepository.needsResolution` がvideo-source付きcreditを除外し、`YouTube.resolveTrackArtistCredit` にもqueue取得より前の同じ終了条件がある。その結果、この曲の個別応答で取得可能な名前も取りに行かず、不明表示が維持される。
- 動画補完を別課題にしていた仕様が除外の背景。ただしアルバム一覧にもOMVの曲が含まれ、表示名の欠落回復まで除外する結果になっている。既存 `AlbumBrowsingParsingTest` には別アルバムの「曲名あり・第2列空・OMVを含む」実fixtureと曲artists全件空の期待値があるが、保存→再表示→個別取得による回復は確認していなかった。今回の見落としは、その工程間の確認不足として扱う。
- 確定範囲は「対象曲が不明のまま残る保存状態と取得除外条件」、および同じ入力形を現在の公開応答で確認できたこと。以前は名前が存在して消えたのか、ライブラリ削除や同期失敗が直接引き金だったのかは、履歴のないDBから断定できない。全34曲の個別API取得・全報告症状の同一原因確認はしていない。
- 調査成果物：同ディレクトリの `target-database-initial.json`、`album-public-columns.json`、`queue-public-byline.json`。公開応答は曲名・byline・識別子・種別など比較に必要な項目だけ抽出し、応答全文やvisitor情報は保存していない。PCの標準制限下では最初の公開通信が失敗したため、同じ読取専用スクリプトを承認済みのネットワーク実行で成功確認した。
- 今回は原因調査のみ。実装・ビルド・端末DB修正は未実施。最小の修正候補は「動画扱いでも空の曲bylineは同一動画IDの基本情報で回復できる」よう、表示名取得と深い人物補完の除外を分けること。アルバムの人物を曲へ一律コピーしたり、ダウンロードを必須にしたりする変更は原因への対処として不要。次に実装する場合はこの実データ形を起点に、保存から表示・再要求・再起動までの回帰確認を行う。

### 空の動画曲bylineを回復する修正

- ユーザーが修正済みかとAPKの場所を確認。前段が原因調査までだったことを説明し、今回判明した取得除外条件の修正とAPK作成へ進む。
- 範囲：空RAWの曲bylineだけ、動画種別が付いていても同一動画IDのqueue応答による取得を許す。元入力が動画なら取得後も人物ページ・演奏者クレジットへの探索は行わない。非空の動画表記、採用済み人物、既知アルバム、言語/認証の世代保護、失敗時の再試行間隔を維持。DB schema変更なし。
- 合格条件：実例と同じ空AlbumPage/OMVから個別名・IDを回復する。別ID・空応答・失敗で人物を作らない。アルバム見出しの人物を借用しない。曲と人物の関連・表示用metadata・保存後の再読み込みが一致する。再生中の人物metadata更新で音声・URI・再生位置を維持する。
- 修正前のinnertube試験：`ArtistCreditTest` 39件中5件FAIL、34件PASS。追加した空OMV回復・endpointのみ動画・空/別ID/重複ID応答・通信失敗・取消の5件が旧早期returnで失敗し、非空動画等の保護対照は成功。`build/artist-byline-innertube-red.log`、Gradle7秒。
- 検証用Pixel_9_API_35をポート5556、read-only/no-window/no-audio/no-snapshot-saveで起動。こちらが操作担当であることを案内し、接続中のユーザースマホは通常利用可能と伝えた。スマホを検証用に初期化・上書きインストールしない。
- 修正前のapp保存境界：実AlbumPage importを用いるAndroid回帰2件を実行し、空OMVの取得回数が期待1に対して0でFAIL、既存非空動画の取得除外はPASS。`build/artist-byline-repository-red-android.log`、試験0.311秒、テストAPK作成1分11秒。innertube側の修正だけではこのapp入口を通れないことを確認後、`needsResolution`も空RAWに限って取得を許すよう修正。
- 修正後のinnertubeモジュール：80件PASS、既存の明示通信13件SKIPPED。前述の赤5件は全件PASS。`build/artist-byline-innertube-final.log`、Gradle10秒。app・端末の最終結果は別途追記する。

### 空の動画曲byline修正の最終確認・配布APK

対象ソースは `67e69d4d` に今回の未コミット差分を加えたもの。本体変更は `ArtistCreditRepository.needsResolution` と `YouTube.resolveTrackArtistCredit` の取得条件。DB schema/version、言語設定、再生用URLの取得処理は変更なし。

| 確認 | 結果 | 範囲・限界 |
| --- | --- | --- |
| appのArtist関連unit | 26件PASS | `:app:testCoreDebugUnitTest --tests '*Artist*'` |
| innertube unit | 80件PASS、既存13件SKIPPED | 空OMV、endpointだけの動画、別ID/重複ID/空応答、通信失敗、取消、非空動画保護を含む |
| Android Repository/DB/再生継続 | 25件PASS、7.305秒 | 実AlbumPage保存から個別取得・人物関連・既知album保持・再読込、空artistから名前付きtagへ置換しても音声/位置/URI/cache keyを保持 |
| 対象曲の明示実通信 | 1件PASS、2.436秒 | ja/JP・匿名の実album応答からLast-resortの空OMVを保存→productionの個別queue取得で天音かなたとIDを回復→表示用metadata一致→専用cacheを空にしてファイルDBを閉じ/開き、保持を確認 |
| debug/AndroidTest/core-releaseビルド | PASS、2分53秒 | 最終本体・試験に対するビルド。必須lint成功。従来と同じ第三者Compose mapping収集警告あり |
| 配布APKの署名・ABI・起動 | PASS | v2署名、arm64-v8aのみ。検証エミュレータへインストールしcold startと初期設定/ホーム描画を確認 |
| 配布APKでの対象曲の実画面 | 未完了 | releaseの通信はSSLHandshakeException/CertPathValidatorExceptionで拒否され、album/曲URLから対象表示へ到達できなかった。起動成功だけを曲表示のPASSとは扱わない |

- 合計132件PASS、既存13件SKIPPED。修正前のFAIL記録は上記のまま保持する。最終ビルド後に本体・試験ソースの追加変更なし。
- 実通信試験は使い捨ての検証エミュレータ上のdebugアプリだけで実行。専用Room/SharedPreferences/scopeを使用して片付け、言語を復元した。ただしproduction queueのmetadata通知は通常appの名前保存先にも届き得るため、完全なアプリ隔離とは称さない。ユーザーのバックアップ・設定・認証値を試験へ投入せず、接続中のスマホは変更していない。
- releaseの通信制約は、debug専用network security設定との違いを含む検証環境上の未確認事項として残す。証明書の検証を弱める本体変更は加えない。確認した例外は型名・件数だけで、ログ原文やURL・認証値は保存していない。日本語実応答での名前回復は前述のdebug実通信試験で確認したもので、release実画面やユーザー端末で同じ結果を直接確認したとは扱わない。
- 実行ログ：`build/artist-byline-final-build.log`、`build/artist-byline-final-android.log`、`build/artist-byline-live-android.log`。release画面は `build/diagnostics/artist-20260912/release-current.png`。いずれもGit対象外。
- 配布APK：`build/distributions/OuterTune-artist-byline-recovery-20260912-arm64.apk`、core-release / arm64-v8a、0.10.2-b1 (71)、9,090,162 bytes。SHA-256 `df68515044a8d80ae1d39552c31859e8fee074ef46368c445505c0ee65c036a8`。署名証明書は従来配布版と同じ。対応mappingは `build/diagnostics/artist-20260912/byline-recovery-mapping.txt` に保全。
- 残るユーザー環境確認：このAPKを上書き後、既存の不明曲を表示して名前が回復するか、ライブラリ追加/削除・アプリ再起動後も保持されるかを確認する。既存の再試行待ち時間は維持している。今回の対象曲の回復確認と、過去の同期/削除で名前が消えた原因の特定は別であり、後者を解決済みに広げない。
- debug専用設定は、このPCのAvast通信検査用証明書を追加していることを確認。releaseにはこの証明書を追加していない。今回の配布版で通信確認できなかった環境差の根拠として記録する。
- 検証用エミュレータを終了。APKと実データ・mappingのGit除外、最終差分の空白検査を確認済み。今回の修正は未コミット。
