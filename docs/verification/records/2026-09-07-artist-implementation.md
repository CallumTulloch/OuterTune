# アーティスト名の解析・保存・表示の実装検証

2026-09-07。対象ブランチ `restart/artist-20260905`、開始HEAD `a80f2615`。
以下は開始HEADに実装変更を加えた作業ツリーの検証記録であり、開始HEADだけの結果ではない。
[仕様案](../../artist-display-spec-draft.md) と [実応答調査](2026-09-05-artist-response-investigation.md) を参照。

## 対象

- YouTube Musicの音楽トラック。元の表記、採用済みの個別名、内部ID、確認済みオンラインIDを分けて保持する。
- RAW・部分解析・全個別名解析、IDなしと後着ID、検索・再生・保存後の表示、アルバム自身のクレジット。
- 同一曲の追加取得の共有、未保存曲の補完、保存済み曲の修復、遅い応答や薄い情報による退行の防止。
- schema 23。既存データの移行は不要というユーザー条件に従い、移行経路のない旧DBは再作成する。
- 動画由来の情報欠損とフォルダ音源のタグ分割は別課題。APKとTagLibの対象ABIは `arm64-v8a` のみ。

## ビルドと自動テスト

| 最終実行 | 結果 | 証拠・範囲 |
| --- | --- | --- |
| coreDebug APK・対象単体テスト | 成功、1分8秒 | `build/artist-debug-build-3.log`。時間はビルド等を含む |
| innertube対象単体テスト | 34件成功、失敗・除外0件 | ArtistCredit 28、ArtistMetadataParsing 3、SearchSummaryParsing 3 |
| app対象単体テスト | 32件成功、失敗・除外0件 | ArtistCreditPersistence 7、DatabaseDao 4、ArtistIdentity 2、DownloadUtil 10、ArtistDisplay 9 |
| Android上の実Room・repositoryテスト | 12件成功、テスト本体1.732秒 | `build/artist-instrumentation-3.log`。DB 6件、repository 6件 |
| coreRelease APK | 成功、3分32秒 | `build/artist-release-build.log`。出力メタデータとZIP内のnative libraryは `arm64-v8a` のみ |

単体テストの詳細は `innertube/build/test-results/test/` と `app/build/test-results/testCoreDebugUnitTest/`。
対象テストの成功を、プロジェクト全テストの成功とは扱わない。

実応答由来のfixtureでは、対象曲の結合された表記、通常の二人分のRun、`Earth, Wind & Fire` のように名前に記号を含む入力を確認した。
合成した境界入力では、部分解析で未確認部分を消さないこと、IDの有無の組合せ、同じIDに異なる表記が来る場合、根拠が矛盾する場合などを検証した。
合成入力の成功は、その異常な応答が実サービスで観測されたという意味ではない。

Androidテストでは実Room/SQLiteを使用し、次を確認した。追加取得には制御可能な応答を注入しており、ライブ通信の網羅試験ではない。

- RAWから部分・全個別名への更新、後着IDによる既存人物との統合、旧IDからの参照、曲・アルバムの関連順序とユーザー状態の維持。
- 薄い再保存と古い曲エンティティの更新で採用済み情報を失わないこと、関係の欠落の修復、DBを開き直した後の保持。
- 本番と共通のDB builderによる新規作成・現行DB再開・version 22からの再作成。
- 同一曲の同時要求の共有、遅い薄い応答と古い言語・アカウント条件の応答を採用しないこと。
- 検索だけでは曲をDB登録せず、後から保存された曲へ永続キャッシュを適用すること。同期キャッシュ参照でも強い元情報と確定済み内部IDを保つこと。
- 後から届くアルバム情報を曲アーティストと混同せず、保存・再起動後も保持すること。

## 最終debug APKの画面と保存結果

Pixel 9・API 35の検証用エミュレータで新規データから確認した。
イメージはx86_64で、`arm64-v8a` と `libndk_translation` の対応を確認してarm64 APKを実行した。arm64実機での確認とは区別する。

対象曲は `TSZhKssbW2g`（It Will Fit Me Just As Well・特别版）、アルバムは `MPREb_NUdafp1DlA5`。

