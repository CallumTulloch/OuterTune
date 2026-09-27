# 2026-09-19: 英語原表記ONでもアルバム収録曲がカタカナになる

**2026-09-20追記: 初回修正は実機の通常のアルバム一覧で不合格。ユーザーの指定により、以後は取得済み実機DBをエミュレータへ再現して改修・検証した。最終版では先頭を含む3曲のON/OFFを同じIDで確認したが、Breed等は未解決。全曲解消とは扱わない。末尾の最終確認と残件を優先して読む。**

## 依頼・範囲

- ユーザー報告: 曲検索は英語なのにアルバムではカタカナ。例はNirvana「Smells Like Teen Spirit」。英語原表記設定はON。
- 対象はアルバム収録曲。任意の海外作品での再現確認を許可され、既存DBの保持も不要と明示された。
- 調査のみ。本体・テストコード・DB schemaの変更、新規ビルドは行っていない。以前の課題の未コミット差分を維持。
- 仕様の所在への質問に対応し、過去の合意と現在の実装を[仕様整理](../../metadata-language-spec.md)に集約。新たな表示条件の合意・実装ではない。

## 結論と限界

**初期化したdebugアプリでも、英語原表記ONでNevermindとAbbey Roadのアルバム収録曲がカタカナになることを確認。古いDBやMVの混入を必要としない。**

今回の環境では、曲詳細のgetQueueが空応答となり、Art Trackの種別を得られず、Main原題取得・英語判定へ進めない。ブックマーク後には英語アルバムAPIから同じ曲IDの英語名を取得できたが、これは名称候補であり原題の証拠にはならない。ONでも `ORIGINAL_UNCONFIRMED` により日本語が選ばれる。

ただし匿名の検証環境では対象のアルバム曲がすべて再生制限表示になり、Nirvanaの曲フィルター検索は0件。このためユーザーの「検索では英語」という一対の画面差を同条件で再現したとは扱わない。実機はADB未接続で、現在の曲ID・判定・取得状態は未確認。今回の取得制限と、前課題で修正した再生履歴によるDBデッドロックも別の現象。

## 新規DBによる実測

- 対象ソース: HEAD `14e73317` と既存未コミット差分。
- 既存coreDebug / arm64-v8a、0.10.2-b1 (71)。インストール済みAPKと既存ビルド出力のSHA-256一致: `e0bad32d6ab5bd648d0e1ae81e0cef21beba38e9dd43788eb743792ebfaf13ef`。
- Pixel_9_API_35 / emulator-5556、Android 15、x86_64上のarm64実行対応。
- debugを初期化し、Welcomeをスキップ。UIから英語原表記ON・コンテンツ日本語を設定。checked=trueをXMLで確認。初回は国US、追加で国JPへ変更し同じ結果を確認。
- 検索のアルバム結果から開き、ブックマーク前後の画面・DBを採取。提供バックアップは投入していない。

| 対象 | Nevermind | Abbey Road (Super Deluxe Edition) |
| --- | --- | --- |
| album ID | `MPREb_jPOYfjGgApr` | `MPREb_tQfaWH32ovE` |
| 正式曲数 | 13 | 40 |
| 先頭の曲ID | `ljUtuoFt-8c` | `wqaKHHxQFZc` |
| 日本語名 | スメルズ・ライク・ティーン・スピリット | カム・トゥゲザー (2019 Mix) |
| 保存後の英語名 | Smells Like Teen Spirit | Come Together (2019 Mix) |
| 日英名称候補のある曲数 | 各13 | 各40 |
| 原題und / 判定 | 0 / 0 | 0 / 0 |
| JPの曲詳細取得 | 日英26状態すべてEMPTY | 日英80状態すべてEMPTY |
| アルバム先頭の表示 | カタカナ | カタカナ |

NevermindはUSでの日英26状態もEMPTY。日本語候補のsourceは `album`、英語候補は `album-original-context`。英語候補のoriginEvidenceJsonはnull。

- [Nevermind](../images/2026-09-19-album-song-language/nevermind-ja.png): 英語名取得後もカタカナ。
- [Abbey Road](../images/2026-09-19-album-song-language/abbey-ja.png): 別作品の初回表示でもカタカナ。後続DB採取で40曲の英語候補を確認。
- 設定・画面XML、DBコピー・抽出JSON、APKハッシュ: 無視対象 `build/diagnostics/album-song-language-20260919/`。最終DBのintegrity_checkはok。
- 実機は操作していない。検証後はdebugを停止してエミュレータを終了し、操作制限を解除。

