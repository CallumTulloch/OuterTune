# コンテンツ言語の決定・通知の統一

## 現象と期待動作

アプリ表示言語を変えると、端末に従うコンテンツ言語まで変わる経路がある。起動・設定画面・名前保存・検索が別々に言語を解決し、変更通知も一致していない。

既存のコンテンツ言語・地域設定を優先する。「端末に従う」は端末のシステム言語・地域を参照する。アプリ表示言語だけの変更はコンテンツ言語を変えない。既存の「英語表記利用」チェックの意味・英語の適否判定は変更しない。

## 根拠・対象・合格条件

App、InterfaceFrag、MetadataNameRepository、BilingualSearch で独立した Locale.getDefault ベースの決定を確認。InnerTube の初期値も同じ既定値を参照。既存の localeUpdates を発行する単一の設定解決元へ統一し、検索・補完・各画面・保存名・プレイヤーへ同じ変更を伝える。

検索結果と継続取得、取得途中の変更、保存済み名称の再表示、端末言語変更のみ、アプリ言語変更のみ、明示設定と端末に従う設定、既存チェックの切替を対象とする。既存の再生修正を保全し、言語切替が再生状態・ID・音声URLを作り直さないことを確認する。ソース調査、単体テスト、端末テスト、実際の release の確認を区別して記録する。未実施の確認を PASS と扱わない。

検証時の対象ソース: HEAD 7bc8bfa5847652d9cd6e7883de562574be36147e + 既存未コミット差分 + 本改修。最終結果・成果物は末尾に記載。検証完了時点では既存差分を維持し、commit/pushは行っていない。続くユーザーのコミット依頼により、本記録を含むコミットへ、ここまでのソース・テスト・調査記録・検証画像をまとめる。APKと実行ログはコミット対象外。次の「曲なび」「ライブラリなび」の条件名変更は含まない。

## 仕様境界と合格条件ID

個別アルバムでユーザーが見た変化の実機再現は、静的に影響経路を確認したことと区別する。英語表記利用についてはユーザーが説明に疑問を示しているため、「二言語の取得・保存」と「候補の表示選択」を混同しない。この改修の都合でチェック項目の意味・既定値・判定・説明文を変更しない。未確定の解釈を「英語優先」という合格条件にしない。

| ID | 条件 | 確認する結果 |
| --- | --- | --- |
| C01 | コンテンツ日本語固定、アプリ表示日本語→英語→日本語 | 要求言語・名前選択言語・言語別保存先は日本語のまま。UIラベルは設定どおり変更 |
| C02 | コンテンツ「端末に従う」、端末日本語、アプリ表示英語 | コンテンツは端末設定に従い、プロセス既定Localeを端末言語と取り違えない |
| C03 | 端末言語変更 | 端末に従う場合は共通状態を更新。コンテンツ明示指定は維持 |
| C04 | コンテンツ言語・地域変更 | 保存設定、通信、検索、補完、表示が同じ決定結果を使う |
| C05 | 通信中・続き取得中・連続変更 | 古い応答が現在ページ・補完を上書きしない。続きtokenを別言語で送らない |
| C06 | 再起動、候補が片言語/両言語/未取得、オフライン | 保存情報を勝手に翻訳・改名せず、既存表示選択規則と照合できる |
| C07 | 再生中の設定変更 | 曲ID、URI、キュー順、再生位置を維持し、表示更新が音声再生を止めない |
| C08 | 静的検査 | 独自のコンテンツ設定解決や通信言語への直接代入の再導入を検出できる |

## 決定元の逆引き

`Locale.getDefault`、`YouTube.locale`の代入/参照、`ContentLanguageKey`/`ContentCountryKey`、`requestLocale`、`metadataObserver`、DB書込みと表示拡張を逆引きし、画面入口からの追跡と照合した。以下の行番号は調査開始時。改修後はシンボル名を正とする。

