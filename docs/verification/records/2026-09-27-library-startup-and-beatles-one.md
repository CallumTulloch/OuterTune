# 2026-09-27: 起動後のライブラリ待機と Beatles「1」の原題

## 依頼と期待値

- ユーザー確認: Nirvanaはアルバム単位で英語表示。Beatles「1」の一部、例「エリナー・リグビー」は日本語のまま。
- 最優先: アプリを閉じてから開くとライブラリの曲・アーティストの読み込みに約20秒かかる。曲数は未確認。
- 前回の途中作業を別LLMへ引き継いだ差分についても独立レビューする。
- 保存済みの確定名で速やかに一覧を表示し、通信・再評価を初回表示の条件にしない。原題や曲のIDを推測せず、英語判定条件・手動名・ローカルタグを保護する。

対象ソース: `7e48d375` + 既存の未コミット差分 + 本改修。既存差分を削除・巻き戻しせず続行。前回の結果は [9月20日の記録](2026-09-20-metadata-language-behavior.md) と照合し、今回の最終版PASSと区別する。

## 調査で得た根拠

- savedSongs/artist一覧はローカルRoomクエリ。ネットワーク同期完了は表示条件ではない。
- MetadataNameRepositoryの初期表示は候補・確定結果の全証拠JSONをFlowとtransactionで二重読込。初回評価・取得も並行開始し、確定結果の組立をRoom transaction内で実行していた。証拠組立には対象数×原題数の全走査があった。
- 毎回のプロセス起動では保存済み評価の入力fingerprintが有効でもモデルを再実行していた。
- 「1」の匿名API確認: album `MPREb_mnYrD806fc4`、canonical playlist `OLAK5uy_mc399CKoHidFSZHLCydnI43dS3O9hEojA`。browse/nextは27項目の完全集合。set ID `3CF5D4F99A0F04E8` が source `6gluNoLVKiQ` と target `HuS5NuXRb5Y` を対応付ける。
- source Main原題は `Eleanor Rigby (Remastered 2015)`、source Musicとtarget albumの英語候補は `Eleanor Rigby`。既存の完全一致条件で原題の参照を捨てていた。ユーザーが見た版との同一性は共有リンク未取得のため未確定。

## 変更方針

- 表示は証拠JSONを含めない単一のJOINクエリから作り、初回表示後に背景取得・評価を開始する。
- 証拠の組立はtransactionの外。書込み直前に入力世代を再確認し、変わった確定結果だけ保存する。有効な保存評価は再利用する。
- playlistSetVideoIdによる明示的な対応があり、source/targetのMusic表記が同じ場合、別に取得したMainの正式原題をそのまま利用する。括弧を削除・補完せず、この例では正式な版名も表示する。
- 別版の再取得で、現在の完全Main応答が再確認している旧版の証拠を失効させない。部分的なMusic人物欄で旧証拠を撤回しない。

## 検証

ユーザー追加確認: 約20秒待つと曲・アーティストは表示される。未登録の空ライブラリという意味ではない。

最初のテストでは次を確認した。以下は途中版のため最終版の結果と分ける。

- 単体199件の初回は198 PASS / 1 FAIL。追加テストが非data classの入力コンテナを参照比較していたため、指紋と候補集合の比較に修正。再実行199 PASS。
- Android32件の初回は31 PASS / 1 FAIL。不完全なMusic情報を7日キャッシュから再利用し、5分のMain再試行でも復旧できない実装不具合を追加発見。該当キャッシュだけを破棄し、次回はMusicのID情報も取得し直すよう修正。テストの待機条件は緩めず、各再試行の実取得回数を確認する。
- 欠損キャッシュ破棄だけでは、並行した再取得が古い完全Music情報を先に使う競合も残った。2回目のAndroid実行で検出し、Music観測時刻を持たせ、前回Main取得より新しい観測だけを1回再利用するよう修正。初回PENDINGの同時刻観測だけはまとめ取得から再利用できる。
- 起動テスト: ディスクRoomを閉じて開き直した2,000保存曲・4,002名称行・約24MBの証拠を持つデータで、名称の初回公開99ms。通信を完了させずに保存一覧を読め、入力が変わった判定を停止中にも一覧読込・手動表示更新83ms。これはアプリ全体の起動時間・ユーザー実機の20秒からの短縮率ではない。
- 実通信「1」は27/27曲の直接原題・英語判定・確定表示を取得し、アルバム取得も完了。旧live validatorがen別名だけを認め、今回の実取得Main原題を認めないため24曲で検証FAIL。validatorを現行の直接原題・指紋・参照・双方の独立Music観測を検証する形へ更新し、最終版で再実行する。FAILをそのままPASSに読み替えない。