## コードとの照合

1. `AlbumViewModel.load` はコンテンツ言語のアルバムを取得し、曲とIDを保存。`YouTube.album` のobserverが各曲の名前補完を予約する。
2. `MetadataNameRepository.saveMusicResult` は英語getQueue応答で `MUSIC_VIDEO_TYPE_ATV` を確認した場合に原題取得を開始。今回のEMPTYでは進まない。
3. `captureAlbumOriginals` は保存対象アルバムの英語曲目を名称候補として保存。英語名が存在するだけでは配信原題の証拠を作らない。
4. `latestOriginalRows` / `originalCandidate` はund + `art-track-original:` の有効な原題を分類。通常の英語アルバム候補は対象外。
5. `OriginalNamePolicy.select` は英語原題判定が無ければ、ONでも `ORIGINAL_UNCONFIRMED` として設定言語を採用。今回のDB状態から表示を説明できる。
6. EMPTYの再取得間隔は24時間。ブックマーク後の再予約も `due` で省略され、新しい英語名が入っても原題確認は復旧しない。

曲検索のSongItem.displayTitleとアルバムのSongEntity.displayTitleは同じIDの共通表示マップを読む。ただしマップにIDが無い場合は各画面の元タイトルへ戻るため、「同IDなら必ず同じ表示」とまでは言えない。

実測していない別経路として、同IDでも補完前に元タイトルが違う場合の表示差、原題再取得で判定なしJSONが保存され再分類まで一時失効する経路も確認。今回の53曲は原題自体が未取得であり、モデルの誤判定とは区別する。

## 過去DBによる説明の訂正

初回は提供DBの同名2 IDの評価差をユーザー画面の原因として説明したが、現画面IDを未確認のまま断定しており撤回した。

- 旧DBには `ljUtuoFt-8c` と `hTWKbfoikeg` のindex=0があり、前者だけ英語原題判定がある。前のアルバム重複課題の資料であり、現在の画面の証明ではない。
- 前回修復DBの名称関連3テーブルは元DBと全行一致。前回release画像・XMLでは先頭が英語。全体は8曲に英語判定、4曲は名称未取得でカタカナ、末曲は元タイトル自体が英語だった。
- 今回の新規DBでは `ljUtuoFt-8c` 自体がカタカナになった。MVの選択は原因の必要条件ではない。

## 継続改修の判断材料（未実装）

- 原題確認を曲詳細取得の成功だけに依存させず、取得不可でも正規アルバム収録曲の確認を復旧できる経路を検討する。
- 一時的な空応答で24時間止まる扱い、アルバムを明示的に開いた場合の再試行、原題が不変な再取得での判定保持を検討する。
- 英語候補があるだけで一律に英語採用する変更は「正式な原表記が英語なら」の意味を変えるため、取得経路の修復と区別する。
- 新規/保存済みDB、同一曲ID、取得成功/空応答/復旧、ON/OFF、検索とアルバムの往復を合格確認に含める。今回の調査だけで問題解消とは扱わない。

## 承認後の改修（2026-09-20、実機調査継続中）

ユーザーの「ここまでの内容を一度コミットした後、改修」により、従来のUI・アルバム・再生履歴修正と本調査を `7e48d375` にコミットし、作業ツリーが空であることを確認してから以下に着手。pushは行っていない。

- 実際に開いた正規アルバムと保存対象アルバムについて、英語の正規曲目から同じ曲IDのMain原情報を直接照合する。検索結果の関連アルバムやキューの仮行を全件展開しない。
- 新しいアルバム専用経路は、同一ID、配信元・自動生成を示す構造、Main原題とクレジット行の一致を必須にする。明示的な非Art Trackや別IDは拒否。配信者チャンネル名から人物IDを作らない。
- 新たな匿名Main応答で、Nevermindの先頭原題を確認。Abbey Roadの先頭も原題を取得できるが、descriptionの版名は「Abbey Road」で、今回のページ名「Abbey Road (Super Deluxe Edition)」とは異なる。曲の収録関係は正規ページのIDを根拠にし、曲原題にはMainを使う。アルバム名そのものは全文一致時だけ候補を生成する。
- 英語候補を原題扱いせず、得たMain原題群を既存の言語判定へ渡す。ON/OFFの意味・モデル閾値・ID・rawタイトルは変更しない。
- 曲のEMPTYは5分で再試行可能にする。アルバム補完は各曲の原題確認まで完了してからSUCCESSにし、途中失敗を7日間の成功扱いにしない。旧アルバム補完の成功cacheは新しいv2キーで再確認する。
- 通常の原題補完とアルバム補完が同じ曲を扱う際は、固定数のmutexで取得・保存を直列化する。
- 原題再取得時は、完全な取得元snapshot・全言語評価入力・判定器が同一と確認できる場合だけ評価JSONを引き継ぐ。原題・関連・文脈の変更や消失、旧形式・旧判定器は再評価する。DB schema変更なし。