| ID | 調査開始時の場所 | 初回の問題・区別 | 実装上の処置 |
| --- | --- | --- | --- |
| D01 | `App.kt:onCreate` 86–100 | Locale.getDefaultと保存設定から独自決定 | 共通リポジトリの同期初期化へ |
| D02 | `InterfaceFrag.kt:ContentSettings` 462/493 | 言語/地域コールバックが個別にYouTube.locale.copyを代入 | 設定保存を入口とし共通観測側で反映 |
| D03 | `MetadataNameRepository.kt:contentMetadataLocale` 555 | Locale.getDefaultで独自解決 | 唯一の解決元へ集約。端末Localeを入力 |
| D04 | MetadataNameRepository.start 133、BilingualSearch 51/60、AlbumMetadataRepository.start 81 | 個々にD03を呼び、端末変更そのものの共通通知がない | 共通の決定済み値と変更通知を参照 |
| D05 | `InnerTube.kt:locale` 35 | HTTP層初期値がプロセス既定Locale | Android設定を解釈しない初期値と起動順の検証 |
| D06 | `YouTube.kt:locale` 179、localeUpdates 160 | 通信既定値の設定後にStateFlow更新 | 共通状態からの唯一の反映先として監査 |
| D07 | `InterfaceFrag.kt:AppLanguagePreference` 511–540 | LocaleManager.applicationLocalesを変更 | UI言語の入口として維持。コンテンツ値を代入しない |
| D08 | `ArtistDisplay.kt:artistNameSeparator` 13 / artistDisplayText 26 | アプリLocaleで区切りと不明ラベルを選択。名前候補言語を選ぶ処理ではない | 表示の役割を区別し、原語判定へ混ぜない。最終差分で処置を確認 |
| D09 | Player.kt 625、PlayerMenu.kt 268/274/777 | Locale.getDefaultで数値/時間/日時を書式化 | UI書式として維持可能。禁止参照検査の許可分類 |
| D10 | App.kt:onCreate 99 | 起動時だけKuGou.useTraditionalChineseを設定 | 共通コンテンツhlのzh-TWへの変更に追従（実装担当報告、実行結果は別記） |

`Locale.ROOT`での文字列/ID正規化は言語決定ではない。`YouTubeClient.toContext`は渡されたgl/hlを要求本体へ入れるだけでAndroid設定を解釈しない。

## 通信・保存・表示の共通経路

1. YouTubeのメタデータAPIが入口でrequestLocaleを受け取り、HTTP要求とパーサーのlanguageへ渡す。
2. 成功結果をnotifyMetadata(items, requestLocale, source)からMetadataNameRepositoryのobserverへ渡す。
3. metadataNameCandidatesが要求言語を記録し、MetadataNamesDao.recordMetadataNamesが保存する。MetadataNameEntity主キーはkind/targetId/language/name/source。文字の見た目から要求言語を推測しない。
4. MetadataNameRepositoryがDB候補と設定を結合し、既存selectMetadataDisplayNameへコンテンツ言語を渡す。
5. MetadataDisplay.ktのMetadataNames.publish/resolveが表示名一覧を公開し、displayTitle/displayNameが曲・アルバム・人物IDで読む。ローカル音源は元の名前を使う。
6. MediaItemExt.toMediaItem（24/41/58）が同じ表示拡張からMedia3の曲・人物・アルバム表示を作る。生のメタデータ、ID、URIは別に保持する。

英語と指定言語を保存すること、二言語の検索結果を統合すること、表示名へ英語を採用することは別の処理である。

## 入口ごとの網羅表

この表の「読取確認」「実装済」は実機動作のPASSを意味しない。実測結果は後段へ追記する。

