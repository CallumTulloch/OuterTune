# Repository working agreement

このリポジトリで実装・調査を行うエージェントは、着手前に
[`CONTRIBUTING.md`](CONTRIBUTING.md) と
[`docs/development-workflow.md`](docs/development-workflow.md) を確認してください。

- 表示用文字列から区切り記号だけで識別子や関係を推測しない。可能な限り、ID を持つ正式な情報源と実際の応答 fixture を使う。
- 編集中は変更箇所に近いテストだけを実行し、ソースを確定してから関連する一式を一度実行する。同じ worktree で Gradle を並列実行しない。
- コード、ビルド設定、DB、動作仕様を変更した場合は、
  [`docs/verification/README.md`](docs/verification/README.md) に従って改修単位の検証記録を作成または更新する。
- `PASS` は、記録した最終ソースまたは成果物で実行して成功した場合だけ使う。途中版の確認、テスト側の無効化、意図的な後回し、実行不能を区別する。
- 後回しにする確認は [`docs/verification/pending.md`](docs/verification/pending.md) に安定した ID、再現手順、合格条件、実行時期を残す。曖昧な「後で確認」だけで終えない。
- DB スキーマ変更では、バージョン、移行または破棄の方針、schema JSON を同じ変更内で揃え、判断を検証記録にも残す。
- APK や長い生ログを検証証跡としてコミットしない。再実行可能なコマンド、件数、結果、所要時間、成果物のハッシュを短く記録する。
- コミット本文には、可能なら `Verification: docs/verification/records/<record>.md` を付ける。
