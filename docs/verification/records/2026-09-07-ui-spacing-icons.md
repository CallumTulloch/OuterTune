# 初期案内の余白とソースアイコンの調整

開始HEAD: `4def7ed10c8862e17c1e2948a4fc17c57d84510e`。既存の検索範囲・出所フィルター等の未コミット変更を保持して着手。

## 現象・期待結果・範囲

ユーザーから依頼された次の表示調整のみを対象とする。

1. 初期案内の「バックアップがある」「スキップ」を左へ寄せ、小さい画面で右下の矢印との余白を確保する。バックアップ文字の先頭を上の警告のiアイコン付近へ合わせる。
2. アーティスト画面の切替ボタンは、ローカル表示中にLibraryMusic、オンライン表示中にLanguageを表示する。
3. 検索条件のオンライン／ローカルのアイコンは未選択時も選択時と同じ色にする。

根拠: 初期案内の操作行は左右48dpの余白、警告アイコンは外側のColumnから28dpの位置。アーティストのアイコン条件は切替先を表示している。検索条件はFilterChip標準の未選択アイコン色を使用している。

## 合格条件・検証予定

- 小さい幅で初期案内の位置と矢印からの間隔を画面確認する。
- アーティストの両表示と検索条件の両選択状態を画面確認する。
- 軽微な表示変更のため実装をなぞる単体テストは追加しない。画面確認用のcoreDebug / arm64-v8aを生成する。
- 最終差分・実行結果・未確認事項を以下に追記する。

## 最終実装

- 初期案内: 操作行の余白を左右48dpから開始16dp／終了64dpへ変更。バックアップ文字の左端は画面の44dp位置となり、警告アイコンの配置開始位置と一致する。幅不足時は`FlowRow`でボタン全体を次行へ送る。
- アーティスト: `showLocal`がtrueならLibraryMusic、falseならLanguageに変更。切替処理はそのまま。
- 検索条件: 共通の`FilterChipDefaults.filterChipColors(iconColor = onSurfaceVariant)`を両方に適用。未選択アイコンをラベルと同じ白系の色にし、明テーマでは暗い色へ追従する。選択時・無効時の配色は既存テーマを維持する。

## 途中確認からの調整

- 最初の余白変更では360dpでも「スキップ」が文字の途中で折り返したため、終了余白を再調整しFlowRowへ変更した。
- 選択時の色`onSecondaryContainer`を未選択へ直接流用すると、検証端末の動的テーマでは黒色となって暗い背景で見づらかった。未選択ラベルに対応する`onSurfaceVariant`へ変更し、表示状態ごとのコントラストを保った。
- 上記途中版は最終成果物のPASSに含めない。最終ソースのAPKで下記を再確認した。

## 最終確認結果

対象: 開始HEAD＋既存の未コミット変更＋本改修。Pixel 9 / API 35のread-only・headlessエミュレータ（x86_64＋arm64変換対応）で、coreDebug / arm64-v8aを実行。端末の日本語・暗テーマ、密度320dpiを使用した。

| 対象 | 結果・証拠 |
| --- | --- |
| 最終APKのビルド | PASS。`:app:assembleCoreDebug --console=plain`、13秒。`build/ui-spacing-icons-build-final.log` |
| 初期案内360dp幅 | PASS。バックアップ文字の左端88px、スキップは1行。文字右端536pxと矢印ボタン左端576pxの間に40px。`build/ui-spacing-icons-welcome-360-final.xml`／PNG |
| 初期案内320dp幅 | PASS。両ボタンは全文1行を保ち、スキップのボタン全体が次行へ移る。文字左端はいずれも88px、矢印との干渉なし。`build/ui-spacing-icons-welcome-320-final.xml`／PNG |
| 検索範囲の両選択状態 | PASS。未選択のLibraryMusicとLanguageがラベルと同じ白系で表示。`build/ui-spacing-icons-search-online-final.xml`／PNG、`build/ui-spacing-icons-search-local-final.xml`／PNG |
| アーティストの両表示 | PASS。検索候補のニルヴァーナからオンライン画面へ移動し地球アイコンを確認。ボタンを押し、内部の曲表示とLibraryMusicへ切り替わることを確認。`build/ui-spacing-icons-artist-online-final.xml`／PNG、`build/ui-spacing-icons-artist-local-final.xml`／PNG |

