# 2026-10-01: チャンネル由来アーティストの保存と手動紐づけ

## 依頼と合格条件

- 開始点: `8bf3329c`、作業開始時の差分なし。
- 動画の投稿チャンネル名をアプリ内のアーティストとして保存し、フォルダ由来と同じ手動検索・候補確認・紐づけ・変更・解除を可能にする。
- 曲に明示されたアーティストを優先する。投稿チャンネルIDだけでオンラインアーティストとの同一性を確定しない。
- チャンネル由来の区別、元の名前・チャンネルID、曲との保存関連を保持する。同じチャンネルIDは共通の内部ID、同名でも別のチャンネルIDは別の内部IDにする。
- 紐づけ対象の保存曲数・曲一覧を確認できる。解除は元の表示・内部ページへ戻る。
- DB保存、プレイヤーとライブラリからの遷移、名前・画像の表示、再起動後の維持、薄い応答・通信失敗時の保持を検証する。

## 実装

- 動画の構造化クレジットを解析し、明示人物を投稿チャンネルより優先する。USER_CHANNELや種類がないUCリンクは、オンライン人物IDにせずチャンネル由来として保持する。アルバムの人物には投稿チャンネルを採用しない。
- プレイヤー応答の投稿者も、動画であり明示人物がない場合に保存用クレジットとして受け入れる。名前検索による自動紐づけは行わない。
- DB 30→31: `artist.isChannel` と `artist.sourceChannelId`、表示ビューを追加・更新。自動移行で既存行を保持。schema31のidentity hashは `7c010d4f75976eeb36f6e66dad8f63e8`。
- チャンネル内部IDは `CS`＋チャンネルIDのハッシュ。IDが欠ける場合は動画ID・名前に限定する。同名の別チャンネルや、同じIDを持つオンライン人物とは別の保存元にする。
- 既存の手動紐づけをチャンネル保存元にも適用。元の名前・ID・クレジット・曲関連は保持し、表示と遷移先を選択したオンライン人物へ切り替える。変更・解除に対応する。
- 紐づけ前と管理画面で元チャンネルのID、保存/ダウンロード済みの曲数・曲一覧を表示する。IDがある場合は今後の同じチャンネルの曲にも適用し、IDが欠ける場合の説明は表示曲に限定する。
- 薄い応答・遅延応答で既存の人物情報を壊さない。明示人物へ更新した動画だけを旧チャンネルの文脈から除き、ほかの動画を保持する。

## 検証

- 対象ソース: `8bf3329c` を親とする本改修（コミット前の最終差分で実行）。検索の後続区間・列・カードでの人物優先、ID欠損時の説明、保存オンライン曲の見出しまで反映した最終ソースで確認。
- `Pixel_9_API_35` / `emulator-5554`（x86_64上のarm64変換）をrootが操作。実機やユーザーの認証・音源は使用していない。隔離DBの端末テストと、合成2曲の通常UIを使う。
- 検証PCのrelease実通信の証明書制約は既知。証明書設定は変更せず、再調査していない。debugの検索も今回の環境では失敗した。オンライン検索・候補取得・リンク保存の実通信成功は未確認。
- Release UI fixtureはschema31から新規生成し、1チャンネル保存元・2保存オンライン曲・2元の曲関連・1合成リンクのみを含む。名前・ID・クレジットはdebug fixtureから保持し、画像は同じ合成PNGをdata URIとして埋め込んだ。設定は5つのUI項目だけを許可リストで抽出し、visitorDataや認証項目は含めない。`integrity_check=ok`、外部キー違反0、表示先2曲を確認。生成スクリプト: `build/diagnostics/channel-artist-20261001/prepare_release_fixture.py`。

### 自動テスト

| 確認 | 結果 |
| --- | --- |
| `:innertube:test` | PASS、149件。全162件中13件は既存の実通信fixtureのopt-inスキップ、失敗/エラー0。テスト本体3.177秒。チャンネル解析の新規13件を含む |
| `:app:testCoreDebugUnitTest --tests '*Artist*'` | PASS、71件、スキップ/失敗/エラー0。テスト本体1.066秒 |
| 最終debug APKでのDB・リポジトリ・再生・遷移関連instrumentation | PASS、45件、11.541秒 |

最終Gradle呼び出し:

```text
gradlew.bat :innertube:test :app:testCoreDebugUnitTest --tests '*Artist*' :app:assembleCoreDebug :app:assembleCoreDebugAndroidTest :app:assembleCoreRelease --console=plain
```

