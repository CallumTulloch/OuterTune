# 同じアルバム内のIDなしアーティストを集約する

## 合意した仕様・対象

- 開始コミット `c03b6abc`。ユーザーは提案したアルバム単位の集約を承認し、既存DBは初期化前提でよいと明示した。DBの互換移行は作成しない。
- 特别版アルバム `MPREb_NUdafp1DlA5` の通常版 `TSZhKssbW2g` と伴奏 `xDWhuDRnevk` で、IDのない「翟锦彦」が曲ごとの人物行になる現象を前回再現した。
- 同じオンラインのアルバムID・保存された同じ個別名を持つIDなしの人物を、共通の暫定的な統合先として扱う。文字列の区切りから個別名を推測しない。別アルバムやフォルダ音源の同名人物は自動集約しない。
- 確認済みのオンラインIDとユーザーによるフォルダ紐付けは従来どおり優先する。オンラインIDのないグループをYouTubeの人物IDとして扱わない。
- 曲ごとの元クレジット・内部参照・再生情報は残す。後から各曲で異なるオンラインIDが確認された場合には、その曲の関係だけを分けられるようにする。
- アルバムが不明な曲は曲単位の参照を維持し、アルバム判明後に共通の規則へ反映する。単曲追加、全曲追加、補完・再取得で入口を分岐させない。
- 一覧、検索、人物内の曲、人物への移動、プレイヤーを同じ統合先へ揃える。同名・同一アルバムでも別人である可能性はあり、この集約はオンライン本人確認を意味しない。

## 合格条件

1. 指定アルバムを新規DBへ全曲追加すると、翟锦彦1件・2曲、8082Audio1件・2曲になる。元の2個のIDなし参照は保存する。
2. 同じ条件で多数の曲や逆順追加を行っても表示代表が増えず、削除・再追加・再起動後も保持する。
3. 別アルバムの同名人物、確認済みの異なるオンラインID、フォルダの同名人物を混同しない。
4. アルバムの後着情報と後着オンラインID、通信失敗・言語の再取得で無関係の人物情報を置き換えない。
5. プレイヤーとライブラリの双方から共通の人物画面へ移動し、正しい曲数・表記・画像の有無を確認する。
6. DBの新規作成と旧版からの初期化、最終coreRelease / arm64 APKの主要経路を確認する。

## 実装

- `ArtistEntity.albumGroupId` にアルバム単位の暫定グループを保持する。対象は確認済みのオンラインアルバムID（`MPRE...` または privately-owned release）と、既存の正規化処理を適用した個別名。`AG...` IDはこの組み合わせから安定的に生成する。生成したローカルアルバムIDや、未分解のRAW文字列を根拠にしない。
- 曲・アルバムの追加、個別クレジットの保存、後着アルバム情報、所属変更・削除の経路から同じ再計算へ入る。元の人物参照が複数アルバムにまたがり所属を一意に決められない場合は、暫定グループを推測しない。
- `artist_identity` / `artist_display` / 曲・アルバムの参照ビューで集約する。元の人物行、曲別クレジットの `ref`、曲と人物の関係は物理統合しない。後からオンラインIDが判明した参照だけオンライン側を優先し、元の `albumGroupId` は由来として保持する。
- 共通の表示対応表を手動紐付け以外にも適用し、一覧・検索・曲/アルバムの人物表示・プレイヤー・旧参照からのページ移動を揃える。表示上の共通IDと実在するオンラインIDを区別し、暫定グループではオンラインの英語名補完・人物ページ取得を行わない。プレイヤー更新は表示メタデータだけを変更する。
- いいねは表示代表に保存する。暫定グループの全参照が後からオンラインIDへ移った場合、いいね済みの `AG...` 代表が0曲で残ることがある。本人の同一性を推測して別のオンライン人物へいいねを移さないための扱い。
- DBを26から27へ更新し、`app/schemas/com.dd3boh.outertune.db.InternalDatabase/27.json` を生成した。26→27の互換移行は追加せず、ユーザーが承認した初期化方針とアプリの destructive fallback を使用する。既存DBの内容を新方式へ引き継いだとは扱わない。

## 検証結果

対象は `c03b6abc` に今回の未コミット差分を加えたもの。以下の途中失敗の後はテストの生成データ・期待値のみを修正し、製品コードは変更していない。GradleとADBはrootが逐次実行し、実機は操作していない。今回の合格条件は以下の範囲で確認済み。

### 自動テスト