| 操作・観察 | 確認した結果 |
| --- | --- |
| 曲IDで検索し、アーティスト情報を開く | `翟锦彦` と `8082Audio` の二名。オンラインIDが確認できたのは8082Audioだけ |
| IDなし人物の内部ページを開く | 選択元の未保存曲を表示。この段階のDBは曲0件・Artist 0件 |
| 再生してプレイヤーを開く | 再生位置0:01、長さ3:57を表示。二名を個別に選択できる |
| アルバムへ移動 | 入口が表示され、実際のアルバム画面に2曲を表示 |
| 対象曲を保存 | 曲の原文と採用済み二名、二名への関連、アルバムID・名前をDBに保持。結合名のArtistは対象曲に登録されない |
| 8082Audioのページを開く | 画像を表示。対象曲の二名の表記を維持 |
| アプリを強制終了して再起動 | ライブラリで対象の二名を確認。IDなし内部ページに保存曲を表示し、DBでも対象曲の同じ内部ID・二名の順序・保存状態を維持 |

画面記録は `build/artist-ui-final-*.xml`。
保存状態の照合は `build/artist-db-final-unsaved/result.json`、`build/artist-db-final-saved/result.json`、`build/artist-db-final-restart/result.json`。
全DB件数は順に曲0・Artist 0、曲1・Artist 2、曲2・Artist 3。アルバム閲覧で取得した伴奏曲もDBに保存されるため、全DB件数と対象曲の二名の関連を区別する。

これは対象曲での画面操作と保存結果の観察であり、全言語・全曲・全画面の遷移を網羅した結果ではない。
部分解析、情報衝突、通知や開いたメニューへ補完が届くタイミングなどの全組合せは、上記の自動テストと同じ範囲まで画面で実証したとは扱わない。
通常例の追加端末確認（英語への切替、通知許可、Septemberのdeep link起動）は、自動承認レビューがコマンド全体を拒否し未実行となった。提示理由は `blocked by policy` のみで、迂回はしていない。通常のデュエットとEarth, Wind & Fireはfixture・合成入力の自動テストの結果に限定し、端末確認済みとはしない。

## release成果物と残る確認

- APK: `app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`
- SHA-256: `FDFBD5F426F21BB475BEBD1C947F31DFED7B5E2102CD35B63D4D7AC9E77D5AF8`
- APK v2署名の検証に成功。エミュレータで起動・日本語の初期設定画面を確認した。
- releaseでのオンライン検索は `Trust anchor for certification path not found` により停止。R8による起動クラッシュとしては観測していない。
- 既存のdebug専用設定 `app/src/debug/res/xml/debug_network_security_config.xml` はsystemと `@raw/avast_web_mail_shield_root` を信頼する。本PCのAvast通信検査用ルートで、今回の変更ではない。releaseにはこのルートが含まれず、このPC経由の通信で上記エラーが出た。AVDのglobal proxy値はnull。本番TLS設定とPCのAvast設定は変更していない。
- このrelease APKでの実機の検索・アーティストメニュー確認をユーザーに依頼済みで、結果待ち。releaseのネットワーク経由の画面確認は未完了とする。debugでの観察をreleaseの確認済みとは扱わない。

## 途中で見つけて修正した点

- 初回appビルドは依存取得時のPKIX証明書エラーでコンパイル前に停止。公開ルート証明書とJBR既定証明書をGit無視対象の専用truststoreへ格納し、そのGradle実行に指定して解消した。TLS検証やグローバル設定は変更していない。
- 中間APKの通常起動で、Migration 21→22と22限定の破壊的再作成指定が競合して停止した。DB builderを共通化し、移行経路がない場合の再作成へ修正。追加の本番builderテストと最終APKの起動で確認した。
- 古い曲エンティティによる採用済みクレジットの上書き、欠けているアルバムのキュー取得の共有、薄いキャッシュによる同期表示の退行を修正し、関連する回帰テストを追加した。

長い実行ログ、画面XML、スクリーンショット、DB抽出物、APKはGit無視対象のビルド出力に置き、この記録へ集約する。ソースや引継ぎ資料としてコミットするものと混同しない。

検証に使用したread-only・headlessエミュレータは停止し、ADB接続端末がなくなったことを確認した。元のAVD保存状態は変更していない。実装差分は未コミットで保持している。