### ここまでの確認範囲

- `7e48d375` 後の未コミット差分。DB28のまま、schema変更なし。
- 単体テスト101件PASS。対象は `models.metadata.*`、`repositories.Metadata*`、`OriginalAssessmentRetentionTest`。
- Android 25件PASS（8.634秒）: `AlbumOriginalRecoveryTest` 8、`OriginalAssessmentRetentionDeviceTest` 1、`MetadataNameRepositoryTest` 7、`MetadataLanguageIntegrationTest` 1、`MetadataNamesDatabaseTest` 7、`PlaybackMetadataContinuityTest` 1。
- 最初の24件実行では既存2件が失敗。Integrationが実アプリDBにINSERT IGNOREでfixtureを挿入し、既存の未保存曲を保存済みにできなかったこと、および検索結果の期待が旧raw artist IDだったことを確認。Integrationは独立Room・設定・通信runtimeへ分離し、別名検索と実分類器の検証を保持。検索期待は現行代表IDに変更しraw ID/関連の保持も追加検証した。製品検索処理の変更ではない。
- 別版で同一動画を共有するケースを追加。既にアルバム文脈を確認済みの動画は、別版を開くだけでは取得済み文脈を奪わない。TTL内の強化はalbumIdが無い場合のみ。
- 上記最終ソースのdebug・test・coreReleaseビルド成功（3分45秒）。ログ `repair-final-build.log` / `repair-final-device-tests.log`。
- 修正前に取得した匿名53曲DBを戻し、debug実通信でNevermind全13曲の原題取得・ENGLISH判定・先頭の英語表示を確認。Abbey Roadは39/40曲の原題を取得・英語判定し、1曲は原題未確認。英語名の手動注入はしていない。これを実機のユーザー条件のPASSにはしない。
- PC側エミュレータがDNS解決できなかった際は、稼働中アダプタのDNSをエミュレータ起動引数に指定して復旧。製品のTLS設定やホストのネットワーク設定は変更していない。

### 実機での不合格と説明の訂正

- ユーザーから、0:08生成の `OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk` でもNevermind一覧がカタカナと報告。
- 当初「まだ今回版を渡していないので実機未適用」と説明したが、ビルド出力をユーザーがインストール済みだった。確認不足として訂正。共通ファイル名やversionCode=71だけで世代を判断しない。
- USB接続後、Galaxy S25 / SM-S931Zの実機画面で「一覧はカタカナ、下部プレイヤーは英語」、設定で英語優先ONを直接確認。一般的なアルバム画面が対象であり、ユーザーが別の動画を選んだことを原因としない。
- インストール済みAPKのSHA-256は `99de1bde3b0932421b0826b3ff0d55e7a2bb3dc3f2383c6d464b5323dbb268d2`。dex内に今回追加した `assessmentInputSet` とアルバム照合経路があることも確認。00:20再ビルドのSHA-256 `05e703e6782ff91a2b0196f8dc14492300089e9bc251112c93440ea9ffa63f89` は共有IDの別版ケース補修を含むが、本実機条件の解決を示していない。
- 実機アプリのバックアップ機能で端末Downloadへ採取し、そのコピーのDBをread-onlyで調査。データ初期化・置換・アップデートはまだしていない。調査操作中に再生を一時停止。採取後はユーザーへ操作を返した。

| 実機の参照先 | 曲ID | 取得・判定 |
| --- | --- | --- |
| Nevermindの正式順index=0 | `hTWKbfoikeg` | 日英名称SUCCESS、原題und EMPTY、英語判定なし |
| 検索・再生側、同アルバムindex=-1の関連のみ | `ljUtuoFt-8c` | 原題SUCCESS、ENGLISH、英語表示 |

