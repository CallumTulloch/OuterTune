# アーティスト配下のアルバム表示とKing Gnuの取得調査

## 課題と範囲

- ユーザー報告：アーティストから開くアルバム、シングル／EPのアーティスト表示がすべて「不明」。
- ユーザー報告：King Gnuの「THE GREATEST UNKNOWN」を選択しても何も表示されず、検索にも出ない。
- 期待結果：人物名が欠ける段階、対象アルバムの応答・解析・画面表示、検索での脱落条件を分けて説明する。
- 今回は追加調査のみ。製品コード、DB、設定、APKを変更せず、ビルドを行わない。
- 対象ソース：`7bc8bfa5847652d9cd6e7883de562574be36147e`、`restart/artist-20260905`。追跡済み差分なし。既存のホーム・同期調査記録を保持。
- アカウント・地域・検索条件が端末と同じであると仮定しない。公開APIは匿名・JP・日本語から確認し、必要な応答だけ比較する。

## 調査結果の要点

1. アーティスト配下では年・作品種別だけを返すカードを、共通の人物名表示へ渡して「アーティスト不明」にしている。先日の共通表示への変更で、人物欄が空だった表示に「不明」が出るようになった。
2. 対象アルバムは最初の応答にヘッダーと21曲を含む。しかし現行の取得関数は曲を別のプレイリスト要求で取り直し、今回その応答に内容がなく失敗する。失敗画面が未実装なので空白になる。
3. 対象は日英の検索応答に存在する。再生ボタンがPremium案内となり再生用playlistIdを含まないため、必須扱いするパーサーが検索項目を捨てる。

以下は匿名・JPの実応答と現行コード分岐の照合。ユーザー端末・ログイン状態での画面再現や音声再生の検証ではない。

## 1. アルバム・シングル／EPの人物名が「不明」になる

### 実際の応答

King GnuのアーティストIDは `UC9tx_HmhNISUR39M29r9a8A`。アプリと同じWEB_REMIXクライアント・バージョンで取得した。

| 取得箇所 | 件数 | カードの副題 |
| --- | ---: | --- |
| アーティスト画面のアルバム棚 | 5 | `2023年` など、年のみ |
| アーティスト画面の「シングルと EP」棚 | 10 | `シングル • 2026年` など、種別と年のみ |
| 「シングルと EP」の一覧ページ | 29 | 種別と年のみ。人物名はない |

対象アルバムの詳細ヘッダーには `King Gnu` と同じ人物IDがある。つまり、人物情報そのものがYouTubeから失われているわけではなく、一覧カードでは省略されている。

### コードの経路

パスの基点は以下を使う。

- app：`app/src/main/java/com/dd3boh/outertune/`
- innertube：`innertube/src/main/java/com/zionhuang/innertube/`

1. `innertube/pages/ArtistPage.kt:121–135` と `ArtistItemsPage.kt:61–77` は、アルバムの `artists = null` を設定する。`artistCredit` はカードの副題から作る。
2. `innertube/models/ArtistCredit.kt` の `toAlbumArtistCredit()` は年・「シングル」・「EP」を人物名から除外する。今回の副題はこれしか含まないので、クレジットの文字列・人物一覧は空になる。年を人物と誤認しない処理自体は適切。
3. `app/ui/component/items/Items.kt:437–440,538` が `item.artistDisplayText()` を無条件で使う。
4. `app/utils/ArtistDisplay.kt:16–43,66` はクレジットと代替名が空の場合に「アーティスト不明」を返す。

**回帰箇所**：`4def7ed1` の前は `item.artists?.joinToString { it.name }` を使い、人物名がなければnullだった。同コミットで `artistDisplayText()` に統一し、「この一覧では省略されている」と「情報が不明」を区別しなくなった。`git blame` と同コミットの親の表示コードで確認。

また、副題の年を直接 `toIntOrNull()` に渡しているため、`2023年` は数値化できず年表示も欠ける。これは人物名の問題とは別の表示不足。

修正時はカード内の省略を考慮し、対象アルバムで確認済みの人物情報を使うか、情報がない間は人物欄を省略するなどの扱いが必要。コンピレーション・共演等があり得るため、親ページの人物をアルバムの唯一のアーティストとして無条件にDBへ登録する修正にはしない。

## 2. THE GREATEST UNKNOWNを開いても何も表示されない

### 対象と実応答