- **PASS：JVM 45件・9クラス。** `ArtistCreditPersistenceTest` 7、`DatabaseDaoTest` 4、`ArtistIdentityTest` 2、`ArtistDisplayRepositoryTest` 1、`MetadataDisplaySelectionTest` 8、`AlbumArtistDisplayProjectionTest` 4、`ArtistDisplayProjectionTest` 3、`ArtistDisplayTest` 10、`ManualArtistDisplayProjectionTest` 6。失敗・スキップ0。最終debug/test APK生成を含むGradle処理16秒、XMLに記録された各クラスのテスト時間の合計0.253秒。ログは `build/album-groups-debug-build-final.log`、結果XMLは `app/build/test-results/testCoreDebugUnitTest/`。
- **PASS：Androidの対象49件。** 初回49件のうち45件成功、4件失敗（16.449秒）。失敗した3クラスの期待値を修正し、26件を再実行してすべて成功（6.105秒）。対象は `AlbumArtistGroupingDatabaseTest` 10、`ArtistGroupingDatabaseTest` 7、`LocalArtistLinkDatabaseTest` 8、`ArtistCreditDatabaseTest` 9、`ContentSourceFilterDatabaseTest` 5、`ArtistImageDatabaseTest` 1、`ArtistGroupingPerformanceTest` 1、`ArtistPageLinkTest` 6、`LocalArtistProjectionPlaybackTest` 2。再実行した26件を別の新規テストとして加算しない。ログは `build/album-groups-device-tests.log` と `build/album-groups-device-tests-final.log`。
- Androidの確認には、捕捉済みの特别版応答を実パーサーと保存経路へ通すケース、多数曲・逆順・再追加、別アルバム/既知ID/ローカル人物の分離、後着所属・後着オンラインID、RAW/不完全なヘッダー、旧26および旧22からの初期化、現行DBの再オープンを含む。
- ページテストは、グループIDと元の曲別参照の両方で同じ保存曲を開き、1件だけオンラインIDが判明した際の分離、無関係な画像を借用しないこと、暫定グループでオンライン人物取得を呼ばないことを確認した。再生テストは合成WAVを音声源にして、表示更新中も実際のExoPlayerの位置が進み、元のtag・URI・cache key・人物参照を保持することを確認した。これは下記の実通信での曲再生とは別の検証である。

### 途中失敗と修正内容

初回失敗を最初から成功だったとは扱わない。製品コードの動作を旧テストに戻す変更はしていない。

| 失敗 | 原因・修正 | 後続結果 |
| --- | --- | --- |
| 初回debugビルド、1分25秒 | 新規テストの `SongEntity` 生成4箇所で必須の `localPath` 引数が欠けていた。オンライン曲の3箇所へnull、フォルダ曲の1箇所へテスト用パスを追加した。 | debug/JVM/Android test APK生成成功。 |
| `albumHeaderSharesTheGroupOnlyAfterItsOwnIndividualCreditIsConfirmed` | ヘッダー情報が一切ない状態でも、既存のアルバム一覧は曲の参加関係を補助的に使える。テストがこの既存仕様を無視して一覧を空と期待していた。ヘッダー未保存・実際のRAWヘッダー保存・個別名確認済みを分けて検証する `albumHeaderEvidenceControlsTheGroupAlbumListWithoutReplacingTrackCredits` へ修正した。 | 対象クラス10件すべて成功。 |
| `anExistingOnlineSurrogateAndAliasJoinTheSameGroupWithoutRewritingTheirRelations` | 表示対応表を手動紐付けだけと見なす旧期待値だった。今回、オンラインIDへ解決済みの元参照 `LA-online-stable-ref` も同じ対応表へ含めるため、期待する参照集合へ追加した。 | 対象クラス7件すべて成功。 |
| `lateIdMovesOtherTracksAndAlbumCreditsToTheStableIdentity` | 既存オンライン行を削除し、他曲・アルバムの元参照までLA側へ書き換える物理統合を期待していた。今回の合意に合わせて、元情報と別曲の関係を保持しつつUC側の表示代表へ集約すること、両方の経路で2曲・両アルバム・既存のいいね/画像を参照することへ変更した。 | 改名後の `lateIdProjectsTracksAndAlbumCreditsTogetherWithoutRewritingTheirSourceRefs` を含め、対象クラス9件すべて成功。 |
| `partialCompleteAndLateIdPreserveTrackAndMergeExistingIdentity` | 同様にUC参照をLAへ物理転送する旧期待値だった。元の曲別refとオンライン行を保持し、表示上のUC代表を使用する期待値へ変更した。 | 改名後の `partialCompleteAndLateIdPreserveTrackAndProjectTheExistingOnlineIdentity` 成功。 |

