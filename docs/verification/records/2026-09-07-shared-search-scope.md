# 全入口で共通の検索範囲と通信断時のローカル検索

開始HEAD `4def7ed1`、ブランチ `restart/artist-20260905`。直前に出所フィルター改修の検証を完了し、その未コミット差分を保持した状態で着手。

## 現象と合意した仕様

- 既存の検索範囲保存は共通だが、ホーム／曲からの入力欄操作でオンライン／ローカルへ強制上書きされる。開いた入力欄の再タップでも発生する。
- 検索は独立した共通機能。全入口の初回はオンライン、それ以降は手動選択を画面移動・再起動後も保持する。
- 旧保存値には手動選択と自動上書きの区別がないため、新仕様へ移行する最初の一度はオンラインを初期値とする。
- 通信不可ならローカルへ自動切替。入力語を保持し、実際の範囲・表示・検索確定／履歴／候補選択を一致させる。自動切替で保存した手動選択を変更しない。
- 通信復帰だけで操作中のローカル結果を置換しない。次の検索開始時に保存した選択へ戻る。通信不可中はオンライン選択を無効化し理由を表示する。
- ローカル検索はアプリ内DBの検索。フォルダ限定ではなく、保存済みオンライン情報も検索する。検索できることとオフライン再生できることは区別する。

## 範囲と合格条件

1. ホーム／曲／ライブラリ／フォルダ／検索結果の再編集とショートカットを同じ共通状態へ接続する。
2. 初期値・手動選択・再タップ・移動・再起動で入口による上書きをしない。
3. 初回オフライン、検索中の通信断、復帰、ネットワーク切替に対応する。遅れて届くオンライン応答でローカル結果を置換しない。
4. 入力確定・履歴・候補・結果表示で同じ範囲と検索語を使用する。
5. 関連単体テストと端末での状態・永続化確認を実行する。必要なAPKはcoreDebug/arm64-v8aのみ。

## 実装・検証

- `SharedSearchScope`をActivity単位で共有し、新しい`preferredSearchSource`設定には手動選択だけを順番に保存する。切替自体は即時反映し、設定の読み込み完了までは検索を実行しない。
- ONLINE/LOCALの明示的な選択肢を入力欄の下と結果画面に設置。通信不可の説明とオンライン選択の無効化、復帰後の現在検索LOCAL維持を同じ状態から表示する。
- 通信状態は既定回線のINTERNET＋VALIDATEDで判断する。初期値を持つStateFlowで複数利用箇所に配信し、古い回線の切断通知・初期取得との競合・監視解除後の通知を保護する。正常な回線切替のonAvailableだけではオフライン判定しない。
- Activityで監視を毎回の再描画ごとに登録し直す処理を廃止。初期化時の現在値確認は行うが、定期的な接続確認通信は追加しない。既存の同期・歌詞・再生復帰も同じ到達性判定を使用する。
- IME／物理Enter／履歴／候補を共通の検索確定処理へ接続し、結果ルートもONLINE/LOCALを切替える。物理EnterはKeyUpで一度だけ確定する。
- 非表示のオンライン結果・候補は要求をキャンセルする。APIがResultでキャンセル例外を包んだ場合にも採用前にキャンセルを確認する。候補は再開時に現在の語を先に渡し、現在の語と一致する応答だけを表示する。
- アプリアイコンの検索ショートカットは未起動／起動済み両方から共通の検索欄を開く。検索欄が二重に生成される旧ルートを整理した。

## 途中確認と修正

