# 2026-09-13: 手動紐付け人物をオンライン先へ統合表示

## 依頼・基点

- 前回の手動紐付け/ローカルの曲への見出し変更を `673e50f4` にコミットした後、統合改修を開始。
- オンライン由来人物とフォルダ由来の紐付け人物をアプリ上の一人物にまとめる。表示名・画像・検索・人物への移動・曲一覧はオンライン統合先を正とする。
- ユーザー訂正：元ファイル表記を検索別名として増やす案は採用しない。統合先の通常のオンライン名称検索へ従う。
- 統合人物の三点メニューでフォルダ由来の紐付け元を管理。0曲/非表示の元も設定から管理する。

## 実装方針・合格条件

- 元のlocal ID/ファイルタグ/ArtistCredit/SongArtistMap/AlbumArtistMap/曲ID/音源path/いいね/履歴を維持し、共通の読取モデルで統合する。
- 一覧はcanonical online IDで一件、保存済みオンライン曲＋紐付け元のfolder曲を曲IDで重複排除して集約。異なる曲IDの配信版/ファイル版を同名で自動合体しない。
- 検索・人物名ソート・曲数・Folder/Downloads等の条件でも同じ統合先を使う。条件判定は曲の出所を維持する。
- 既存のコンテンツ言語/英語表記利用に従い、アプリ表示言語では紐付けや元データを変更しない。
- 未保存のonline先/online曲全削除でもlocal linkから代表人物と管理入口を維持する。
- 人物のいいねは既存online側が正。online人物が未保存なら元local群の状態を初期表示に利用し、統合後の切替はonline代表へ保存。元localの状態は保持して解除時に戻す。
- 元別の名前/曲数/フォルダを確認でき、個別変更/解除/キャンセル可能。変更・解除・再起動・再スキャン・通信失敗・遅延応答で別人物や不明へ変化しない。
- DB25→26は統合viewの追加を中心とし、元データを保持して移行する。

## 検証予定

- 単体：共通表示/検索/管理状態と取消・古い選択。
- Android Room：複数元＋online一件、曲集約/重複/filters/search/sort、未保存先、全online曲削除、変更/解除、bookmark、移行、raw保存情報の維持。
- UI/再生：合成ファイル曲とonline曲を混在させ、統合一覧→人物→全曲/検索→再生/プレイヤー→管理変更解除→再起動を確認する。
- 最終core-release / arm64 APKを生成。既知の検証環境の配布版TLS制約は今回も再確認し、実行できた内容と未確認内容を区別する。

## ビルド前のSQL性能観測

- Windowsホストの使い捨てインメモリSQLite 3.53.1で観測。コミット済みv25 schemaの実テーブル・既存indexを作り、新view/DAO queryを実行した。ユーザーデータ、Android端末、ネットワークは使用していない。Gradle並行実行中の参考値であり、Android実機の速度保証ではない。
- データはオンライン人物2,500＋フォルダ人物2,500、手動リンク2,500、各元に1曲ずつ計5,000曲。代表一覧は2,500件、単一人物名の曲検索はオンライン/フォルダの2曲。
- 旧相関subquery版：代表一覧9.949秒、曲検索10.240秒、表示mapping取得は20秒を超えて打切り。candidate比較を単純なID/onlineId ORにするだけでは一覧5.495秒、検索4.935秒、mappingは20秒超で、十分に改善しなかった。
- 採用方式：canonical単位の代表rowIdとbookmark/日時をGROUP BYで一度集約し、remote/sourceをrowIdでJOINする。linkの代表もonlineArtistId単位のMIN(localArtistId)で先に集約する。window関数や新しいSQLiteのMATERIALIZED構文は使わない。
- 旧新の同値確認：保存済みcanonical、オンラインsurrogate、未保存先、複数元、onlineId優先、異なる挿入順、画像/channel/bookmarkのnullありを混ぜた94代表について、全列・全行が一致した。
- 集約版の同じ規模での3回観測：代表一覧0.0397/0.0382/0.0619秒、曲検索0.0176/0.0169/0.0170秒。mappingのsource判定はJOIN順による集約の繰返しを避け、同値のWHERE EXISTSに変更。人物/linkが同規模のmapping専用観測では2,500件を0.0244秒で取得した。
- Android用に `ArtistGroupingPerformanceTest#fiveThousandSourceArtistsRemainGroupedAndSearchable` を追加。1 transactionで同規模を作り、3 queryを各1回読み、件数/対応関係と各20秒の完了期限を確認する。SQLite版と経過msを `ArtistGroupingPerf` に記録する。上記ホスト観測とは分けてルートが明示実行する。