- アルバムIDは同じ `MPREb_jPOYfjGgApr`、`hasTrackList=1`、13曲。現在の13曲は匿名PC応答と別の動画ID群で、AlbumPage由来のOMV型証跡がある。カタカナの原因は表示する曲IDに原題判定が無いこと。先の匿名エミュレータ確認はこのID群を扱っていなかった。
- parserは `playlistItemData.videoId` を曲IDにしており、クライアント側で音源IDをMV IDへ置換するコードは見つかっていない。実機と同じ通信環境の生応答で確認を続ける。
- 私的なバックアップ・設定・APK・応答・画面XMLは無視対象 `build/diagnostics/album-song-language-20260919/physical/` のみ。設定のcookie等をログへ出さず、gitへ追加しない。

**この時点の合格条件は未達。自動テストの成功や別の曲IDでの成功で代替しない。**

## 実機DBを用いたエミュレータ検証への切替

ユーザーから「実機で操作する必要はない、エミュレータでいい」と指定された。以後は実機を操作せず、採取済みのDBコピーを使用する。合格条件は、同じアルバムID・先頭 `hTWKbfoikeg`・全13曲のIDと順序を維持したまま、英語優先ONで先頭が英語、OFFで設定言語へ戻り、再起動後も維持すること。

- エミュレータからのMusic実応答は、実機visitorを用いても音源ID群を返した。再取得で曲目が入れ替わった結果を合格と誤認しないため、保存済み実機DBの名称補完を実通信で実行し、画面確認時にはエミュレータをオフラインにする。
- 検証用コピーでは先頭曲の `inLibrary` のみ設定し、通常のライブラリから開けるようにする。元バックアップは変更せず、名称・曲ID・曲順・アルバム関連は手動修正しない。テスト設定と比較用hashは `actual-replay-input/test-setup.json`。
- Mainの構造化された楽曲カードが、原題を確認済みの `ljUtuoFt-8c` から一覧の `hTWKbfoikeg` を明示的に参照することを実応答で確認した。曲名の一致だけで両IDを同一録音と見なさず、この参照を名称表示の根拠として個別に保存する。
- 参照先には同一アルバム・英語候補との一致も要求する。原題の直接証拠codecを緩めず、元の音源について現在の入力・モデルで行った言語評価を表示時に参照する。参照先を分類器の追加票にせず、再生ID・保存曲・プレイリストを置換しない。
- 起動時は保存済みの名前と原題から候補を探し、Mainの参照を確認するため、未ブックマークのアルバムや、通信側が別の曲目を返す場合も既存一覧を補完できる。新規の正規アルバムで配信元が公式動画IDを返す場合は、限定した曲検索で原題確認用の候補を探し、同じ厳密な参照検証を行う。
- 成功参照は7日、空応答・通信失敗は5分で再試行。カード消失は `{}` の無効証跡で撤回する。通信失敗だけでは確認済み参照を削除しない。同名の別候補との不一致を取得元全体のEMPTYとして記録しない。

詳細仕様は [名称と言語の仕様](../../metadata-language-spec.md)。

### 最終ソースでの確認

