# ライブラリのタイルサイズ設定

## 課題・合格条件

- ユーザー依頼：設定でタイルの大きさを「大／小」から選べるようにする。現在の大きさを「大」とする。
- ライブラリ全体・アルバム・アーティスト・プレイリストで同じ設定を使い、設定変更と再起動後に反映する。
- 大は既存の最小セル幅120dp（画像96dp＋左右余白24dp）、小は88dp（画像64dp＋同余白）。
  実際のセル幅は従来どおり画面幅に応じて均等に広げる。
- リスト表示やホーム・オンライン一覧のサイズは対象外。DB変更なし。
- arm64-v8aのcoreRelease APKを生成し、配布する同一APKで起動・設定切替・描画を確認する。
- 着手前のナビゲーション改修はユーザーの追加依頼により `3301a4d2` で先にコミット済み。

## 実装

- 「設定 → ライブラリとコンテンツ」に「ライブラリのタイルサイズ」を追加した。
- 保存キー `libraryTileSize`、初期値 `LARGE`。既存の設定選択コンポーネントと「大／小」の翻訳を使う。
- 共通の `rememberLibraryGridCells` を4つの一覧で使用。アイテムは既存の可変幅・省略表示に追従する。

## 検証

対象は `3301a4d2` ＋本記録と同時にコミットするタイルサイズ差分。以下のビルド以降、製品ソース変更なし。

- PASS：関連単体テスト30件、失敗・エラー・除外0件、テスト本体計0.573秒。
  FolderSongs 3件、FolderSearch 3件、LibrarySongsScreen 6件、LibraryContentFilter 12件、ContentSourceFilterQuery 6件。
  画面サイズ設定だけをなぞる単体テストは追加していない。
- PASS：上記テストと `:app:assembleCoreRelease --max-workers=2` を一つのGradle実行で完了（4分41秒）。
  テスト指定は `:app:testCoreDebugUnitTest --tests '*FolderSongsTest' --tests '*FolderSearchTest'
  --tests '*LibrarySongsScreenTest' --tests '*LibraryContentFilterTest' --tests '*ContentSourceFilterQueryTest'`。
  ログ：`build/library-tile-release-build.log`。必須release lint成功。
  既存APIの非推奨警告とaboutlibrariesのCompose mapping収集警告あり。ビルド失敗なし。
- PASS：APK署名検証（v2）、出力情報とAPK内のABIはarm64-v8aのみ。
  `apksigner verify --verbose`、`aapt dump badging`、SHA-256を確認した。
- PASS：配布する同一release APKを検証用Pixel 9 API 35へ新規インストール。日本語表示で起動。
  実際の設定画面で初期値「大」→「小」、アプリ強制終了・再起動後の「小」の保持、「大」への復帰を確認。
- PASS：実際のライブラリ全体と再生リスト一覧で、自動再生リスト2件＋画面から作成した再生リスト2件を表示。
  1080px幅・420dpiで大はセル幅360px（3列）、小は270px（4列）。
  再起動後も270px、「大」に戻すと360px。XMLから8項目を検査しすべて成功。
  `build/tile-release-ui-checks.log`、スクリーンショット・XMLは `build/tile-release-verification/`。
- PASS：設定の配置、小での長いタイトルの省略と画像・文字の収まり、大へ戻した配置を画像で確認。
  小のタイルを選択して再生リスト詳細へ遷移でき、リスト表示への切替も確認。crashログは空。
- PASS：4つの一覧が共通の列幅設定を参照することをソース確認。既存の可変幅アイテム描画を利用し、
  リスト表示・ホーム・オンライン一覧の寸法や描画コードの変更なし。

## 成果物・確認範囲

- APK：`app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`
- variant：coreRelease、ABI：arm64-v8a、versionName：0.10.2-b1、versionCode：71。
- SHA-256：`73E43B43CAC168015338C293621BE45AA38C4C118A90E42CB296D3CE8D4F970B`
- エミュレータは `Pixel_9_API_35` / `emulator-5556`、x86_64＋arm64変換。
  `-read-only -no-snapshot -no-window -no-audio` で起動。新規releaseの権限ダイアログを許可して確認を継続した。
  元のAVD保存状態は更新せず、検証後 `adb -s emulator-5556 emu kill` で終了。操作終了をユーザーへ通知済み。
- ライブラリ全体・再生リストの実画面を代表確認した。アルバム・アーティストのサイズ反映と独立ナビは
  共通処理への接続をソース確認し、データを入れた各画面の操作試験は追加していない。
  文字拡大・横画面・実機の全組合せを網羅した確認ではない。
- ナビ改修の端末テスト5件＋画面テスト2件の詳細は
  [先行の検証記録](2026-09-07-folder-search-library-menu-plan.md)を参照。
  これらは先行debug版での結果。今回のreleaseでフォルダ再生を再試験した扱いにはしない。