| ID | 入口→要求（初回調査の行） | 保存 | 表示・更新経路 | 調査・処置 |
| --- | --- | --- | --- | --- |
| P01 | 検索→OnlineSearchViewModel 39→BilingualSearch→YouTube.searchSummary/search 246/255 | 共通observer、検索語は検索履歴 | OnlineSearchResult/共通itemsのdisplayTitle、configurationChangesで再検索 | 独自設定解決を共通化。UIのみ変更で要求不変を確認 |
| P02 | 候補→OnlineSearchSuggestionViewModel 31→BilingualSearch→searchSuggestions 223 | おすすめitemはobserver、候補文字列は応答 | query/activation/configurationで旧応答排除 | 入力を固定したまま言語を変更して確認 |
| P03 | 検索の続き→OnlineSearchViewModel.loadMore→BilingualSearch.searchContinuation | observer | cursorがquery/filter/locale/authと各言語tokenを保持 | 専用cursor試験。汎用loaderだけの試験で代用しない |
| P04 | ホーム→HomeViewModel 60/102/118/137/154/215→home/library/artist/next/related/explore | observer、DBカードを参照 | HomeFeedLoader、localeUpdates、変更時reset | 全サブ要求、チップ、続きが同じlocaleを使うか確認 |
| P05 | アルバム→AlbumViewModel 64→album 288→トラック続き/代替playlist | observer、generation/locale確認後DB保存 | AlbumScreen、displayTitle/artistDisplayText、localeUpdates | 保存済み/未保存の双方。旧応答の破棄も確認 |
| P06 | 保存アルバム補完→AlbumMetadataRepository / MetadataNameRepository | AlbumsDao.applySavedAlbumHeader 483、名前DB | DB Flowと名前一覧 | 両リポジトリのlocale決定元を共通化。旧応答拒否試験を維持 |
| P07 | 人物→ArtistViewModel 61/93/98、ArtistItemsViewModel 22→artist/artistItems/continuation | observer、saveArtistProfileはgeneration/context確認 | ArtistScreen/ArtistItemsScreen、localeUpdates/LocalizedPageLoader | 同じ人物IDでUIのみ変更とコンテンツ変更を区別 |
| P08 | 人物補完→ArtistCreditRepository.request 150→resolveTrackArtistCredit 948 | 言語/地域/authを含むcache、曲credit/album DB | ArtistCreditState 38/59、MusicServiceのcredit購読 | contextの前後確認あり。共通更新時に再購読されるか確認 |
| P09 | オンラインplaylist→OnlinePlaylistViewModel 24→playlist/continuation | observer、登録時は曲/playlist DB | LocalizedPageLoader/OnlinePlaylistScreen、playlist名は応答 | 曲名だけでなくplaylist見出し・続きも確認 |
| P10 | 汎用browse/ジャンル詳細→BrowseViewModel / YouTubeBrowseViewModel→browse 585 | observer | LocalizedPageLoaderでページ更新 | 旧応答排除と共通通知を確認 |
| P11 | ムード/ジャンル→MoodAndGenresViewModel 19→moodAndGenres 579 | 保存なし | 初版は初期化時のみ取得 | LocalizedPageLoaderへ変更済。言語変更時に旧見出し消去・再取得 |
| P12 | アカウント→AccountViewModel 25/32/39→library.completed | observer | playlist/album/artistリスト、初版は初期化時のみ | 3種類ともLocalizedPageLoaderへ変更済。完了件数は失敗も含む既存契約を維持 |
| P13 | 履歴→HistoryViewModel.fetchRemoteHistory 64→musicHistory 731 | observer、ローカルはevents DB | historyPage、localは共通items | remoteをLocalizedPageLoaderへ変更済。手動更新も同じgeneration保護を使用 |
| P14 | ライブラリ/ローカルplaylist/保存人物曲→各VM→DB Flow | 保存DB、metadataLibraryTargetsから不足名補完 | displayTitle/displayName、LocalPlaylistScreen 198は名前revision購読 | 両言語/片言語/オフライン/検索/並替を確認 |
| P15 | ライブラリ/統計のゼロ曲album補完→LibraryViewModels 289 / StatsViewModel 51→album | database.queryでupdate | DB Flow | 初版は遅い応答のlocale/contextガードなし。担当が保存時の保護を追加 |
| P16 | キュー/radio→YouTubeQueue 20/35、YouTubeAlbumRadio 22/23/36→next/albumSongs | observer、キューMediaMetadata | Queue.Status→MediaItemExt→プレイヤー | 初回要求でlocaleを保持し続きにも使用する実装へ変更済 |
| P17 | 再生・通知→MusicService 292/329、MediaItemExt | 表示投影自体は元メタデータを改名しない | 名前更新でMedia3表示をreplace、credit更新でtag更新、通知更新 | URI/cache key/キュー順/再生位置不変と実再生継続を確認 |
| P18 | 外部MediaBrowser/車載→MediaLibrarySessionCallback→DB/queue | 保存DB | 専用toMediaItemでもdisplayTitle/displayName/artistDisplayText | 画面外の表示経路。再要求時の名前一致を確認 |
| P19 | download→DownloadUtil.prepareAndDownload 247→queueで不足album取得→resolvePlaybackData | merge後曲DB、audio cacheは動画ID、DownloadRequest.dataは開始時title | ライブラリは名前投影、進捗通知titleは開始時値 | batchを同一localeへ固定しcontext変更時は元データに戻す処置（担当報告）。音声継続は別検証 |
| P20 | 同期→SyncUtils 283/559/633/680/702→playlist/library/recentActivity | 曲/album/人物/playlist/recentActivity DB、observer | DB Flow＋名前投影 | completedはpage.requestLocaleを保持。複数top-level要求・遅いDB反映は別監査対象 |
| P21 | 歌詞→YouTubeLyricsProvider 11のnext→lyrics、字幕provider→transcript | 歌詞はprovider側 | 歌詞画面、名前選択とは別 | next/lyricsを同一localeへ固定（担当報告）。KuGouはD10参照 |
| P22 | 再生関連→MusicService 434–435のnext→related | observer | 関連候補 | 2段要求を同一localeへ固定（担当報告） |
| P23 | 音声URL→YTPlayerUtils 118/154/234→player | 形式・URL cache、名前DBとは独立 | 再生可否・エラー表示 | 複数client要求は個別既定。言語変更がID/形式/cache keyを変えないか確認 |