証跡は無視対象 `build/diagnostics/metadata-startup-20260927/`。匿名APIのID・表記対応の観測要約は `build/diagnostics/beatles-one-20260927/anonymous-observations.json`。デバッグデータは `debug-before.tar`、releaseデータは通常のアプリバックアップ `release-before.backup` へ操作前に保存。

## 最終差分の確認結果

| 確認 | 結果 |
| --- | --- |
| `:app:testCoreDebugUnitTest` のrepositories / models.metadata | **PASS 199件**、失敗・skipなし |
| 実Roomを使うAndroidテスト | **PASS 32件**、43.8秒。MetadataRelatedProofRetention / MetadataStartup / MetadataPublicationLifecycle / AlbumPlaylistReference / AlbumOriginalRecovery / MetadataManualProtection |
| 最終起動テスト | 2,000曲の保存名初回公開 **106ms**。判定停止中の保存曲読込・表示更新 **43ms**。初回公開前の通信呼出しなし、保存判定再利用時のモデル呼出しなし |
| 最終debug実通信・「1」 | **PASS 1件**、22.251秒。27/27曲で原題・英語評価・英語確定結果、アルバム取得完了。ID・曲順・raw保存値不変、設定OFF→ON→OFFの検証に違反なし |
| coreRelease / arm64-v8a | **PASS**、最終ビルド2分50秒（テスト・コンパイル等を含む）。署名v2とAPK内のarm64-v8a単独を確認 |
| 配布APKの通常画面 | **PASS**。元の保存データでNevermindのライブラリ、Smells Like Teen Spirit、Pollyを確認。実通信で作成した「1」のDBを通常のバックアップ復元機能で読み込み、保存アルバム全27曲、Eleanor Rigbyのアルバム・ミニプレーヤー・プレーヤー表示を確認。OFFで「エリナー・リグビー」、ONで正式原題へ切替。完全終了後もPenny Laneの確定英語を表示 |

最終live report: `build/diagnostics/metadata-startup-20260927/beatles-1790482809283.json` と同名 `.db`。旧FAIL reportは別名で残し、結果を改変していない。
UI用backupはこの実取得DBと操作前の設定ファイルだけから作成し、英語原題・参照・判定を手書きで注入していない。テストDBはアルバムをbookmarkし曲は個別ライブラリ登録していないため、画面では「いいね済み」アルバムで開いた。

配布APKの `am start -W` は元データ1.777秒、「1」データ1.628秒/1.661秒。これはこのエミュレータのActivity起動計測であり、ユーザー環境の約20秒に対する比較測定ではない。連続フレームで全ちらつきが無いことの証明も別とする。

画面証跡: [英語ON](../images/2026-09-27-startup-language/beatles-on.png)、[OFF](../images/2026-09-27-startup-language/beatles-off.png)。その他の画面・テストログは上記diagnosticsディレクトリに保存。

releaseの実通信は既知の検証PC証明書制約のため再試行していない。releaseは通信を切って保存済みデータで確認したため、プレーヤーの音声再生成功は今回の検証結果に含めない。debug実通信とreleaseの画面確認を区別する。

終了時にreleaseは通常バックアップから元のNevermind/Pollyデータへ戻し画面確認、debugも操作前tarへ戻した。通信設定を復帰し両アプリを停止。実機は未操作。ユーザーへエミュレータ操作終了を案内済み。

## 配布物

[OuterTune-0.10.2-b1-core-arm64-v8a-release-startup-language-20260927.apk](../../../build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-startup-language-20260927.apk)

- `coreRelease` / `arm64-v8a` / versionCode 71 / 9,185,677 bytes
- SHA-256: `F116AFD677DE68936DB32C58CB2D010A3F9EA75F884F072776F563722F8211CB`
- ソースは `7e48d375` + 前回差分 + 今回差分。コミット・pushなし。
- 今回の確認を、前回提案の全作品・全画面・全障害条件の合格へ拡張しない。

## 実機待ち時間の切り分けで残す点

ユーザー実機の曲数・保存方法・ログを得ていないため、約20秒の原因を今回の名称処理だけと断定しない。
別の既存経路として `playback/DownloadUtil.kt` のカスタムダウンロード走査は、download flagsを先にクリアして外部ストレージを走査する。ダウンロードだけで保存した曲と人物はその間消え得る。ただしLIBRARY filterの登録済み曲は対象条件が異なるため、すべてのライブラリ空表示の説明にはならない。
この経路は今回未変更。修正版でも待ちが残り、ダウンロード由来の曲・人物が対象と確認された場合は、取得一覧を先に組み立ててからdownload flagsを一括確定する別の再現・回帰検証を行う。外部ストレージI/OをRoom transactionへ入れる修正はしない。