- 対象: `7e48d375` 後の未コミット差分、DB28・schema変更なし。
- 単体120件PASS。`models.metadata.*`、`repositories.Metadata*`、`OriginalAssessmentRetentionTest`、`ProviderSongReferenceTest`。Main参照parserは別モジュール7件PASS。
- Android33件PASS（13.650秒）: `AlbumProviderSongReferenceTest` 8、`AlbumOriginalRecoveryTest` 8、`OriginalAssessmentRetentionDeviceTest` 1、`MetadataNameRepositoryTest` 7、`MetadataLanguageIntegrationTest` 1、`MetadataNamesDatabaseTest` 7、`PlaybackMetadataContinuityTest` 1。
- 最終debug・test生成と単体のGradle所要53秒。ログ `reference-alias-build.log` / `reference-alias-device-tests.log`。Androidテストは通信を差し替えた独立Roomを用い、実分類器・起動時復旧・期限後失敗/消失・同名別IDの拒否・再起動・raw/関連/queue維持を検証する。後述の画面確認と区別する。
- 実機DBコピーの `hTWKbfoikeg` を旧debugで表示し、一覧がカタカナ・下部プレイヤーが英語であることを再現。修正版debugの実通信で13曲の原題候補を補完し、うち3曲へ有効なMain参照を取得。
- 同じ13曲の一覧で、先頭 `hTWKbfoikeg` はONで `Smells Like Teen Spirit`、OFFで `スメルズ・ライク・ティーン・スピリット`。2曲目 `PbgKEjNBHqM` と3曲目 `vabnZ9-ex7o` もON/OFFを確認。ONへ戻してアプリを完全終了・再起動しても英語表示を維持。
- `In Bloom` は保存済み詳細候補に `(Official Music Video)` が付いていた。詳細だけを照合するとアルバム候補との一致を見落とすため、取得済み英語別名のうち原題と厳密に一致するものを照合・採用する。原題評価の競合、UNKNOWN/OTHER、未取得候補、manual、OFFの保護はテストで確認。
- 実通信後とテスト開始前の比較で、song全20行、album全6行、song_album_map全20行、song_artist_map全20行、album_artist_map全6行、playlist/playlist_song_map、queue全3行、queue_song_map全21行がそれぞれ全列一致。現13曲の曲ID・順序・raw曲名も不変。`actual-final-core-comparison.json` に記録。名称補完のみでありDB初期化は不要だった。
- 画面の取得時はオフライン。オンラインで別の曲ID一覧へ置換することで見かけ上解消したものではない。DBの補完とMain参照取得はdebugの実通信、画面操作は通常のLibrary→Albums→Nevermindで行った。

### 残っている範囲

**アルバム全曲の英語表示が解消したとは扱わない。** 実機DBの現13曲に対するMain参照は3件SUCCESS・10件EMPTY。Breedなどの日本語表示は残る。Lithium、Endless, Namelessは元の設定言語候補自体が英語の場合があり、新しく原題対応を確認できた曲数へ含めない。

- 固定ID: Breed `J6EDW5WFb2M` / 原題側 `ox_BG6sLPq8`、Lithium `pkcJEvMcnEg` / 原題側 `_oWUgfpGi0M`。
- 上記4IDを匿名Main `/next` で両方向確認。HTTP200、要求元IDは一致したが、Lithium原題側は曲カードなし、他3件は曲カードに動画endpointがない。全文にも相手IDはなく、同じカードの別構造をparserが見落としたケースではなかった。rawは `main-reference-reverse-probe/` にのみ保存。
- この応答の不足だけで全サービスに対応情報が無いとは断定しない。既存の「確認できた英語原題のみ採用」の規則下では未解決。追加の対応根拠を取得する方法の検証、または英語候補全般を優先する別仕様の判断が必要であり、今回のテスト成功で残件を完了にしない。
- 実機の再確認はユーザーの指定どおり実施していない。release実通信は既知のPC証明書制約により検証対象外。製品TLS設定の緩和は行っていない。

### 配布用APKと画面証跡

- `coreRelease` / `arm64-v8a`、0.10.2-b1 (71)。最終ビルド2分56秒成功。`reference-final-release-build.log`。署名v2検証成功、APK内のnative ABIがarm64-v8aだけであることを確認。
- ファイル: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-album-20260920-011807.apk`。
- SHA-256: `267fa5ac5b6c2544a1778140946a177b5e2ffcd86a92a36f88215210dcb628a1`。同名の旧ビルドとの混同を避け、配布コピーには生成時刻を付けた。mappingを同ディレクトリへ保存。
- このAPKをエミュレータにインストールし、debug実通信で補完した実機DBのコピーを通常のバックアップ復元画面から読み込んだ。オフラインの通常のNevermind画面で、上記3曲の英語ON・日本語OFF・ONへ戻して完全終了/再起動後の維持を直接確認した。release通信の成功を意味しない。
- [同一IDでの修正前](../images/2026-09-19-album-song-language/actual-db-before.png)、[debug ON](../images/2026-09-19-album-song-language/actual-db-on.png)、[debug OFF](../images/2026-09-19-album-song-language/actual-db-off.png)、[配布用release ON・再起動後](../images/2026-09-19-album-song-language/actual-release-on.png)、[配布用release OFF](../images/2026-09-19-album-song-language/actual-release-off.png)。release画像にカバーが無いのはオフラインで画像cacheを移していないため。
- 改修前のcheckpoint以降に追加commit/pushは行っていない。今回の修正・テスト・仕様・検証記録は未コミット差分として残す。実機は追加操作していない。エミュレータの検証操作は終了し、アプリを停止して操作制限を解除する。