### パーサーと続き取得

- YouTube.ktのsearch/suggestion/album/artist/artistItems/playlist/home/explore/browse/library/recentActivity/history/next/related/queueは要求localeをパーサーとobserverへ渡す。
- AlbumPage、ArtistItemsPage、ArtistPage、HomePage、HistoryPage、LibraryPage、NextPage、NewReleaseAlbumPage、RelatedPage、PlaylistPage、SearchPage、SearchSummaryPage、SearchSuggestionPageのパーサーは省略時にYouTube.locale.hlを使う。公開API側の明示引数を確認し、新しい省略呼出の混入を監査する。
- innertube/utils/Utils.kt:completed（13/36）はpage.requestLocaleで全ページを取得する。nullの既存/テストpageだけglobal fallbackを使う。
- LocalizedPageLoaderは変更時に旧page/tokenを捨て、generationと現在localeで遅い応答を拒否する。ホームはHomeFeedLoader、検索は独自cursor、キューは初回localeの保持という別経路を持つ。

## 検証設計と既存試験の限界

全因子の直積を実機で実行したという意味の「完全網羅」は主張しない。決定関数の境界を単体、非同期遷移を専用試験、Android端末Locale取得と画面接続を端末試験で確認し、各P行と証拠を対応させる。

| ID | 条件 | 証拠・対象試験 | 本表作成時 |
| --- | --- | --- | --- |
| T01 | 設定未保存/SYSTEM_DEFAULT/ja/en/中国語地域、端末ja-JP/en-US/zh-Hant-TW/未対応/地域なし | 共通決定関数。対応表の従来優先順を維持 | 実行待ち |
| T02 | 端末Locale入力と設定を固定しプロセス既定Localeだけ変更 | C01/C02不変の単体試験 | 実行待ち |
| T03 | 端末変更、言語のみ/地域のみ明示、同値・無関係設定変更 | 必要な変化だけ通知、言語/地域を1スナップショットで公開 | 実行待ち |
| T04 | 起動、service起動、設定変更、アプリ再作成、再起動 | 初期化前の中間値を要求に使わず、保存設定から復元 | 実行待ち |
| T05 | 検索summary/category/suggestion/continuation、失敗、変更 | BilingualSearchTest、OnlineSearchViewModelTest、OnlineSearchSuggestionViewModelTest | 既存試験、最終実行待ち |
| T06 | 初期/続きの完了直前・失敗時の変更、同じlocaleで手動更新 | LocalizedPageLoaderTest、HomeFeedLoaderTest、新SecondaryContentLocaleTest | 新規4ケース追加、実行待ち |
| T07 | 要求中にglobal localeが別値へ変更 | MetadataRequestLocaleTest：パース/resolver/observer/completed | 既存試験、最終実行待ち |
| T08 | DB片言語/両言語/未取得、通信中変更・失敗・再起動 | MetadataNameRepositoryTest、AlbumMetadataRepositoryTest、MetadataLanguageIntegrationTest | 既存試験、最終実行待ち |
| T09 | 実際のアプリ言語だけ変更、コンテンツ日本語/端末既定 | LocaleManager、DataStore、要求locale、DB.language、Media3表示の照合 | 端末確認必要 |
| T10 | 再生中変更、シーク、cache境界、通知更新 | PlaybackMetadataContinuityTest＋実APK再生 | 最終実行待ち |
| T11 | キュー/radio続き、歌詞/関連2段要求、同期の複数要求 | 新QueueContentLocaleTest（2件）＋該当処置の最終差分・要求記録 | queue試験追加、実行待ち |
| T12 | production全体の決定元/書込元 | D01–D10処置・許可分類、禁止参照検査、P行の最終結果 | 初回静的調査済 |
| T13 | arm64 core-release | ファイル名、SHA-256、ソース識別、C01/C02/C04/C07実測 | 未実行 |