- アルバムID：`MPREb_r2SiObOBunG`
- アーティスト：`King Gnu` / `UC9tx_HmhNISUR39M29r9a8A`
- 応答が示したプレイリストID：`OLAK5uy_kaiYU1pnGQeY_UvpyVEbsgYQVZyBgjvX8`
- 応答の正規URL：[YouTube Musicの対象アルバム](https://music.youtube.com/playlist?list=OLAK5uy_kaiYU1pnGQeY_UvpyVEbsgYQVZyBgjvX8)

`browse(MPREb_r2SiObOBunG)` はHTTP 200で、次を返した。

- `musicResponsiveHeaderRenderer`：作品名、King Gnuと人物ID、画像、年。
- `secondaryContents...musicShelfRenderer.contents`：**21曲の行**。
- アーティストページから渡された `params` 付きでも同じヘッダー・21行が返った。今回の空白を単にparams欠落のせいとはしない。

しかし、現行 `YouTube.album()` はヘッダーを読んだ後、`albumSongs(playlistId)` を必須で呼ぶ（`innertube/YouTube.kt:281–304`）。その中で `browse(VL + playlistId)` を要求し、別の形式 `musicPlaylistShelfRenderer` を必須としている（`:309–317`）。

今回 `browse(VLOLAK5uy_kaiYU1pnGQeY_UvpyVEbsgYQVZyBgjvX8)` はHTTP 200／834 bytesで、トップレベルに `responseContext`・`trackingParams`・`microformat` だけを返し、`contents` がなかった。**通信成功でも `:317` の非null要求を満たさず、`albumSongs()` が失敗し、`:299` の `getOrThrow()` でアルバム全体も失敗する。** 最初に取得済みのヘッダー・21行を画面へ渡せない。

同じアーティストの `CEREMONY` のプレイリストIDで比較しても、別取得は834 bytes／contentsなしだった。少なくともこの取得条件では、問題をTHE GREATEST UNKNOWNだけに限定できない。

`app/viewmodels/AlbumViewModel.kt:32–52` は失敗すると `isLoading=false` にしてログを出すが、エラー状態・再試行操作を持たない。`app/ui/screens/AlbumScreen.kt:191,529` は「アルバムと曲があれば表示、なければ読み込み中だけ仮表示」となっているため、既存の曲一覧がない場合、失敗後は内容が空白になる。アルバム情報だけ残っていて曲が0件の場合もヘッダーごと表示しない。

### 修正案を単純化してはいけない点

`AlbumPage.getSongs(response, album)` という最初の `musicShelfRenderer` を読める補助関数は存在するが、現行の `YouTube.album()` から使っていない。

ただし、今回の21行中13行はグレー表示・Premium案内で、タイトルの再生先に `MUSIC_VIDEO` 型情報がない。`AlbumPage.getSong()` は `PageHelper.extractRuns(..., "MUSIC_VIDEO")` でタイトルを取るため、単に補助関数へ切り替えても13行を落とす（`innertube/pages/AlbumPage.kt:83–99`、`PageHelper.kt:31–45`）。残りにはMVの再生先も含まれる。

したがって「21曲の一覧を表示すること」と「各曲が同じ音源として再生可能であること」を分け、非再生行の題名・曲ID・状態を保持する必要がある。今回音声は再生しておらず、全21曲の再生可否やユーザーの契約条件は判定していない。

## 3. 検索に出ない理由

`THE GREATEST UNKNOWN` で以下4条件を取得した。すべてHTTP 200で、**同じKing GnuのアルバムIDが応答に含まれていた**。

| 検索 | 日本語JP | 英語JP |
| --- | --- | --- |
| アルバム指定 | 先頭のアルバム項目に存在 | 先頭のアルバム項目に存在 |
| すべて | 最上位カードに存在 | 最上位カードに存在 |

いずれも再生用の箇所が `watchEndpoint` / `watchPlaylistEndpoint` ではなく、`showDialogCommand` の `panelId=PApremium_upsell` になっていた。閲覧用のアルバムID、作品名、King Gnuの人物情報・画像はあるが、そこには `playlistId` がない。

- アルバム指定：`innertube/pages/SearchPage.kt:80–85` は再生用playlistIdがないと `return null`。
- すべての最上位カード：`innertube/pages/SearchSummaryPage.kt:75–80` も同じ条件で `return null`。
- 通常のアルバム検索行：同ファイル`:172–176`にも同様の必須条件がある。
- `NavigationEndpoint` モデルには `showDialogCommand` を保持する項目がない（`innertube/models/NavigationEndpoint.kt:6–25`）。
- 呼び出し側の `mapNotNull` でnullの項目は一覧から除外される（`innertube/YouTube.kt:248–254`ほか）。

日英の両方で既に落ちるため、後段の二言語検索の統合では復活しない。これは検索語が間違っている／YouTubeの検索結果にそもそも存在しない、という問題ではなく、**再生の情報が欠けたアルバムを閲覧対象からも除外するモデル・パーサーの問題**。

Premium案内が返ったのは今回の匿名要求での観測。ユーザーがPremiumでない、契約すれば必ず解消する、対象の全曲がPremium限定である、とは断定しない。

## 4. 確認結果・証跡

- 公開API：WEB_REMIX `1.20250310.01.00`、地域JP、日本語／英語、Cookie・アカウント情報なし。計11要求、HTTP 200。
- 最初の通常実行はサンドボックスのソケット制限で失敗。読み取り用のネットワーク権限を用いて成功した。拒否の迂回やユーザー認証値の利用は行っていない。
- 再利用した要求処理：`build/artist-response-research-20260905/probe.py`。保存先を今回専用の `build/artist-album-investigation/` に指定し、以前の証跡を上書きしていない。
- 実行：Python 3.10で `run_probe.py search-jobs.json`、`browse-jobs.json`、`detail-jobs.json`、`comparison-jobs.json`。各組3並列以下、約1～2秒。Gradleを起動していない。
- `export_evidence.py` で今回の応答を抽出・検査：アルバム棚5件、シングル棚10件、一覧29件の副題条件、日英4検索の除外条件、アルバム2要求の21行と13行の再生先欠如、プレイリスト2要求のcontents欠如を確認。
- 追跡対象の証跡：[応答抜粋JSON](../../research/2026-09-07-artist-album-excerpts.json)。要求ごとの時刻・HTTP結果・元JSONのSHA-256を含む。訪問者ID・追跡パラメータは抜粋へ含めない。
- RAW応答と要求定義は `build/artist-album-investigation/`。同名の別作品・別人物を対象アルバムと混同せず、browseIdで照合した。
- ソース分岐と実応答の照合であり、Kotlinパーサーの実行テスト・Android画面の再現ではない。ユーザー端末の現在の設定・認証条件でも同一応答になるかは未確認。
- 製品コード、DB、設定、APKは変更なし。ビルド・エミュレータ操作・再生・ダウンロードを実施していない。

## 5. 修正時の対象と合格条件

今回の調査では未実装。

1. 一覧での人物名省略を扱い、「不明」の一律表示を解消する。今回のアルバム棚・シングル／EP一覧に加え、人物名が実際に提供されるカードと共演アルバムを確認する。
2. アルバムの閲覧ID・ヘッダー・曲一覧と、再生先・制限状態を分けて保持する。別プレイリスト取得が空でも取得済みの作品情報と曲一覧を失わず、曲行の欠落理由を表示できるようにする。
3. 再生用playlistIdがなくても検索にアルバムを残す。再生可能と偽らず、詳細表示や再試行へつなぐ。既存の再生ボタン・共有処理が非nullのplaylistIdを前提としているため、モデル変更時は利用箇所も合わせて確認する。
4. 空白画面をなくし、通信失敗・曲なし・利用制限・読み込み中を区別する。オンライン失敗時に既存の保存済み情報を消さない。
5. THE GREATEST UNKNOWNを日英のすべて／アルバム検索から開き、21行が理由なく欠落しないこと、取得失敗時に画面が空白にならないことを確認する。再生は別条件として曲ID・音源／MV・制限状態を検証する。

ホーム調査で見つかった読み込み表示の不足と共通する設計上の問題はあるが、ホームの停止・バックアップ時のクラッシュと同一の直接原因とは判定していない。

## 6. 改修（調査後の追加依頼）

ユーザーから改修の依頼を受けて実装。上記の「未実装」「変更なし」は調査時点の記録。

- ユーザーの追加指定により、人物名が欠ける場合の表示省略はアーティスト画面内のアルバム／シングル／EPと、その一覧に限定。共通部品は明示指定がある場合だけ省略する。検索、ホーム、詳細などの既定表示と確認済み人物名を維持し、親人物を推測で補完しない。
- AlbumItemの再生用playlistIdを省略可能にして、日英のアルバム検索・全体検索の先頭カードから作品が消える条件を除去。閲覧IDは必須のまま。再生IDがないときはラジオを表示しない／ホームのランダム再生候補から除外。共有は作品のbrowse URLへフォールバック。
- アルバム閲覧応答内の曲棚を優先して読み、棚が存在しない場合のみ従来のプレイリスト取得へ進む。棚の継続を処理し、循環・棚欠如を検知する。タイトル、閲覧ID、ヘッダーの名前、21曲の順序を保持。年の日本語接尾辞にも対応。
- 再生先が制限案内だけの行も名前と曲IDを残し、詳細上で制限を表示。制限された行は詳細の再生・全曲選択・ダウンロード候補から除外する（保存済み／ローカル曲は利用可能）。再生可能なMVをアルバム音源と偽って置き換えない。利用制限は今回の応答に対する一時状態で、DBの恒久属性にしない。
- アルバム取得を30秒で打ち切り、DB書込みの完了を待って読み込み状態を解除。失敗／空の曲一覧に再試行を表示し、取得失敗だけで既存アルバムを削除しない。

合格条件：既存画面の人物名表示を維持、日英検索に対象作品が残る、全21行が理由なく欠落しない、失敗時の空白画面をなくすこと。匿名取得で13行が制限されることはユーザーの認証時の権限を示さない。最終ソースに対する検証結果は下へ追記する。

### 最終検証結果

対象は `7bc8bfa5847652d9cd6e7883de562574be36147e` ＋今回の未コミット差分。DB schema/versionは変更していない。

| 確認 | 結果 | 根拠 |
| --- | --- | --- |
| 保存応答によるパーサー回帰 | PASS | `AlbumBrowsingParsingTest` 4件。日英の検索行／先頭カード、21曲・13制限行、playlist側の棚欠如、年と人物名欠如の区別 |
| 従来のアーティスト解析と検索 | PASS | `ArtistCreditTest` 31件、`SearchSummaryParsingTest` 3件、`MetadataRequestLocaleTest` 6件 |
| 表示省略の限定 | PASS | `ArtistDisplayTest` 10件。明示指定のない画面は従来の表示、確認済みの名前はどちらでも保持 |
| アーティスト画面のアルバム／シングル・EP | PASS | King Gnuの両棚とシングル・EP一覧で、不明表示を省略して作品名・年を保持。詳細画面と検索結果にはKing Gnuを表示 |
| 検索→対象アルバム | PASS | 最終APKの「すべて」「アルバム」検索にTHE GREATEST UNKNOWNがあり、アルバム検索から詳細を開くと作品名・King Gnu・2023・21曲を表示 |
| アーティスト→対象アルバム | PASS | アルバム棚から同じ閲覧IDの詳細を表示。別プレイリストの空応答で曲一覧を失わない |
| 取得失敗と回復 | PASS | 最終APKでCEREMONYの作品名を選択した直後に通信断。空白ではなくエラー／再試行を表示。通信復旧後の再試行でKing Gnu・2020・12曲まで回復 |

実行：`:innertube:test --tests '*AlbumBrowsingParsingTest' --tests '*ArtistCreditTest' --tests '*SearchSummaryParsingTest' --tests '*MetadataRequestLocaleTest'`（44件、失敗0、本体約0.741秒）、`:app:testCoreDebugUnitTest --tests '*ArtistDisplayTest'`（10件、失敗0、本体約0.061秒）。ホーム・バックアップの関連検証と合わせて単体61件＋Android実行4件＝65件、全件成功。

通常画面はread-onlyのPixel 9 / API 35、arm64変換、日本語・360dp幅。最終coreDebug / arm64-v8a APKのSHA-256は `aba00e43c05fcfcada4c33106fa95cc91cc4f640753662c282c99998b2c2d6af`。検索と詳細・エラー回復はこのAPKで確認。棚・一覧の確認後の変更は制限行の文言のみで、最終APKでもKing Gnuのアルバム棚を再確認した。シングル／EPの画面画像はその文言変更前の候補APKの記録。

画面記録：[アルバム棚](../images/2026-09-07-home-album/artist-albums-fixed.png)、[シングル・EP棚](../images/2026-09-07-home-album/artist-singles-fixed.png)、[すべて検索](../images/2026-09-07-home-album/album-search-all-fixed.png)、[アルバム検索](../images/2026-09-07-home-album/album-search-filter-fixed.png)、[21曲の詳細](../images/2026-09-07-home-album/album-tracks-fixed.png)、[取得失敗](../images/2026-09-07-home-album/album-load-error-fixed.png)、[再試行後](../images/2026-09-07-home-album/album-retry-fixed.png)。赤い端末情報は既存のdebug版表示で、今回の製品UI変更ではない。

制限・未確認：画面確認は匿名取得。今回の端末応答では全曲が制限表示になっており、保存したJP応答の13行とは条件が異なる。ユーザーの認証・地域条件での再生、音源とMVの実際の再生、ダウンロードは未検証。再生制限の解除を実装したものではない。アルバムの30秒タイムアウトの実時間確認は未実施（通信断時の停止と回復を確認）。通信・画面設定を戻し、保存状態を更新せず検証エミュレータを終了した。

### release APKの追加確認

同じ最終ソースからcoreRelease / arm64-v8aを生成し、署名・構成・起動・バックアップ作成を確認。SHA-256は`58cb58071640ab34a06895a149624d9f20e1587dd9f8b45a2b447380ae02ed30`。ただしreleaseのオンライン取得は検証環境のTLS証明書信頼エラーでBLOCKED。上記のdebug画面確認をreleaseの合格へ読み替えない。配布パスと検証の詳細は[ホーム・バックアップ記録のrelease追加確認](2026-09-07-home-keep-listening-investigation.md#release-apkの追加確認)を参照。
