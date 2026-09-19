# 2026-09-19: アルバムの先頭曲が重複する問題

## 依頼・合格条件

- Chop Suey! とNirvanaのアルバムで先頭曲が2つ目にも表示される。
- 成功取得したアルバムの正式な曲ID・順序で表示/一括再生し、古い曲IDや単曲再生由来の関連が割り込まないこと。
- 曲名だけで別IDを統合しない。保存済み曲・DL情報・いいね・履歴・プレイリスト・アルバムへの関連を維持する。
- 前のアーティスト紐付けUI変更は保ったまま、本件は別の記録で扱う。

## 基点・確認した原因

- 基点: `14e73317` + 前のUI変更（未コミット）。
- ユーザー提供 `OuterTune_27_20260919212735.backup` の `song.db` だけを診断用ディレクトリへ展開し、読取専用で確認。元ファイル/設定データは変更・使用していない。
- バックアップSHA-256: `5cb75e95b76c4bda1916a1d611d0985201e14d284b8e40fc71ba276f35c057c8`、DB27。
- Chop Suey! (`MPREb_hscoNGfCKJW`) はsongCount=2に対しmap3件。`-cid1qHuy_U` と `CSvFpBOe8eY` が同じ曲名/index=0。
- Nevermind (`MPREb_jPOYfjGgApr`) はsongCount=13に対しmap14件。`ljUtuoFt-8c` と `hTWKbfoikeg` が同じ曲名/index=0。
- `insert(MediaMetadata)` は単曲のalbum関連にもindex=0を設定し、`upsert(AlbumPage)` は受信した曲を追加/更新するだけで過去の収録順を置換していない。表示はその全mapを読み込む。
- 匿名JA/JPの現API応答では、Chop Suey! の正式2曲は `-cid1qHuy_U` / `cmna-FAi6t0`。Nevermindは `ljUtuoFt-8c` を先頭に13曲。DBの残存IDは現リストに含まれず、画面だけの二重描画ではない。旧IDが作られた当時のAPI応答/操作順は未確認。

## 方針

- 既存mapのindex=-1を「アルバムとの関連のみ、正式な収録順は未確認」として使う。ローカル曲は従来どおり。
- 成功取得した全曲リストを一つのtransactionで反映し、過去のmapを-1、現在の収録曲を0からの連番へ更新。
- `AlbumEntity.hasTrackList` で正式な曲リストを取得済みか区別する。取得済みオンラインアルバムの表示・一括操作では、その曲だけを明示的に並べる。旧DBを含む未取得の仮アルバムは関連曲を引き続き表示。
- 関連mapを消さず、別IDの保存曲やアルバムのライブラリ掲載条件を維持する。空のアルバム応答は既存情報を変更しない。
- DB27→28を自動移行し、`album.hasTrackList INTEGER NOT NULL DEFAULT 0` のみ追加。既存の全列・キー・インデックス・8ビューは同一。旧DBのアルバムはオンラインで初めて曲リストを正常取得した時に補正する。旧26の初期化方針は変更しない。
- ブックマーク等が古いAlbumEntityを持っていても、取得済みフラグを巻き戻さない。

## 検証

提供データやAPIの生応答はgit管理外の `build/diagnostics/album-first-track-20260919` に限定する。テストassetは匿名API応答のアルバム部分だけで、ユーザーDBや設定は含まない。

