# ライブラリのアルバムタイルから曲一覧へ移動

## 課題・合格条件

- ユーザー報告：ライブラリのアルバムタイルに再生ボタンがある。アルバムを選択して曲一覧へ移り、
  曲を選んだ時点で初めて再生マークが付く動作を期待している。
- 原因：共通 `AlbumGridItem` が非アクティブ時に `AlbumPlayButton` を重ね、タップでアルバムの
  キューを直接再生している。外側の `LibraryAlbumGridItem` は本来アルバム画面へ遷移する。
- ライブラリのアルバムタイルでは再生ボタンを隠す。以前ボタンがあった位置も曲一覧への遷移に使う。
- アルバムを開くだけでは再生しない。曲一覧で選択した曲を再生し、既存の再生中表示を維持する。
- タイルの大／小に適用。ライブラリ全体・アルバム一覧・同じ部品を使うアーティストのアルバム一覧を対象とする。

## 実装

- `AlbumGridItem` に `showPlayButton`（初期値true）を追加し、`LibraryAlbumGridItem` はfalseを渡す。
- `ItemThumbnail` の `isActive` / `isPlaying` とアルバム詳細の曲選択処理は既存どおり。
  ホーム・統計・アーティスト詳細等の直接 `AlbumGridItem` を使う表示は既存動作を維持する。
- 着手時は `de753b9e`、作業ツリーに未コミット差分なし。DB変更なし。

## 検証

- PASS：`:app:assembleCoreRelease --max-workers=2`、2分50秒。必須release lint成功。
  ログ `build/library-album-tap-release-build.log`。既存のCompose mapping収集警告あり、ビルド失敗なし。
  最終製品ソースでの生成後、製品コードの変更なし。
- PASS：生成した同一coreRelease / arm64-v8aをPixel 9 API 35の読み取り専用エミュレータへインストール。
  実際のスキャン画面から、2アルバム・各2曲の検証用FLACを取り込んだ。
  最初のWAV検証データではタイトル・アルバムタグを取得できなかったため、同梱TagLibの
  `tests/data/silence-44-s.flac` に検証用タグを付けた音源へ切り替えた。製品のスキャン処理は変更していない。
- PASS：ライブラリ内アルバム一覧の大（セル360px）・小（270px）で、各タイルの子に再生クリック要素がないことをXMLで確認。
  大では旧ボタン位置 `(260,833)` からアルバムBの曲一覧へ、小では `(440,743)` からアルバムAの曲一覧へ移動。
- PASS：大でアルバムを開いた直後のメディアセッションは `NONE(0)`・metadataなし。
  2曲目 `Tap Track B2` を選んだ後に `PLAYING(3)`・position461ms・同曲のmetadataを確認。
  曲行の再生中表示と、一覧へ戻った際の同アルバムのインジケーターを画像で確認。
  小で別アルバムAを開いても、現在曲のmetadataはB2のままで、Aの再生は開始しなかった。
- PASS：共有ラッパーを使うライブラリ全体・アーティストのアルバム一覧にも同じ指定が適用されることをソース確認。
  既存のアルバム詳細・再生中表示の処理は差分なし。表示条件変更をなぞる単体テストは追加していない。
- 証跡：`build/album-tap-release-verification/` の `albums-flac`、`albums-small`、`album-open-no-play`、
  `small-album-open` のPNG/XML、`album-playing-1.png`、`tile-active.png`、メディアセッション出力。
- PASS：v2署名検証。APKは `app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`。
  SHA-256：`5A3F7080301E1968EB95C480212CF9E662B9620A629EA9F9EB0AAE29E7E800B7`。
- 使用環境：`Pixel_9_API_35` / `emulator-5556`、x86_64＋arm64変換、
  `-read-only -no-snapshot -no-window -no-audio`。元のAVD保存状態を更新しない。
  操作担当を事前通知し、ユーザーの追加依頼（Lithiumの再生エラー）の確認へ引き続き使用する旨を通知済み。
- 確認範囲：ライブラリ内アルバム一覧の実画面を代表確認。ホーム等の既存ショートカットはソース確認。
  音源は約3.68秒の検証用FLACで、デコード・再生位置・表示の確認。聴取確認ではない。