## 最終実装

- DB25→26で `artist_identity` / `artist_display` / `artist_song` / `artist_album` の読取viewを追加。24個の既存entity schemaはv25と一致し、タグや曲・人物の関係を移し替えない。
- 通常人物一覧、曲検索、フォルダ検索、人物内の曲/アルバム、メディアブラウザ、曲数、ソートを統合先へ揃える。元の人物を扱うscanner/保存/管理にはraw DAOを使う。
- `ArtistDisplayRepository` が明示linkだけをアプリ共通の表示モデルへ公開。人物選択、アルバム見出し、曲行、プレイヤー、通知、キュー内検索へ反映。再生用tag/URI/cache key/元creditは置換しない。
- フォルダの表示名順ソートとプレイリスト内検索は、紐付け変更・名称設定変更でも再計算する。
- 統合人物と設定から元別の管理画面へ入り、元表記・曲数・フォルダを確認できる。子編集画面は自分の保存/解除で元の一覧行が消えても途中で破棄しない。
- 人物ページはcanonical代表から取得先を決定。リンク変更、言語・認証変更、同じアカウントへ戻る認証世代変更でも古いページを採用しない。既存オンラインprofileの画像更新だけを許し、元ローカルprofileは更新しない。
- YouTubeアカウント同期の停止とログイン機能、既存コンテンツ言語設定は維持。

## 最終ソースの実行結果

対象：`673e50f4` + この改修の未コミット差分。Gradle/ADBはrootのみで逐次実行。Pixel_9_API_35 / emulator-5556 の使い捨てデータを使用し、実機スマートフォンは操作していない。

| 確認 | 結果 |
| --- | --- |
| JVM: ArtistDisplayTest / ArtistDisplayProjectionTest / ManualArtistDisplayProjectionTest / ArtistDisplayRepositoryTest / LocalArtistLinkViewModelTest / LocalArtistLinksViewModelTest / FolderSongsTest | PASS、計38件。最終 `:app:testCoreDebugUnitTest` + debug/test APK生成のGradle全体53秒 |
| Android: ArtistGroupingDatabaseTest / LocalArtistLinkDatabaseTest / ArtistPageLinkTest / LocalArtistProjectionPlaybackTest / ArtistImageDatabaseTest / ArtistCreditDatabaseTest / ContentSourceFilterDatabaseTest / YouTubeSyncDisabledTest / ArtistGroupingPerformanceTest | PASS、計41件、試験本体12.604秒 |
| Android大規模読取 | PASS、SQLite 3.44.3、人物5000/曲5000/link2500。代表一覧2500件134ms、表示mapping2500件23ms、統合先名の曲検索2件47ms |
| LocalArtistLinkUiSeedTest (`seedLocalArtistLink=true`, `seedCanonicalArtistGroup=true`) | PASS、1件、0.563秒。2つの合成WAVと表示確認用online曲、0曲の元を準備 |
| 最終debug実通信・新規手動link | PASS、Sheena Ringo検索→画像/名前/3アルバムの候補確認→明示保存。開いたまま人物名が変わり、元A/元B/online曲の3件が「ローカルの曲」に集約 |
| 最終debug検索/管理 | PASS、統合先名のローカル検索で人物一件/曲三件。元A/Bの名前とフォルダ、0曲の元も管理画面に表示。0曲の元を解除しても親画面へ戻り、A/Bを保持 |
| debug実操作後のDB | PASS、元A/BのID/name/isLocal/onlineId、元のSongArtistMapを保持。表示先だけ同一online ID、対象曲数3件 |
| `:app:assembleCoreRelease` | PASS、3分38秒、lintVital通過。既存外部ライブラリのCompose mapping警告あり、APK/署名生成成功 |
| 配布版: 復元→一覧→人物→ローカルの曲 | PASS、合成fixtureをアプリの復元UIで導入。統合人物一件/3曲、人物ページのローカル2曲＋保存online1曲を確認 |
| 配布版: 再生/人物への移動 | PASS、自作WAVのプレイヤー0:02/0:06、MediaSession PLAYING(3)/speed1.0/buffered6000/errorなし。プレイヤーの名前から統合人物へ移動 |
| 配布版: 通信失敗/解除/再起動 | PASS、オフライン検索失敗で旧link保持。元Aのみ解除し、統合先にはB＋onlineの2曲、再生中だった曲の人物表記は元Aへ復帰。再起動後も2曲＋元Aの1曲を維持 |
| 配布版: 設定/検索 | PASS、設定の「アーティストの紐付け」から残る元Bを管理できる。解除後のSheena Ringo曲検索はB＋onlineの2件、元Aの曲を含まない |
| schema/差分/成果物 | PASS、v25既存entity24個一致、schema26が最終集約CTEを含む。差分check、arm64-v8aのみ、APK署名v2検証成功 |