- PASS: `ManualArtistDisplayProjectionTest` 6件、`LocalAlbumIdentityTest` 16件（テスト本体合計0.211秒）。
- PASS: Androidの `AlbumTrackMembershipTest`、`AlbumArtistGroupingDatabaseTest`、`AlbumOriginalContextEligibilityTest`、`AlbumMetadataRepositoryTest` 合計31件。旧重複、単曲からの追加、正式IDの変更、同名別ID、明示的な順序、空応答、未取得アルバム、ローカル曲、再読込、古いブックマーク更新を確認。
- PASS: `ProvidedAlbumTrackRepairTest` 1件（2.275秒）。提供DBのコピーをRoomで27→28へ移行し、全テーブルの元列・元行が等しいことを確認。実API由来fixtureを本番パーサー・更新処理に通し、Chop Suey! 2曲 / Nevermind 13曲、旧IDの関連保持、DBを閉じて再度開いた結果を検証。
- PASS: エクスポートした補正済みDBをPC上でも整合性・外部キー確認。元327曲の保存状態とユーザーデータ13テーブルを保持。提供backupのSHA-256も不変。
- テスト転送時の記録: 初回はADB標準入力で送ったDBコピーのハッシュが一致せず、提供DBテストのみ開始時に失敗した（残り31件は合格）。`adb push` と端末内コピーに変え、元DBとのSHA-256一致後に該当1件だけ再実行して合格。元backup/抽出DBは正常で変更なし。
- ビルド途中の記録: debug/releaseのKSPが新schema JSONを同時生成し、debug側で一時的なEOFエラー。単一Gradleの `--no-parallel --max-workers=1` で直列化。途中版のAPKは最終成果物として扱わない。

- PASS: 最終ソースを次の単一Gradleコマンドでビルド（5分28秒、単体テストを含む）。既存の非null警告とCompose mapping警告あり、ビルド成功。

  ```powershell
  .\gradlew.bat :app:testCoreDebugUnitTest --tests '*ManualArtistDisplayProjectionTest' --tests '*LocalAlbumIdentityTest' :app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease --no-parallel --max-workers=1 --console=plain
  ```

- Androidテストは最終debug/test APKを `Pixel_9_API_35` / `emulator-5556` にインストールし、`am instrument -w -r -e class <上記5クラス> -e verifyProvidedAlbumTracks true com.dd3boh.outertune.debug.test/androidx.test.runner.AndroidJUnitRunner`。上記転送修正後は提供DBクラスのみ再実行。テスト本体は初回7.812秒＋該当1件2.275秒。エミュレータはx86_64上のarm64互換実行で、APKに他ABIは追加していない。
- PASS: 最終coreReleaseをインストールし、テストで本番処理を通した補正済みDBを、設定を含まない検証専用backupから復元。再起動後にライブラリ→各アルバムで曲順を直接確認。
  - [Chop Suey!](../images/2026-09-19-album-first-track/chop-suey.png): 2曲、1曲目Chop Suey!、2曲目Sugar。先頭曲は1行のみ。
  - [Nevermind先頭](../images/2026-09-19-album-first-track/nevermind.png): 13曲、1曲目Smells Like Teen Spirit、2曲目In Bloom。
  - [Nevermind末尾](../images/2026-09-19-album-first-track/nevermind-bottom.png): 13曲目Endless, Namelessまで確認。
- releaseのオンライン通信は既知の証明書制約により未確認。オフラインで再取得失敗しても補正済みリストは保持される。画像の読み込み失敗表示・アートワーク未表示はこの条件による。
- Nevermindの新規音源IDで「アーティスト不明」が出ることを観測。フルAPI応答とfixtureを全15曲で比較し、曲bylineが空・menuのartist browseIdも空で同一と確認。曲名やアルバムアーティストからの推測は加えていない。曲クレジットの追加通信は今回のオフラインrelease検証対象外。

## 成果物

- `build/distributions/OuterTune-album-tracks-20260919-arm64.apk`
- `coreRelease` / `arm64-v8a` のみ、0.10.2-b1 (71)、DB28。前のアーティスト紐付けUI変更も含む。
- SHA-256: `1e93828a2c9a723c878c34e1c738d1eee7a0e354f8b0fb6b115b61f0e7fec185`
- APK署名検証PASS（v2）、native ABI検査PASS。mappingは同名prefixの `-mapping.txt` に保存。
- 最終差分 `git diff --check` PASS。ユーザーの実機・元backupは変更していない。
- インストール後、ネット接続中に対象アルバムを一度開き、正式な曲リストが取得されると既存の重複も補正される。
