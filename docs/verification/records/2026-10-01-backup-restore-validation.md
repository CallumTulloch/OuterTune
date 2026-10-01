# 2026-10-01: 復元前の検証とデータ保全

## 現象・範囲

- フォーク以降の結合回帰テスト中、実際の `BackupRestoreViewModel.restore()` に破損ZIP・DB欠落・不正SQLiteを渡した。既存設定が先に書き換わり、復元拒否時にも元設定を保てなかった。
- 着手HEADは `17425de2`。別作業の音声403対策は保全した作業ツリーで検証し、今回のコミットへ含めない。
- 新規の現行DBを前提とする。移行用schema・fixture・移行処理は追加しない。
- 合格条件: 正常バックアップは元ID・関連・手動リンク・名称・歌詞・キュー・設定を保持。拒否する入力では開いている元DBと設定を維持し、再起動しない。

## 対応

- アーカイブを私有領域へコピーし、全エントリ・CRC・Preferencesの実パーサー・SQLite整合性・Room互換性・外部キーを検証してから反映する。
- 検証用Roomでは破壊的fallbackを許可しない。元DBのパスをclose前に取得し、close後に再openして上書きする経路を除いた。
- DBと設定の置換用ファイルを両方準備してからRoomを閉じ、各ファイルを同じディレクトリで `Os.rename` により原子的に置換する。公開失敗時は退避から回復する。
- close後のjournal処理失敗では、未公開の元データでアプリを再起動する。回復自体が失敗した場合は退避コピーを削除せず、私有cacheの場所をログへ残す。

## 検証

- 証跡: `build/diagnostics/fork-regression-phase2-20261001/`。最終ソースhash、APK hash、単体XML集計、各instrumentation statusを保存。
- `FAIL（製品、修正前）`: 実復元4件中、正常1件成功・破損入力3件失敗。`restore-before.txt`。隔離したContextWrapperのDB/files/cacheだけを使用。
- `PASS`: 最終本番APKで `BackupRestoreIntegrationTest` 7件、既存 `DatabaseSnapshotDeviceTest` 4件、計11件。3.167秒、失敗/スキップ0。`compat-backup-regression.txt`。置換API変更前の11件/3.183秒は `final-backup-regression.txt` に別途保全し、加算しない。
  - 正常backup→restore→DB再open: 元ID、raw credit、local/channelリンク、名称publication、歌詞/offset、shuffle順を持つ混合キュー、Preferences。
  - 中央ディレクトリが欠けたZIP、DB欠落、不正SQLite、不正Preferences、孤児artist関連を拒否。
  - 設定フォルダへの書込み拒否では、反映前に停止し、元の注入Roomから引き続きsentinelを読める。
- `PASS`: 最終本番入力で `:app:testCoreDebugUnitTest` 538件、失敗/スキップ0、テスト本体4.436秒。APKを含むGradle29秒。`api24-compat-build.log` / `compat-unit-metrics.json`。
- 最終本番APK: coreDebug / arm64-v8a、SHA-256 `89DA7DBB82F6BFA86249E791E53E0A7011CE24215F8722978A920A75F0B372B6`。
- コミット前に置換APIを `Files.move` から同一親ディレクトリの `Os.rename` へ変更し、単体・復元11件・通常UIを再検証した。入力変更は本ViewModelだけで、変更前hashは `source-before-api24-compat.json` / `apk-before-api24-compat.json`、最終hashは `source-final.json` / `final-apk-hashes.json` に保存。Android 7対応不要というユーザー指示に従い、Android 7の追加検証は実施していない。
- 独立レビューでclose後のjournal失敗とrollback失敗時の退避削除を指摘され、上記の保全・再起動経路へ修正済み。実際のatomic move失敗とrollback失敗の同時故障注入は未実施。

## 通常UIからの復元・再起動

- 専用read-only `Pixel_9_API_35` / emulator-5556 / ADB5038で実施。新規の検証DBだけをsentinel1件にし、実ViewModelで作った合成バックアップを通常DocumentsUIから選択する。
- `ForkBackupRestartDeviceTest` は明示opt-inとdisposable確認が必要。prepareとverifyは別instrumentationで、元AVD・通常ADB・実機のデータを使用しない。
- 初回はPID7564→8196で実再起動を観測したが、起動後の全26テーブル照合はfixture不整合で失敗した。音源/indexを持たないDL印はDownloadUtilが解除し、根拠のないfake publicationは名称処理が再評価した。元曲・所属・手動リンク・歌詞・キューは保持。`ui-restore-verify.txt` と `ui-first-*` に保全。
- UI専用fixtureを、音源のないDL印を付けず、正規Art Track評価・入力指紋・出版判定と有効な取得済みcacheを持つ4曲へ修正。元の7件の拒否/保存テストは変更していない。
- `PASS（最終fixture・置換API変更前）`: prepare 1件0.899秒、通常DocumentsUIで復元→PID9108から9504へ実再起動→verify 1件0.397秒。`ui-restore-prepare-corrected.txt` / `ui-corrected-restore-process.json` / `ui-restore-verify-corrected.txt` に保全。
- `PASS（最終本番APK）`: prepare 1件0.854秒、通常DocumentsUIで復元→PID4398から5121へ実再起動→verify 1件0.385秒。全26テーブルの列/全行、元4曲のID、local/channelリンク、正規English publication、歌詞/offset、混合キューとshuffle順を照合し一致。Preferencesは合成設定の全キー/値を照合し、起動処理が追加するキーを許容する。UI試験の設定ファイル全バイト一致を示す結果ではない。`compat-ui-restore-prepare.txt` / `compat-ui-restore-process.json` / `compat-ui-restore-verify.txt`。
- prepare内部でも、同入力でのpublication再評価が全行不変であることを確認した。verifyはバックアップ自体のSHA-256も再照合し、成功後に自身の3fixtureファイルだけ削除する。
- 終了: 最初のセッションは2026-10-01 21:14 JST。最終API変更の再検証セッションは21:32 JST、起動PID/引数を再照合して一時read-onlyセッションだけを終了、残存0、専用ADB5038停止。元AVD/通常ADB/実機の変更なし。両セッションともcrash bufferは空。`emulator-cleanup.json` / `compat-emulator-cleanup.json`。

## 限界

- backupに含むのはDBと設定。音源・Media3 index/cacheの移設や、実機の実通信成功を確認した結果ではない。
- 復元の通常UI確認に使う保存データは合成fixture。既存DBの移行を保証する追加検証ではない。