- 端末テストのクラス: `ChannelArtistDatabaseTest`、`ChannelArtistCreditRepositoryTest`、`ChannelArtistProjectionPlaybackTest`、`LocalArtistLinkDatabaseTest`、`ArtistGroupingDatabaseTest`、`ArtistCreditDatabaseTest`、`ArtistPageLinkTest`、`ArtistImageDatabaseTest`、`LocalArtistProjectionPlaybackTest`。
- 同じチャンネルIDの再利用と改名、同名別チャンネル、ID欠損の動画ごとの区別、同名フォルダ/オンライン人物との分離、紐づけ・変更・解除と元クレジット/曲関連の不変性、保存済み/ダウンロード表示、ディスク再読込を確認。
- 遅延したチャンネル応答が確定人物を戻さないこと、明示人物へ更新した動画だけがチャンネル文脈から外れること、キャッシュ再読込と薄い応答で元のチャンネル情報が保持されることを確認。
- 再生テストは実際のExoPlayerと生成WAVを使う。紐づけ・変更・解除で名前と人物の遷移先を更新し、再生位置の進行、seek、URI・元のメタデータ・キュー件数・チャンネルIDを保持する。実ネットワークの音源再生はこの検証に含めない。
- ログ: `build/channel-artist-final.log`、`build/diagnostics/channel-artist-20261001/final-test-metrics.json`、`build/diagnostics/channel-artist-20261001/instrumentation-final.txt`。

### 通常画面と成果物

最終release APKをエミュレータへ更新インストールし、合成fixtureを通常の復元UIで読み込んだ状態で確認。途中版のスクリーンショットは以下のPASS画像に使用していない。

| 操作・確認 | 結果 |
| --- | --- |
| ライブラリ→紐づけ先人物の内部ページ | PASS、選択した人物の名前・合成画像で2曲表示。保存オンライン曲の見出しは「曲」 |
| 人物メニュー→紐付け元を確認→曲を展開 | PASS、元チャンネル名・ID、現在の紐付け先、2保存曲のタイトル、同じチャンネルの今後の曲にも適用する説明を表示 |
| 管理画面→変更・解除 | PASS、元チャンネル名と2曲、現在の選択先、検索欄、変更・解除の操作を表示。オンライン候補の取得成功/変更保存の実通信は未確認 |
| 解除確認→解除→ライブラリ | PASS、管理対象からリンクが消え、元のチャンネル名のアーティストと2曲へ戻る。元の画像も保持 |
| アプリ終了・再起動 | PASS、紐づけ済み状態で2曲と選択先を維持。解除後も再起動して元のチャンネル名と2曲を維持 |
| 設定→アーティストの紐付け | PASS、同じ管理画面に到達。解除後の「該当する紐付けはありません」を確認 |
| debugでの検索失敗 | PASS、対象曲とチャンネルIDを表示したままエラーを表示し、候補がない状態では確定を無効化。停止後のDB snapshotにも新規リンクなし |
| DB 30→31 | 既存debug DBがschema31で開き、以前の合成Nirvanaデータを維持。新規fixture挿入も成功。生成された自動移行は2列追加と表示ビュー再作成 |
| ビルド・署名・ABI | PASS、最終Gradle全体3分57秒、lintVital通過。coreRelease / arm64-v8aのみ、APK v2署名検証成功、signer1 |
| 差分・クラッシュ | PASS、`git diff --check`（CRLFを許容）、端末crash buffer空。既存の非推奨API/外部Compose mapping警告のみ |

画面画像:

- [紐づけ後の人物と2曲](../images/2026-10-01-channel-artist-link/linked-artist.png)
- [元チャンネルと対象曲の管理](../images/2026-10-01-channel-artist-link/source-tracks.png)
- [解除後のチャンネルと2曲](../images/2026-10-01-channel-artist-link/unlinked-artist.png)

成果物:

- APK: `build/distributions/OuterTune-channel-artist-link-20261001-arm64.apk`
- coreRelease / arm64-v8a / 0.10.2-b1 (71) / `com.dd3boh.outertune`
- SHA-256: `d7b5e76b089645331409f1c37b3e53056475ac4975311a70f8be48ed75e935f5`
- mapping: `build/distributions/OuterTune-channel-artist-link-20261001-mapping.txt`
- 最終ビルドログ: `build/channel-artist-final.log`。画面PNG/XML・合成fixture・署名検証・crash buffer: `build/diagnostics/channel-artist-20261001/`。
- 通常UIのオンライン検索・候補取得・リンク保存、実ネットワークの音源再生は環境制約で成功未確認。保存・競合制御・投影は決定的なテストと端末テストで確認した。実機そのものでは未確認。
- rootによるエミュレータ操作を終了し、今回起動したエミュレータのみ停止。画面密度・文字倍率・証明書設定の変更なし。
