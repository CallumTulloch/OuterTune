# 実装・検証の共通方針

この文書は、改修のたびに調査や検証方針を作り直さず、必要十分な確認を短時間で行うための共通手順です。
個々の実行結果は [検証記録](verification/README.md)、後回し項目は
[pending.md](verification/pending.md) に残します。

## 1. 実装前に決めること

着手前に、最低限次を明確にします。

- 目的とユーザーから見た合格条件
- 変更する範囲と、今回は変更しない範囲
- 信頼する情報源と、値が欠ける場合の代替情報源
- 実際に問題を再現する入力、レスポンス fixture、DB 状態
- 影響し得る非同期処理、画面ライフサイクル、オフライン、永続化、既存データ
- DB スキーマを変える場合の migration または destructive reset 方針

コンテンツ情報を扱う場合は、[`content-source-semantics.md`](content-source-semantics.md) も参照します。
表示用に結合された文字列は表示値であり、区切り記号だけでアーティストなどの識別子へ分解しません。
アルバム、アーティスト、クレジットなどは、項目ごとに ID を持つ正式な情報源から補完します。

## 2. 実装中の原則

- 不足している項目だけを補完し、既に確定している無関係な値を上書きしない。
- 外部レスポンスの型付き境界で解析し、実際の応答 fixture を回帰テストに使う。
- 返却された resource ID は、要求した typed endpoint と同じ種類・値かを照合する。credits は video ID を対象曲と照合し、
  album は要求した browse ID、artist は ID と名前候補をそれぞれ検証する。
- 非同期の補完結果は、現在の再生曲、キュー、DB、通知など必要な利用箇所へ一貫して反映する。
- 世代番号、対象 ID、transaction などで、古い非同期結果や部分更新が現在状態を壊さないようにする。
- ユーザーの既存変更と無関係な整形・修正を混ぜない。

## 3. 検証を速く、正確に行う

### 編集ループ

実装中は変更箇所に最も近いテストだけを実行します。コンパイルエラーや fixture の誤りを早く見つけるためで、
この段階の結果を最終結果として記録しません。同じ worktree で複数の Gradle を同時に動かすと、生成物の競合や
原因の判別できない失敗を招くため、並列実行しません。静的なコード確認は並行して構いません。

### ソース確定後

1. 実装を止め、最終 diff と対象範囲を確認する。
2. 変更モジュールの関連テスト一式を一度実行する。
3. 要求された variant だけを一度ビルドする。
4. 自動化できないユーザー操作だけを手動確認する。
5. 結果を検証記録へ反映してからコミットする。

今回のように `app` と `innertube` の両方を変更した場合の基本コマンドは次です。

Windows:

```powershell
.\gradlew.bat :innertube:test :app:testCoreDebugUnitTest --console=plain
```

Linux / macOS:

```bash
./gradlew :innertube:test :app:testCoreDebugUnitTest --console=plain
```

release APK が明示的に必要な場合だけ、テスト後に次を実行します。

```powershell
.\gradlew.bat :app:assembleCoreRelease --console=plain
```

Linux / macOS では `./gradlew :app:assembleCoreRelease --console=plain` を使います。

変更範囲が狭い場合は対象モジュールやテストを減らして構いません。逆に、DB、並行処理、配布物など失敗時の影響が
大きい場合は必要な確認を省きません。ドキュメントのみの変更では、リンク、Markdown、`git diff --check` の確認を
基本とし、ビルド設定へ影響しない限り Gradle は実行しません。

## 4. 結果の記録

コード、ビルド設定、DB、動作仕様を変えた改修には、
[`docs/verification/records/`](verification/records/) に簡潔な記録を追加します。記録には次を含めます。

- 対象 commit または対象成果物
- 目的、範囲、主なリスク、合格条件
- 追加・変更したテストと、その確認内容
- 実行したコマンド、PASS/FAIL/SKIPPED、件数、概算時間
- 手動確認の build と環境。最終版でない場合は `OBSERVED` とする
- 実行しなかった確認と、その理由
- APK など成果物のパス、SHA-256、署名確認
- 後回し項目の安定 ID

長いログは原則コミットしません。検証記録から、何を再実行すべきか、何がまだ未確認かを判断できることを優先します。

## 5. 後回し項目

後回しにする場合は [`pending.md`](verification/pending.md) を唯一の台帳として更新します。各項目に次を記載します。

- 安定した ID と起点となる改修
- 残るリスク
- 再現または実行手順
- 合格条件
- 実行する契機（次回 release、端末利用可能時など）

完了時は元の検証記録へ結果を追記し、台帳の「完了」へ移します。別の不具合を見つけた場合は、元項目を完了扱いにせず、
新しい ID で分けます。

## 6. 完了条件

- 最終 diff に意図しないファイルや秘密情報がない。
- 必要なテスト・ビルドを最終ソースに対して実行した。
- PASS、FAIL、SKIPPED、OBSERVED、DEFERRED を混同せず記録した。
- 後回し項目に再現手順と合格条件がある。
- commit 本文から検証記録へ辿れる。

推奨する commit trailer は次です。

```text
Verification: docs/verification/records/YYYY-MM-DD-change-name.md
```
