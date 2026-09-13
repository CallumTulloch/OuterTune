# フォルダ曲のプレイヤーからアルバムを表示

## 課題・合格条件

- 現象：フォルダから取り込んだ曲にアルバムが保存されていても、プレイヤーの3点メニューで「アルバムを表示」が出ない。
- 期待：保存済みの所属アルバムを同じアイコンから開き、そのアルバムの曲を確認できる。
- 合格条件：フォルダ曲から実際のローカルアルバムへ遷移する。アルバム情報がない曲にはアイコンを出さない。オンライン曲の既存の遷移を維持する。

## 根拠・実装

- 対象：`f7826b9e` の後の未コミット変更。
- `PlayerMenu` の `!mediaMetadata.isLocal` がローカル曲のアクションを除外していた。
- フォルダの読み込みは `album` と `song_album_map` に実際のIDを保存する。`AlbumViewModel` はそのIDのアルバムが `isLocal` なら通信せず保存曲を表示するため、新しい画面やDB変更は不要。
- ローカル曲の遷移先は現在の `Song.album` 関連を使う。再スキャンで所属が変わった場合に、キューに残った古いIDを開かない。同名アルバムの検索・推定はしない。
- アルバム情報のない曲、削除済みの関連、DBの読み込み前にはアイコンを出さない。前の曲の非同期読み込み値も曲IDで除外する。
- オンライン曲は従来どおりメディア情報のアルバムIDを使う。

## 確認

- `PlayerAlbumNavigationTest` 5件を追加：所属変更、キュー情報欠落、関連削除・未読込、曲切替中の古い値、オンライン曲。
- テスト・ビルド・実画面の実行は親エージェントが担当。最終ソースでの実行結果は確認後に追記する。

## 制約

- ファイルにアルバム情報がなく、取り込み時にアルバムが作成されなかった曲には表示先がない。フォルダ名からアルバムを自動作成する改修は今回に含めない。

## 最終確認結果

対象は `f7826b9e` ＋今回の未コミット差分。プロダクションソースの確定後に以下を実行し、その後のコード変更はない。

- PASS：`PlayerAlbumNavigationTest` 5件、`LocalAlbumIdentityTest` 16件（合計21件、失敗0）。関連単体テストとcoreDebug/test APK生成は44秒。ログ `build/player-adjustments-debug-build.log`。
- PASS：`PlayerAdjustmentsUiSeedTest` を専用引数で実行、1件・0.499秒。使い捨てエミュレータ内にアルバムあり/なしの合成WAVと歌詞を準備した。人物重複調査とは別の固定fixture ID。
- PASS：coreReleaseビルド1分54秒、lintVital通過。ログ `build/player-adjustments-release-build.log`。既存外部ライブラリのCompose mapping警告は非致命的。
- PASS：配布APKをAPI35エミュレータ（arm64変換）へインストール。合成データをアプリの復元UIで導入し、Wi-Fi/データOFFでローカル再生を確認。MediaSessionはPLAYING/speed1.0/errorなし。スマートフォンは操作していない。
- PASS：プレイヤー3点メニューにアルバムアイコンと「アルバムを表示」があり、「フォルダアルバム確認」1曲の画面へ遷移。画像/XMLは `build/diagnostics/player-adjustments-20260913/release-local-player-menu` と `release-player-album-destination`。
- PASS：アルバム未設定の別曲へ切り替えて停止すると、プレイヤーメニューにアルバム入口がない（`release-standalone-menu`）。オンライン側の既存ルート保持は単体テストで確認。
- PASS：再起動後も2曲とアルバムを保持。再生証跡 `build/player-adjustments-release-media.log` は対象パッケージのセッションのみを評価し、エミュレータの別Bluetoothセッションのエラーと混同しない。

合成fixtureの初回復元に別のエラーがあり、DBのjournalモードを変更した合成ファイルで再復元して上記の検証を行った。復元の本番コードは変更していない。[観測と未確定事項](2026-09-13-synthetic-restore-observation.md)に分けて記録した。

## 成果物

- `build/distributions/OuterTune-player-adjustments-20260913-arm64.apk`
- coreRelease / arm64-v8aのみ / 0.10.2-b1 (71) / com.dd3boh.outertune
- SHA-256: `70a3f0115795d67e7cfb8ee7fca636289736f2170e4b14c5bc6476bd6622a728`
- APK v2署名検証PASS。対応mappingは `build/distributions/OuterTune-player-adjustments-20260913-mapping.txt`。
- 歌詞調整も同じAPKに含む。今回の人物重複は調査のみで、修正は含まない。

検証終了後、アプリを停止し、エミュレータのWi-Fi/データ設定を復帰。今回起動したPIDとAVD/port 5556の起動引数を照合して停止し、操作占有を解除した。