既存MetadataRequestLocaleTestの試験名にある「app language changes」は、実際にはYouTube.localeを変更するパーサー試験である。AndroidのapplicationLocalesとsystemLocalesの分離を試験した証拠として扱わない。

## 残項目ID

- R01：D01–D05共通化、初期化順、端末変更通知（C01–C04/T01–T04）。最終実行結果待ち。
- R02：P11–P13の再取得漏れ。実装・専用試験追加済、最終実行待ち。
- R03：P16/P21/P22の複数要求・tokenのlocale固定。実装済、最終実行待ち。
- R04：P15/P19/P20のDB反映時の設定変更。内容の同一性と名前候補の言語記録を区別して監査。同期P20は残監査対象。
- R05：D08区切り/不明ラベル、D10歌詞表記。役割の異なる文字列を一律置換せず、最終差分と仕様を照合。
- R06：C01/C02/C07の端末・APK検証。静的調査や既存テスト件数で代用しない。
- R07：認証変更中のmetadataObserver入口（独立レビューで指摘、追加修正済・回帰試験の実行待ち）。observerへの応答到着時に現在の認証contextを付けるため、アカウントAで開始した遅い応答がBへ切替後に届くと、Bのcontextとして扱われ得た。再現条件はAでメタデータ要求を保留→Bへ切替→Aの応答を解放。YouTubeの23個のメタデータ通知経路を共通metadataRequestへ接続し、要求開始時の認証revisionと通知直前のrevisionが同じ場合だけobserverへ渡す修正を追加。認証setterと通知可否判定を同じlockで保護し、A→B→Aでも旧応答を拒否する。合格条件は旧認証応答が新observerへ一度も到達しないこと。後段の追加試験結果でこの境界の完了を判断し、認証遷移全体の保証へ拡張しない。

## 担当作業記録

- 2026-09-12 経路担当：AGENTS.md、CONTRIBUTING.md、development-workflow.mdを読んで初回静的調査。本体/innertube/試験の逆引き。初版では実機再現なし。
- 2026-09-12 経路担当：Account/Mood/Historyを共通のlocale通知へ接続。LocalizedPageLoaderに同一localeでの手動refreshを追加し、古い初期/続き応答を即時無効化。YouTubeQueue/YouTubeAlbumRadioは初回要求のlocaleを後続要求へ保持。アプリ表示設定・英語判定は変更せず。
- 追加試験：SecondaryContentLocaleTest 3件（アカウント全分類の旧応答/失敗、ムード見出し更新）、QueueContentLocaleTest 2件（初回要求時点のlocale、album→radio→続きの固定）、LocalizedPageLoaderTest 1件（同一localeの明示refreshと旧token破棄）。経路担当はGradle/デバイスを実行せず、ルート担当の一括実行へ引継ぎ。
- 追加の再生修正：YouTubeAlbumRadioで、アルバム3曲に対してradio応答が1曲だけなら旧処理はsubList(3, 1)、0曲ならsubList(3, 0)になり、キュー構築時に例外が発生する経路を特定。範囲の厳密指定をdrop(albumSongs.size)へ置換し、取得済みアルバム全曲と存在する追加候補を維持する。AlbumRadioQueueTest 2件を追加（短い応答と後続取得、空radioと全応答空）。短いprovider応答を与える具体的な再現fixtureであり、ユーザーの報告と同一原因とは未確認。経路担当は未実行、ルート担当の最終試験へ引継ぎ。