### debugでの実画面・実通信確認

エージェントが `coreDebug / arm64-v8a` を使用し、エミュレータのアプリデータを初期化した新規DBで確認した。ログインせず、対象のオンラインアルバムのプレイリストを開き、通常版・伴奏版の2曲のクレジットが解決してから全曲をライブラリへ追加した。

- **PASS（debugの実画面）：** アーティスト一覧は「翟锦彦」1件・2曲、「8082Audio」1件・2曲。人物画面で通常版と伴奏版の両方を確認した。
- **PASS（debugの実通信）：** 通常版のストリームが実際にPLAYINGになったことを確認した。プレイヤーの「翟锦彦」から同じグループ画面へ移動し、2曲を確認した。
- **PASS（debugの検索）：** ローカル検索 `It Will Fit Me` で通常版と伴奏版の2曲がヒットした。
- DB実測は `build/diagnostics/album-groups-20260913/export/relations.json`。通常版 `TSZhKssbW2g` の元人物IDは `LA56498d4e4b30c00f27a3c1001ec8067b`、伴奏 `xDWhuDRnevk` は `LAa14505d06d383ca94876af698abc9b52` のまま保持し、両方の `onlineId` はnull。共通の表示先だけが `AGdfb194996983bafaa228e4f121c0672f` となり、曲数2を返した。8082Audioは確認済みの `UChWKQRswWTLRXp98zmgHtdQ` に2曲を対応させていた。
- 画面・XML証跡は `build/diagnostics/album-groups-20260913/` の `06-library`、`07-group-page`、`08-playing`、`09-player`、`10-player-group`、`13-search-results`。検証用ログ・DB・画像はコミット対象外。

### 配布版・再起動・成果物

- **PASS：** `coreRelease / arm64-v8a` のビルド・lintVitalは3分2秒で成功。ログは `build/album-groups-release-build.log`。既存のaboutlibraries Compose mapping警告は出たがビルドは成功した。
- **PASS（debug、通信不可・再起動）：** Wi-Fi・モバイル通信を無効にし再起動。ホームの通信は失敗したが、保存アーティストは2件・各2曲を維持。証跡 `15-debug-offline-library`。
- **PASS（最終release実画面）：** 上記の匿名エミュレータで追加した対象2曲だけのDBを、端末内の復元画面から新規releaseへ読み込み、通信を無効にしたまま確認。アーティスト一覧は2件・各2曲、翟锦彦の人物画面には通常版と伴奏版の2曲。オンライン未確認の人物画像はプレースホルダーで、別人の画像を借用していない。証跡 `23-release-library`、`24-release-group`。
- **PASS（最終release操作・再起動）：** 通常版を曲メニューでライブラリから削除し再追加しても2件・各2曲。強制終了・再起動後も維持。保存されたキューのプレイヤーから「翟锦彦」を押すと同じ2曲の人物画面へ移動。証跡 `25`〜`30`。releaseでは通信を切って保存データ操作を検証し、実ストリーム再生の確認はdebugで行った。
- release検証用バックアップは、今回初期化した匿名debugアプリの対象2曲だけを検査して作成。ホスト上でSQLiteの単独DELETE journal DBへ出力し、整合性とDB27を確認した。ユーザーのバックアップ・資格情報は使用していない。これは製品の通常バックアップ経路全体を保証する試験ではない。既知の合成復元の観察は別記録 `2026-09-13-synthetic-restore-observation.md` のまま扱う。
- 最終APK：`build/distributions/OuterTune-album-artist-groups-20260913-arm64.apk`。署名v2検証成功、ZIP内のnative ABIは `arm64-v8a` のみ。SHA-256：`428ab5b6192940b17bd67f0ea8e94abaa0ad2e2af2ed150fffb6ac2d72d4586c`。対応する難読化対応表は同ディレクトリの `OuterTune-album-artist-groups-20260913-mapping.txt`。
- エミュレータは今回の `Pixel_9_API_35`・port5556・read-onlyインスタンスを使用。終了時に通信設定を戻し、起動時の親子プロセスとコマンドラインを照合して今回のインスタンスだけ停止。実端末は操作していない。
- 同じアルバムに同名の別人がいる可能性を完全には排除できない。暫定的な集約という今回の仕様上の制約は残る。後着オンラインIDの振り分けは個々の参照に限定し、名前一致から別人の確認情報を配布しない。
