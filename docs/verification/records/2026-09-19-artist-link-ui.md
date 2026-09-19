# 2026-09-19: アーティスト紐付けのUI整理

## 依頼・合格条件

- 「フォルダ由来の紐付け」で開くポップアップは機能を維持し、元の名前・紐付け先・フォルダ・変更操作を見分けやすくする。
- 「YouTubeアーティストを紐付け」を「オンライン紐づけ」に変更する。
- アーティスト画面上部の3点メニューを一番右に配置する。
- 元ごとの変更・解除、フォルダの開閉、0曲の紐付け、設定からの管理を維持する。DB・検索・保存ロジックは変更しない。

## 基点・調査

- 基点: `14e73317`、開始時の作業ツリーに変更なし。
- 対象は `LocalArtistLinksDialog`、日本語/既定言語の関連文字列、`ArtistScreen` の上部アクション順序。
- 現状は紐付け元の情報と操作が平坦に並ぶため、元ごとのカードとラベルで関係を示す。
- 単体テストの追加はせず、既存の管理・編集ViewModelテストと対象画面で確認する。

## 実装

- 紐付け元ごとにカードで区切り、ファイル内の名前・曲数・背景色を変えた現在の紐付け先を順に表示。
- フォルダ開閉をアイコン/開閉矢印付きの行にし、選択コピー可能な完全なパスを保持。変更・解除はカード全幅のアウトラインボタンにした。
- 0曲の項目には「現在読み込まれている曲はありません」を表示。画像がない場合は人物アイコンを表示。
- ダイアログは左右16dpの余白・最大560dp、本文は従来同様にスクロール、閉じるボタンは本文の外に固定。
- 日本語の新規紐付けメニュー/タイトルを「オンライン紐づけ」に変更。既定言語も `Link online artist` に揃えた。
- 上部のボタン順を、お気に入り→共有→3点メニューへ変更。表示条件と処理は維持。

## 最終ソースの検証結果

対象: `14e73317` + 今回の未コミット差分。GradleとADBはrootのみで実行。

| 確認 | 結果 |
| --- | --- |
| `:app:testCoreDebugUnitTest --tests '*LocalArtistLinkViewModelTest' --tests '*LocalArtistLinksViewModelTest'` | PASS、14件（8+6）、失敗0。テスト本体0.250秒 |
| 同じGradle呼び出しで `:app:assembleCoreRelease --console=plain` | PASS、全体5分50秒、lintVital通過。既存の非推奨API/外部Compose mapping警告あり |
| 最終release: 紐付け元3件・長名・0曲 | PASS、各カードの区別、長名の折り返し、曲数/0曲補足、現在の紐付け先、画像なしアイコンを確認 |
| 最終release: フォルダと変更画面 | PASS、フォルダ展開で完全なパスを表示。編集→キャンセルで親へ戻り、展開状態を維持 |
| 最終release: 設定経由/解除 | PASS、設定から同じ管理画面を開き、元Aだけを解除。元B/0曲の元は残り、統合先2曲と元A1曲に更新 |
| 最終release: 表記/配置 | PASS、元Aのメニューと新規紐付け画面タイトルが「オンライン紐づけ」。人物画面の3点メニューは共有より右 |
| 最終release: 360dp幅・font_scale=1.5 | PASS、本文/長名/パス/操作ラベルが折り返し、スクロールで変更ボタンへ到達。固定の閉じるボタンも表示。倍率を戻す際の別件例外は下記参照 |
| 成果物 | PASS、output-metadataはcoreRelease / arm64-v8aのみ。APKのv2署名検証成功 |
| 差分 | PASS、`git diff --check`。DB/schema/保存・検索/ViewModelは変更なし |

検証端末は `Pixel_9_API_35` / `emulator-5556`（x86_64上のarm64変換）。最終画面確認は新release APKで行い、debug APKは生成していない。実機・ユーザーの音源/認証データは使用していない。

### 合成fixtureと制約

- 初回は以前のv26合成バックアップを使用したが、現行アプリは既存の26→27初期化方針によりデータを消去した。既知の開発方針に従う動作であり、今回DB処理は変更していない。
- 最終確認では、コミット済みschema27から新規SQLiteを生成し、旧合成fixtureのentity行を移した。0曲のリンクと長名を合成データに追加し、アプリの復元UIで読み込んだ。3リンクを確認してから上記操作を実施。
- 既知のrelease通信環境制約は再調査していない。新規オンライン検索/リンク保存の実通信は今回未確認。編集・管理ViewModelの既存テストと通信不要のUI経路を確認した。
- 終了時にfont_scale=1.0、画面密度を420へ戻し、今回起動したエミュレータだけを終了。チャットで操作占有を解除した。

### 別件の未解決観測: UI-DENSITY-001

- `OBSERVED`: 360dp/font1.5で管理画面を閉じた後、ADBで `settings put system font_scale 1.0` → `wm density reset` を続けて実行した際、1回 `IllegalArgumentException: invalid beforeContentPadding` でアプリが終了した。再起動後の通常表示・設定からの編集/解除/名称表示は正常。
- 例外はLazyListの計測処理。`ArtistScreen.kt` の既存contentPaddingは親のInsetsからsystemBars＋64dpを引く構造（2024年の既存実装）で、今回未変更。密度更新時の値のずれが候補だが、原因の確定や旧版での再現はしていない。改修ダイアログは `Column.verticalScroll` を使用し、今回の追加余白は正値。
- `DEFERRED`: 画面密度変更の別改修時に、人物画面で上記操作を再現し、変更直後もcontentPaddingが負にならずクラッシュしないことを確認する。今回の紐付けUI変更には混ぜていない。
- ログ: `build/diagnostics/artist-link-ui-20260919/density-change-crash.txt`。

## 画像・成果物

- [紐付け元のカード表示](../images/2026-09-19-artist-link-ui/links-dialog.png)
- [360dp/文字150%での表示](../images/2026-09-19-artist-link-ui/large-font-card.png)
- [人物画面の3点メニュー](../images/2026-09-19-artist-link-ui/unlinked-artist.png)
- [オンライン紐づけの表記](../images/2026-09-19-artist-link-ui/online-link-menu.png)
- APK: `build/distributions/OuterTune-artist-link-ui-20260919-arm64.apk`
- coreRelease / arm64-v8a / 0.10.2-b1 (71) / com.dd3boh.outertune
- SHA-256: `8a6214d4852ae7dd2b6ee8d9e00fb4f1d82ea91d73e20d58ab459af94503ed92`
- mapping: `build/distributions/OuterTune-artist-link-ui-20260919-mapping.txt`
- ビルドログ: `build/artist-link-ui-20260919.log`。画像/XML/合成fixture生成スクリプトは `build/diagnostics/artist-link-ui-20260919`。