## 途中releaseでの端末観測と追加修正

以下はルート担当が直接行った端末操作の報告を経路担当が記録したもの。経路担当自身は端末を操作していない。いずれも追加修正前の途中releaseに対する`OBSERVED`であり、最終ソース・最終配布APKの`PASS`に繰り上げない。APK識別情報、最終テスト件数・ログは実行担当の追記を待つ。

| 観測ID | 実際の条件・操作 | 観測結果 | 対応する条件・判定 |
| --- | --- | --- | --- |
| O01 | アプリUI英語、コンテンツ日本語で表示 | 曲・人物の名前が日本語で表示された | C01 / OBSERVED（途中release） |
| O02 | O01からアプリの言語上書きだけを日本語へ変更 | 名前は日本語を維持した | C01 / OBSERVED（途中release） |
| O03 | 既存の英語表記利用チェックを有効→無効 | 有効時はSmells Like Teen Spirit / Nirvana、無効時は日本語名へ戻った | 既存チェックの動作確認 / OBSERVED。新しい意味付け・判定規則への変更ではない |
| O04 | 実際の端末システム言語en-US、コンテンツをSYSTEM_DEFAULTへ変更、オフライン | 曲名は英語へ変更されたが、人物がUnknownになった | C02/C06 / OBSERVED：追加不具合を検出 |
| O05 | Android設定画面で端末言語順をen-US,ja-JP→ja-JP,en-USへ変更。settingsのsystem_locales値とシステムUIで実変更を確認。アプリ言語はjaに固定、コンテンツは端末に従う | 起動中アプリへ復帰して30秒待っても曲名は英語のまま | C03/C04 / OBSERVED：端末言語変更の通知漏れを検出 |

### O04：言語変更の補完開始時に既知の人物が消える

`ArtistCreditRepository`は新しい言語の補完を開始すると空のRAW状態を作る。初版ではその空状態が、別の言語に属する既知の完全なクレジットを表示上隠し、オフラインでは人物不明のままになる経路があった。空のRAWは人物が存在しないという証拠ではないため、`combinedCredit`で空RAWを除外して元の確定済み情報を保持する修正を追加した。担当が回帰用単体試験2件を追加。試験と同じ端末操作の最終版再確認は実行結果待ち。

### O05：アプリ言語固定中の本当の端末言語変更

アプリの表示言語が固定されている条件では、端末言語を変えても`ComponentCallbacks.onConfigurationChanged`だけでは共通コンテンツ状態が更新されない実例を確認した。ルート担当が`ContentLocaleRepository`へ`ACTION_LOCALE_CHANGED`を受ける`BroadcastReceiver`を追加し、通知時に端末のsystemLocalesを読み直すよう修正した。

実際のシステム言語を変更するopt-inの端末試験を追加中。アプリのapplicationLocalesを変更する従来試験や、偽のLocale入力を渡す単体試験は、このO05の代用にはしない。C03の最終合格には本物の端末言語変更での再確認が必要。

### レビューの認証遷移に関する制限

検索cursorや補完cacheに認証contextが含まれること、observerキュー内でcontextを再検査することは確認した。ただし、要求を出した時点と応答がobserverへ届いた時点の間に起きるアカウント切替は別の境界である。R07にはこの入口を保護する追加修正を行った。P03/P08等のcontext保護と今回の入口修正をもって、認証遷移を全体として検証済みとは扱わない。

## R07の追加実装・検証対象

