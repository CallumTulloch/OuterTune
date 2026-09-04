# 後回し確認の台帳

ここが未完了確認の唯一の台帳です。状態が変わったら、起点となる
[検証記録](README.md) とこのファイルを同時に更新します。

## 未完了

| ID | 起点 | 状態 | リスク | 実行する契機 |
| --- | --- | --- | --- | --- |
| `PM-003` | 同上 | `DEFERRED` | download が補完済みの値を退行させる、または fresh download の album を保存しない可能性 | 次に download 動作を変更する時、または次回 release 前 |
| `PM-004` | 同上 | `DEFERRED` | 一度の通信失敗後に自動回復しない可能性 | ネットワーク回復処理を変更する時 |
| `PM-005` | 同上 | `DEFERRED` | 補完通知でキュー操作状態が崩れる可能性 | キュー UI を変更する時、または次回 release 前 |
| `NAV-001` | [型付き endpoint によるオンライン遷移](records/2026-09-05-navigation-endpoints.md) | `DEFERRED` | 通常版と instrumental 版の長い共通 title が末尾省略により同じ曲に見える | 曲一覧または title 表示を次に改修する時 |

## 実行手順と合格条件

### PM-001: 最終 commit で対象曲を再生

- 手順: `fd3730e0` 以降の変更を含む build で `TSZhKssbW2g` をオンライン再生し、プレイヤー、通知、キュー、アーティスト・アルバム遷移を確認する。
- 合格: 主アーティストが `翟锦彦`、アルバム ID が `MPREb_NUdafp1DlA5` となり、結合表示へ戻らず、アーティスト選択で異常終了しない。

### PM-002: 強制終了・再起動後の保持

- 手順: `PM-001` の補完後にアプリを強制終了し、再起動して対象曲を表示・再生する。
- 合格: artist と album が保持され、`翟锦彦、8082Audio` の単一アーティストへ逆戻りせず、リンクが有効である。

### PM-003: download 経路

- 手順A: `PM-001` で artist と album を補完してから対象曲を download し、完了後と再起動後を確認する。
- 合格A: 補完済みの正式 ID が保持され、重複 artist / album や表示の逆戻りがない。
- 手順B: DB を初期化し、対象曲を事前再生せず fresh 状態から直接 download する。
- 合格B: queue 由来の正式 album が保存される。現実装では download 単独で credits artist を取得しないため、artist 補完は合格条件に含めない。

### PM-004: オフラインからの回復

- 手順: オフラインで補完対象曲を再生して失敗させ、同じ曲を選択したまま接続だけを戻す。再生操作やキュー更新は行わず60秒待つ。
- 合格: 接続復帰通知による自動再試行で60秒以内に不足項目が補完され、失敗状態や仮 ID が固定化されない。

### PM-005: 補完中のキュー操作

- 手順: 補完中にキュー検索、複数選択、スクロールを行い、補完完了後に検索を終了する。
- 合格: 表示が更新され、検索語、選択対象、スクロール位置が不意に失われず、別曲へ値が反映されない。

### PM-006: universal core-release APK

- 手順: `OuterTune-0.10.2-b1-core-universal-release-71.apk` を対応端末またはエミュレータへ clean install し、起動と基本再生を確認する。
- 合格: install と初回起動が成功し、起動直後の致命的例外がなく、基本再生が開始できる。

### NAV-001: 長い title の末尾差を識別できる表示

- 現状: `TSZhKssbW2g` の末尾は `(特别版)`、`xDWhuDRnevk` は `(特别版伴奏)` で、通常版と instrumental 版として ID も完全な title も異なり、release build で別々に stream できることを確認済み。ただし一覧では長い共通部分の後ろにある相違箇所が省略され、同一曲の重複に見える。
- 手順: 両方を含む検索結果または曲一覧を開き、項目を再生せずに通常版と instrumental 版を識別できるか確認する。
- 合格: 末尾の版表記を表示する、補助情報を付ける、または省略方法を変えるなどにより、2項目が別版であることを一覧上で判断できる。既存の曲 ID、完全な title、artist metadata は変更しない。

### LINT-001: full lint issue の分類

- 手順: 別々の clean worktree で対象 commit `fd3730e0` と、その親 `40212b39708b8d26c0350c8348ae7428df2c0095` に対し、
  `.\gradlew.bat :app:lintCoreRelease --console=plain` を一度ずつ実行する。両方の `app/build/reports/lint-results-coreRelease.xml` を
  issue ID、相対 path、message で比較する（行番号だけでは比較しない）。途中版の前回結果は156 issues
  （28 errors: `LocalContextGetResourceValueCall`、126 warnings、2 hints）だった。全 issue を既存・今回由来・解消済みに分類する。
- 合格: `fd3730e0` で新たに生じた error / warning が 0 件になる。既存 error には別 ID または合意済み baseline があり、
  両 commit の severity 別件数と差分を元の検証記録へ追記する。

## 完了

| ID | 完了日 | 結果 | 完了証跡 |
| --- | --- | --- | --- |
| `PM-001` | 2026-09-05 | `PASS`: 後続の最終 core-release で `TSZhKssbW2g` を再生。player、MediaSession、通知、queue は `翟锦彦` と正しい album を表示し、artist / album 遷移と通常版・instrumental版の分離も確認 | [型付き endpoint によるオンライン遷移](records/2026-09-05-navigation-endpoints.md) |
| `PM-002` | 2026-09-05 | `PASS`: 補完後に強制終了・再起動し、通常版の title、`翟锦彦`、album が保持され、再生再開後の MediaSession・通知にも反映 | 同上 |
| `PM-006` | 2026-09-05 | `PASS`: 最終 universal core-release を clean install。versionCode 71 / versionName 0.10.2-b1 で起動・オンライン再生に成功し、fatal exception なし | 同上 |
| `LINT-001` | 2026-09-05 | `PASS`（退行比較）: `40212b39` と `fd3730e0` はともに28 errors / 164 warnings / 2 hints。正規化比較は共通194、追加0、削除0。両 lint task 自体は既存28 errorsで `FAIL` | [再生時メタデータ補完](records/2026-09-05-playback-metadata.md) |
