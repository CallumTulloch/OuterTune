# 2026-10-01: 実ファイルスキャン・OFF/ON・DB再open

## 現象・合格条件

- 着手HEAD `17425de2`。フォーク以降の `REGRESSION-SCAN-001` を、合成MP3→実TagLib→scanner→実Roomで検証する。
- パスのないローカル曲が再スキャン後もフォルダ一覧に残る不具合を再現。`localPath = null` はNULL行に一致せず、解除処理が働かなかった。
- 有効な曲のID・曲ArtistとAlbumArtistの役割を保持し、元音源とオンライン保存/DLデータを保全する。OFF後に抽出済み/待機中スキャンが遅れて再登録しない。

## 対応・追加テスト

- `SongsDao.disableInvalidLocalSongs()` を `isLocal = 1 AND localPath IS NULL` に変更。オンライン曲のNULLパスは対象にしない。
- `ForkLocalMediaIntegrationTest` 4件:
  - 日本語・記号を含む実ファイルの反復スキャン/再タグ、ArtistとAlbumArtistの分離、無効localだけ解除、オンライン保存維持。
  - 実ファイル移動後のID維持、同名アルバムのAlbumArtist/リリースID競合の分離。
  - 実TagLib抽出後にゲートで停止したスキャンと待機中スキャンをOFFで排除。ローカル関連だけ削除し、再取り込みで新ID、オンライン曲/歌詞/統計/DL情報を維持。
  - 通常pickerで許可した専用SAFツリーから実 `importSavedMedia()`、除外フォルダ、OFF→ONを確認。作成した音源は削除されない。
- 4件ともUUID名のファイルDBを使用。1件目はclose→同じDBを再open→全Song/Artist/AlbumArtist/保存状態を確認し、再スキャン後の重複なしまで確認する。

## 結果

- `FAIL（製品、修正前）`: 4件中3件成功・1件失敗。パス欠落localがフォルダ一覧に残る。`scanner-before.txt`。
- `PASS（最終テストソース）`: 4件、2.637秒、失敗/スキップ0。`final-scanner-reopen.txt`。
- `PASS`: 近傍 `ContentSourceFilterDatabaseTest` / `LocalArtistLinkDatabaseTest` / `AlbumArtistGroupingDatabaseTest` 計23件、4.852秒。`final-local-database-regression.txt`。
- `PASS`: scanner検証時のapp単体回帰538件、失敗/スキップ0。テスト本体5.294秒。復元APIだけを後で変更した最終版でも538件/4.436秒が成功した。
- エミュレータは専用read-only emulator-5556、ADB5038。`forkScannerRegression=true` と、通常UIで許可した `forkScannerTreeUri=content://com.android.externalstorage.documents/tree/primary%3AMusic%2FOuterTuneRegression` を指定して単独実行。
- 元AVD/通常ADB/実機は操作しない。テストは設定をfinallyで戻し、自身のDB・合成ファイルだけを削除する。独立レビューでassert・排他・設定/ファイル清掃を確認。
- 証跡・ソース/APK hash: `build/diagnostics/fork-regression-phase2-20261001/`。scanner検証時の本番APKはcoreDebug/arm64-v8a、SHA-256 `D04C326DE3328693E341BC811BA0E150D40AC9293DF899FE4A58CD3B5B31EC2C`。後続の復元API変更ではscanner/DAO/テスト入力の変更0をhashで照合し、この端末結果を再利用した。

## 残る範囲

- DB再openは確認済み。実ファイル取り込み後の通常画面全遷移と、実機のアプリ/OS再起動を含む長時間運用は未確認。
- このコミットへ別作業の音声403対策を含めない。DB版/schema/移行処理は変更しない。