- `YouTube.metadataRequest`はHTTP要求・パース前に認証revisionを保持し、成功結果のobserver通知前に照合する。既存の要求localeはそのまま維持する。古い認証の結果も既存呼出元へのResult契約は維持し、任意のメタデータ収集だけを抑止する。
- revisionの変更対象はcookie、dataSyncId、visitorData、useLoginForBrowse。値が同じ再代入やコンテンツ言語のみの変更ではrevisionを変えない。認証値自体をテストログや新しい保存先へ記録しない。
- 保護対象23経路：searchSuggestions、searchSummary、search、searchContinuation、album、albumSongs、artist、artistItems、artistItemsContinuation、playlist、playlistContinuation、home、homeContinuation、explore、newReleaseAlbums、browse、library、libraryContinuation、libraryRecentActivity、musicHistory、next、related、queue。公開メソッドの引数と既存observerのsignatureは維持。
- 新規`MetadataAuthenticationTest`4件：要求処理をCompletableDeferredで停止して各認証入力を変更、新contextでは旧応答を通知せず新要求は通知すること／A→B→Aでも拒否／同じ認証値再代入と言語のみ変更は元の要求locale・同じ生itemを通知／収集無効と失敗は通知しないこと。
- 新規`MetadataObserverArchitectureTest`2件：23経路がすべて共通wrapperを使用すること、productionの通知呼出がguard内の1か所だけであること。
- 経路担当はGradle・デバイスを実行していない。`git diff --check`は差分形式の確認のみ成功。R07の回帰試験PASSはルート担当の最終実行結果を待つ。

## 最終結果（ルート担当が直接実行、2026-09-12）

上の「実行待ち」「OBSERVED」は各調査段階の記録。以下が最終ソースに対する結果である。チェックの意味、英語の適否判定、対応言語・地域の従来の優先順、保存キーを維持した。DB schemaの変更なし。

### 自動検証

実行: `:app:testCoreDebugUnitTest :innertube:test :app:assembleCoreDebugAndroidTest :app:assembleCoreDebug :app:assembleCoreRelease --offline --console=plain`。

| 対象 | 最終結果 | 証拠 |
| --- | --- | --- |
| ビルド・関連テスト・core-release生成 | PASS、2分44秒 | `build/content-locale-verified-final.log` |
| app単体 | 315件PASS、失敗・skipなし、試験本体3.835秒 | `app/build/test-results/testCoreDebugUnitTest/` |
| innertube単体 | 76件中63件PASS、既存skip13件、失敗なし、試験本体1.274秒 | `innertube/build/test-results/test/` |
| Android上の設定・名前・人物・アルバム・cache・実ExoPlayer | 27件PASS、10.252秒 | `build/content-locale-final-device.log` |
| 本当の端末言語変更、アプリ言語固定中 | 1件PASS、32.533秒 | `build/content-locale-final-live-system.log` |
| 差分形式、APK署名、ABI | PASS。APK署名v2、signer 1、native libはarm64-v8aのみ | `git diff --check`、`apksigner verify --verbose`、APK ZIP entries |

Android試験はPixel_9_API_35（Android 15、x86_64ホスト上のarm64実行対応）で、arm64 core-debug APKを使用した。27件の内訳はContentLocaleDeviceTestの通常2件、ArtistCreditRepositoryTest 8件、AlbumMetadataRepositoryTest 8件、MetadataNameRepositoryTest 3件、MetadataLanguageIntegrationTest 1件、PlaybackCacheDataSourceTest 4件、PlaybackMetadataContinuityTest 1件。

本当の端末変更試験は`ContentLocaleDeviceTest#liveSystemLanguageChangeWithApplicationOverride`に`systemLocaleTarget=ja-JP`を指定。待機通知の後にAndroid設定UIで端末の優先言語をen-US→ja-JPへ変更した。固定アプリ言語、保存設定、Appインスタンスを維持したまま共有hl/glがja/JPに更新された。偽callbackや再起動で成功させていない。途中版でも逆方向のja-JP→en-USを確認済み（`build/content-locale-live-system.log`、42.021秒）だが、最終件数には加算しない。

R07はMetadataAuthenticationTest 4件とMetadataObserverArchitectureTest 2件を含む最終innertube試験でPASS。同期DB書込中の変更については、トランザクション終端の再確認・不一致時のrollback・commit後の再確認を追加。LocalizedSyncSnapshotTest 8件がPASSし、旧応答、commit待ち・書込中の変更、現contextのDBエラー、cancelを区別した。

### 実際の配布用APKの確認

以下は最終core-release APKをインストールして直接操作した。debugの成功をreleaseの成功へ転記していない。日本語・英語名と人物IDを持つ検証用保存データを使用。画像は[英語UIと端末既定の日本語コンテンツ](../images/2026-09-12-content-locale-release.png)。