全PNGを直接見て確認した。debug共通の赤い端末情報表示は既存のもので、本改修の対象外。軽微な表示変更のため単体テストは追加していない。

最終APK: `app/build/outputs/apk/core/debug/OuterTune-0.10.2-b1-core-arm64-v8a-debug-71.apk`。出力metadataはcoreDebug / arm64-v8aの1件。
SHA-256: `C8032D7B6CC10E7366200CF7EEA539E5A70F6506CD44447FCABDC6A5EA5C2582`。

上記debug確認の時点ではrelease生成・実機確認は実施していない。アプリデータの初期化・日本語指定・画面サイズ変更は一時的なread-onlyエミュレータ内でのみ行い、保存AVDとユーザー実機は変更していない。

最終の`git diff --check`はPASS。今回の端末確認でcrashバッファは空（`build/ui-spacing-icons-crash-final.log`）。検証用エミュレータは停止済み。変更は未コミットで保持している。

## 追加依頼: coreRelease / arm64-v8a

ユーザーの追加依頼により、上記と同じソースから配布用releaseを生成する。検索範囲・出所フィルター等の既存未コミット改修も含む。

- 関連単体テスト: PASS。SearchSourceTest 10件、ArtistDisplayTest 9件、失敗・除外0件。テスト本体合計0.122秒。XMLは`build/ui-spacing-icons-release-unit-results/`に保全。
- 実行: `:app:testCoreDebugUnitTest --tests com.dd3boh.outertune.ui.screens.search.SearchSourceTest --tests com.dd3boh.outertune.utils.ArtistDisplayTest :app:assembleCoreRelease --console=plain`。
- ビルドログ: `build/ui-spacing-icons-release-build.log`。
- ビルド: PASS、テストとコンパイル・縮小処理を含め3分42秒。既存ライブラリのCompose mapping収集等の警告はあるが、ビルド・releaseの必須lintは成功。
- 成果物: `app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`。8,394,046 bytes、versionName `0.10.2-b1`、versionCode `71`。
- SHA-256: `5BD1AB6C6ECFD94DAD9C251FE18A2BED14A6B03B1EC5A81428C8905863613359`。
- 署名: PASS。`apksigner verify --verbose`でAPK v2署名を検証。`build/ui-spacing-icons-release-signature.log`。
- 構成: PASS。出力metadataはcoreRelease / arm64-v8aの1件、`aapt2 dump badging`でもnative-codeはarm64-v8aのみ。applicationIdは`com.dd3boh.outertune`、debuggable指定なし。`build/ui-spacing-icons-release-badging.log`。
- release実行: PASS。read-onlyのPixel 9 / API 35（arm64変換対応）、日本語・暗テーマ・360dp幅でインストール・正常起動。初期案内の文字先頭と矢印の余白、スキップ後の検索ショートカット、オンライン／ローカル両選択時の未選択アイコンの白系表示を直接確認した。
- 画面証拠: `build/ui-spacing-icons-release-welcome.xml`／PNG、`build/ui-spacing-icons-release-search-online.xml`／PNG、`build/ui-spacing-icons-release-search-local.xml`／PNG。クラッシュ記録なし（`build/ui-spacing-icons-release-crash.log`）。
- アーティストの両表示・320dp幅の詳細な表示確認は前記debug版で実施済み。今回releaseでは再実行していない。arm64実機での確認は未実施。
- エミュレータのreleaseアプリは今回新規インストール。検証用エミュレータを終了し、保存AVD・ユーザー実機には変更なし。