- 初回の関連単体21件（検索10・接続11）はPASS、失敗・除外0。テスト本体合計0.330秒、APK／テストAPK生成込み1分14秒。`build/shared-search-build.log`、`build/shared-search-unit-results/`にXML保全。
- 実DataStore＋製品Composeコントロール4件と実接続監視1件はPASS、11.833秒。`build/shared-search-device-tests.log`。接続監視ではWi-Fiとモバイルを実際に無効→有効にし、複数利用箇所へ通信断が届くことと復帰を確認した。
- 通常アプリの初回起動は、検証用AVDに残る旧試作版DBの同一version内のschema不一致で失敗した。HEADと今回のschema hashはともに`37fe9550b87aa8b46dd773556479d6ae`、旧DBは`cb7d68b8a19a04ca386dd67afdd8ba4e`。今回schema変更はない。read-onlyエミュレータ内のdebugアプリだけ初期化し、起動・WelcomeのSkip後に確認を続行した。元AVDの保存状態は変更しない。
- 通常画面で曲から初回ONLINE、LOCAL選択後のライブラリ入口での保持、LOCAL確定後の結果画面、Quruliのオンライン結果、通信断中の同語LOCAL表示、復帰後の同語LOCAL維持、次にフォルダから開いたときのONLINE復帰を確認。XML/PNGは`build/shared-search-*.xml`・同名PNG。オンライン結果の種別表示と上部余白も確認した。
- coldショートカットで入力欄は開くがfocusが付かないことを確認し、表示とwindow focusに連動する独立したEffectに変更。差分ビルド37秒・検索単体10件PASS、端末scope4件PASS（11.428秒）。修正後はcold/warmともfocused=true、タップなしで`focuscheck`入力成功。`build/shared-search-focus-build.log`、`build/shared-search-scope-tests-final.log`、`build/shared-search-cold-focus-final.xml`、`build/shared-search-warm-focus-final.xml`。
- 回転でphone/tabletの検索欄配置が切り替わる際、入力した`rotationprobe`が空になることを通常画面で確認。入力状態をActivityのViewModelへ移したが、それだけでは改善しなかった。復元した画面の検索要求を監視するとき、切替前のFlow値が一時的に使われ、新規検索と誤認して入力を消去する経路も修正した。画面ごとに購読状態を作り、遷移処理ではその画面のSavedStateHandleの現在値を参照する。

途中版で確認した結果を最終成果物への全確認済みと扱わず、最終差分の結果は以下に追記する。

## 最終差分の確認結果

最後の修正は検索欄の復元処理のみ。検索範囲の状態管理、通信監視、検索要求のキャンセル、出所フィルターは、それぞれ上記検証の時点から変更していない。

| 対象 | 結果 | 証拠 |
| --- | --- | --- |
| 検索の単体テスト再実行 | PASS、10件、失敗・除外0、0.082秒 | `build/shared-search-unit-results/TEST-com.dd3boh.outertune.ui.screens.search.SearchSourceTest-final.xml` |
| coreDebug arm64 APK生成 | PASS、単体テストと合わせて15秒 | `build/shared-search-entry-restore-build.log` |
| 未起動から検索ショートカット | 入力欄focused=true、タップなしで入力成功 | `build/shared-search-entry-cold.xml`、`build/shared-search-entry-before-rotation.xml` |
| 縦画面から横画面への回転 | `rotationprobe`とfocused=trueを保持 | `build/shared-search-entry-before-rotation.xml`、`build/shared-search-entry-after-rotation.xml` |
| 起動済みから検索ショートカット | 新規検索として入力を空にし、focused=true | `build/shared-search-entry-warm.xml` |
| 最終端末確認時のアプリクラッシュ | crashバッファに該当記録なし | `adb logcat -b crash`による確認 |
| 差分の空白チェック | PASS | `git diff --check` |

最終APKは`app/build/outputs/apk/core/debug/OuterTune-0.10.2-b1-core-arm64-v8a-debug-71.apk`。出力metadataはcoreDebug、ABIはarm64-v8aの1件のみ。SHA-256は`9BD4650C0445B04EBC6DCEDD1B7E9054AE4CE2F061B1A7B42E063A1F7B266F67`。今回releaseは生成していない。

Wi-Fi・モバイル通信はともに有効、画面回転設定は元の自動回転有効・user_rotation=0へ復元した。検証にはread-onlyのPixel 9 / API 35を使用し、ユーザーの実機・保存AVDのデータは変更していない。未確定の入力語は画面回転で保持するが、アプリの強制終了後まで保存する仕様は追加していない。検索範囲の手動選択は再起動後も保存する。