| 条件 | 最終releaseで観測した結果 | 対応 |
| --- | --- | --- |
| 以前「人物不明」が出た状態から更新。オフライン、端末ja-JP、コンテンツ端末既定 | 曲・人物が日本語で復元。以前の空RAW cacheが残っていても人物IDに対応する表記を表示 | C02/C06、O04解消 |
| 端末ja-JP、コンテンツ端末既定でアプリ表示だけenへ変更 | 操作表示は英語、曲一覧とミニプレイヤーは日本語名を維持 | C01/C02 |
| アプリ表示en固定、端末をja-JP→en-USへ変更 | 同じPID、HOT復帰で曲一覧とミニプレイヤーがSmells Like Teen Spirit / Nirvanaへ更新 | C03、O05解消 |
| 端末en-US、コンテンツ端末既定。アプリ表示jaへ変更してforce-stop→起動 | COLD起動1,181ms、UIは日本語、コンテンツは英語を維持 | C02/C06 |
| 端末en-USのままコンテンツを明示jaへ変更 | 日本語の曲名・人物名へ更新 | C04 |
| 既存チェックを有効→無効 | checked=trueで英語名、checked=falseで日本語名へ復帰。設定画面を開いたままミニプレイヤーにも反映 | C04/C06 |

チェック操作の最終証拠: `build/content-locale-final-checkbox-on.xml`、`build/content-locale-final-checkbox-off.xml`。同一PIDの証拠: `build/content-locale-release-pid-before.txt`、`build/content-locale-release-pid-after.txt`。最終crash bufferは空（`build/content-locale-final-crash.log`）。この確認は音声の再生成功を意味しない。

### 網羅性の判定と限界

- C01–C04/C06: 共通決定関数・通知・保存名・人物の端末試験と上のrelease実測により確認。対応表の全言語×国の明示指定、未保存/端末既定、無関係設定で通知しない条件は単体試験で確認した。全ての国・言語を実機UIで選択したという意味ではない。
- C05: 検索/補完、各ページの共通loader、home、queue、sync、名前/アルバム/人物repositoryの遅延・失敗・継続・再取得を対応試験で確認。P01–P23の静的経路調査を併用した。全画面の実通信を一つずつreleaseで確認したという意味ではない。
- C07: 実ExoPlayerと部分cacheを使う端末試験で、名称変更後の再生継続とseekを確認。アルバム3曲に対してradio 1曲/0曲の範囲外例外は回帰試験で再現・修正済み。利用者の未特定の再生停止と同じ原因かは未確認。
- C08: 独立した設定決定元/YouTube.locale書込元、process-default locale再導入、23経路の認証guard逸脱を自動検査する。静的検査はOS通知の実測の代わりにはしない。O05はこの違いによって端末で追加検出できた。
- 実YouTube配信は未確認。検証環境からの接続は`SSLHandshakeException` / `CertPathValidatorException: Trust anchor for certification path not found`と接続timeoutで失敗した（`build/content-locale-release-process.log`）。これは途中releaseでの実通信試行の記録であり、最終releaseの音声成功として扱わない。最終版のonline検索・配信開始・実アカウント同期にはこの制約が残る。
- Android 13/14や物理端末各機種での実測、全因子の直積、全アカウント遷移は未実施。確認範囲を越えて不具合ゼロを保証しない。

端末言語の取得は[LocaleManagerCompat.getSystemLocales](https://developer.android.com/reference/androidx/core/app/LocaleManagerCompat#getSystemLocales(android.content.Context))、端末・アプリの言語変更通知は[Intent.ACTION_LOCALE_CHANGED](https://developer.android.com/reference/android/content/Intent#ACTION_LOCALE_CHANGED)の公式仕様を照合した。

### 最終成果物

- `build/distributions/OuterTune-content-locale-20260912-arm64.apk`
- core-release / arm64-v8a、9,070,974 bytes。
- SHA-256: `53018ae0ea3dfced12cba5a6600f7d627f5b1bd537be5a101872abd1bb6532ad`
- 最終ソースによる生成後は文書・検証画像のみを更新。APK確認後、read-only検証エミュレータを終了し、操作終了をユーザーへ連絡済み。