最終近傍テストは計79件（JVM38＋Android41）。fixture seedは別。最終テスト/ビルド後のプロダクションコード変更はない。

## fixture補正・実施範囲

- 初回の合成人物は公式channel由来の `UCRQX-dpFt_osBpH71ItuuvA` を使用したが、今回の検索で選択したSheena Ringoは `UCbrWU0y_rLsEOYgaTX5Y74A`。同名でも別IDなので統合されないのが正しい。fixtureを実検索のIDへ合わせて最終確認し、同名/別IDを自動合体しないDB試験も追加した。
- 配布版用合成fixtureの最初の復元は、スキャン対象フォルダ未設定のまま自動スキャンが走り、手動投入した曲が対象外になった。`/sdcard` とMediaStoreの正規pathの差も排除した。最終fixtureは `/storage/emulated/0/Music/OuterTune-canonical-artist` にWAVを登録し、**エクスポートした合成設定だけ**自動スキャンをOFFにして再復元。曲数3・再生成功を確認した。アプリのscannerコードやユーザー設定は変更していない。
- 今回の確認は、任意の音源タグ形式、実機の長時間スキャン運用、全言語/全アカウント組合せの網羅を意味しない。再スキャン時の元情報保持は既存のproduction DAO/重複整理の回帰試験で確認。
- **BLOCKED（配布版の実通信）**：このPCのHTTPS検査環境では引き続き `SSLHandshakeException / CertPathValidatorException: Trust anchor for certification path not found` が発生。配布版の信頼設定は変更していない。今回の実検索/候補確認/新規linkは既存debug構成で成功し、最終releaseは保存済み情報・再生・管理・オフライン検索を確認した。releaseの実通信は検査証明書の問題がない接続環境で確認が必要。
- x86_64エミュレータ上のarm64変換で実行。スマートフォンへのインストール・実ユーザーの認証/バックアップ/音源の使用は行っていない。

## 配布成果物

- `build/distributions/OuterTune-canonical-artists-20260913-arm64.apk`
- coreRelease / arm64-v8a / 0.10.2-b1 (71) / com.dd3boh.outertune
- SHA-256: `f5b0e99a4b869d0cdbc23d43576a64dc690205bf87f1a03cbba11398f83fe03c`
- 対応mapping: `build/distributions/OuterTune-canonical-artists-20260913-mapping.txt`
- ログ: `build/canonical-*.log`。画像/合成DB/復元fixture: `build/diagnostics/canonical-artist-20260913`（git管理外）。

- 検証終了後、アプリを停止しエミュレータのWi-Fi/データ設定を復帰。今回起動したAVD/port 5556のプロセスだけを起動引数で照合して終了し、操作占有を解除した。
