# ライブラリとフォルダの出所フィルター

開始HEAD `4def7ed1`、ブランチ `restart/artist-20260905`。着手時の作業ツリーはclean。

## 現象と合意した仕様

- 曲のLIBRARY条件には既にフォルダ除外があるが、アルバム・アーティストのLIBRARY条件とプレイリストの抽出には不足がある。
- 曲のLIBRARYはオンライン由来かつライブラリ追加済み、FOLDERはフォルダ由来かつ現在取込済み、DOWNLOADEDはオンライン由来かつダウンロード済み。
- アルバム・アーティストは関連曲の出所で判定する。名前・オンラインID・エンティティ自体のisLocalでは判定しない。
- 保存済みプレイリストはオンライン曲があればLIBRARY、有効なフォルダ曲があればFOLDER。混在プレイリストはどちらの条件でも表示する（ユーザーが追加で明示）。空・収録曲未取得の保存済みオンラインプレイリストはLIBRARYに残す。
- 複数選択はOR、未選択は全出所。プレイリストを開いた後の収録曲全体は変更しない。DB登録の削除やschema変更は不要。

## 範囲と合格条件

1. 単独画面とライブラリ内のアルバム・アーティストで、LIBRARYにフォルダ曲だけの項目を含めない。
2. プレイリストのオンラインのみ／フォルダのみ／混在／空／未取得を実SQLiteで検証する。混在はどちらからも参照可能。
3. ダウンロードのみ、消失済みフォルダ、複数選択・未選択を含めて検証する。曲フィルターの既存の分離条件・件数・再生キューへの適用を確認する。
4. 関連単体テストと実Roomテストを実行する。Gradleは直列、APKが必要ならcoreDebug/arm64-v8aのみ。

## 実装・検証

- 共通SQL条件を導入し、アルバム・アーティストの単独／集合フィルターに適用。プレイリストLIBRARYはオンライン収録曲または空、FOLDERは有効なフォルダ収録曲で判定。
- 全体棚・同期・MediaBrowser用の3取得helperは従来の全出所を保持する。曲の登録状態・出所・関連付けは書き換えない。
- ライブラリ内の固定された「曲」一覧に、別の曲タブの選択だけを変更してしまう件数横フィルターメニューが残っていたため、その固定一覧では当該メニューを非表示にした。
- 曲一覧の表示・件数・通常再生・全曲再生・シャッフル・複数選択は同じ絞り込み済み一覧を使用することをコードで確認した。曲のLIBRARY単独へ正しいisLocal=trueの曲が恒常的に入る経路は確認されなかった。

## 最終ソースの確認結果

対象は開始HEAD＋本改修の未コミット差分。メニュー修正前の初回単体テスト成功（1分33秒）は途中版として扱い、以下はメニュー修正後。

| 対象 | 結果 | 証拠 |
| --- | --- | --- |
| 出所SQL・ライブラリ選択・曲絞り込みの単体テスト | PASS、24件、失敗・除外0、テスト本体合計0.165秒 | `build/content-source-device-build.log`、`build/content-source-unit-results/`にXMLを保全 |
| coreDebug arm64 APK・AndroidテストAPK | PASS、関連単体テストと合わせて38秒 | `build/content-source-device-build.log` |
| 実Room/SQLiteでの出所分類 | PASS、3件、0.628秒 | `build/content-source-room-tests.log`。Pixel 9 / API 35、x86_64＋arm64変換対応イメージでarm64 APKを実行 |
| 差分の空白チェック | PASS | `git diff --check` |

実Roomでは、オンライン登録曲、ダウンロードのみ、未登録オンライン曲、有効／消失フォルダ曲、混在アルバム・人物・プレイリスト、空アプリ内プレイリスト、未取得の保存済みオンラインプレイリストを使用。単独／全OR組合せ／未選択／全選択、ファイル消失→再発見、全体棚helperの取得範囲を確認した。

実行したGradle対象：`:app:testCoreDebugUnitTest`（ContentSourceFilterQueryTest、LibraryContentFilterTest、LibrarySongsScreenTestに限定）、`:app:assembleCoreDebug`、`:app:assembleCoreDebugAndroidTest`。端末ではContentSourceFilterDatabaseTestだけを実行。配布用releaseは本段階では生成しない。

実データベースの合格と、ユーザーの既存データでの画面再現は区別する。件数横メニューの非表示はコンパイルとコード確認で検証し、実描画でのタップ確認は未実施。検証用データはin-memory DBに限定した。
