# 合成テストデータの復元時に観測したDB破損

## 対象と位置づけ

- 対象ソース：`f7826b9e` ＋未コミットのプレイヤーのアルバム導線・歌詞調整幅の変更。復元処理自体は変更していない。
- エージェント管理の `emulator-5556`、`coreRelease` / `arm64-v8a` で、2曲の合成音声を使った画面検証中に観測。
- 使用したのはdebugアプリの専用seedからホストで作った合成バックアップ。ユーザーのバックアップ・実端末・アカウント情報は使用していない。
- 通常のアプリ内バックアップにも発生するかは未確認。今回のプレイヤー変更に起因すると判断できる根拠もない。

## 観測結果

1. `build/export-player-adjustments.py` でdebugのDBを読み出し、Python SQLiteの `backup()` で `release-song.db` を作成。2曲のパスをエミュレータ共通ストレージへ変更してコミット・クローズし、DB本体をZIPへ格納した。出力はWALモードで、ホストの `integrity_check` は `ok`。
2. アプリの復元画面は `Found valid database, proceeding with restore` を記録した。
3. 次の起動時、`PRAGMA journal_mode` で `SQLITE_CORRUPT` / `database disk image is malformed` が発生。`SupportSQLite` が `song.db` を削除し、ライブラリが空になった。
4. エクスポートした合成DBだけを `PRAGMA journal_mode=delete` に変更し、`player-adjustments-standalone.backup` を再作成して同じ復元画面から読み込んだ。その後は2曲と所属アルバムが表示され、実際のローカル音声再生を確認した。

エミュレータでの直接操作・整合性確認は親エージェントが実施。記録担当はエクスポート用コード、復元コード、`build/player-adjustments-restore.log` の上記ログを確認した。最初の失敗と再復元の成功を別の観測として保持する。

## コードから分かることと未確定の原因

- `BackupRestoreViewModel.restore()` は本番DBをcheckpointしてcloseし、別名の検査用DBで互換性を確認する。
- その後、出力先パスを得るために `database.openHelper.writableDatabase.path` へアクセスし、本番DBを開き直した状態でDBファイルへ上書きコピーする。
- コピー前後に本番・検査用DBのWAL/SHMをどの状態に保つかは、この処理内で明示的に管理されていない。
- 再オープンした接続や残存WALとの不整合は調査仮説。今回のログとjournalモード変更だけでは、どのファイル・接続が破損を引き起こしたかまでは確定できない。
- 通常のバックアップはAndroidの `createDatabaseSnapshot()` で新規ファイルへスキーマ・データをコピーしており、今回のPython `backup()` とは作成経路が異なる。通常バックアップの安全性・不具合をこの結果だけで断定しない。

## 残課題

`RESTORE-SYNTHETIC-20260913`：**DEFERRED**。次回のバックアップ復元の独立した調査で、使い捨てデータを用い、WALモードとDELETEモードの合成DB、およびアプリ自身が作ったバックアップを比較する。復元前後の接続状態・本体/WAL/SHMを保全して原因を絞る。

合格条件は、検査を通った復元データが再起動後も保持され、曲・アルバム数と関連が元データに一致すること。復元失敗時に既存のライブラリを失わないことも確認する。今回のプレイヤー改修では復元の製品コードを変更していない。
