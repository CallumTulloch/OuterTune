# 再検索で古い検索結果が残る問題の調査

対象HEAD: `4def7ed10c8862e17c1e2948a4fc17c57d84510e`＋既存の未コミット変更。ブランチ`restart/artist-20260905`。

- ユーザー報告: 一度検索した後、違う検索条件を入れても反映されない。最初の選択式回答はオンライン／ローカルだったが、続く確認に対して「切り替えが反映されないとは何の話かわかりません」と回答された。切替障害と断定した解釈を撤回し、元の再検索の報告へ戻って調査した。
- 期待結果: 新しい検索条件と実際の検索結果が一致する。
- 今回の範囲: 調査のみ。アプリのソース修正・新規ビルドは行わず、既存成果物とコードから再現条件・原因・修正方針を整理する。
- 確認項目: 検索入力中／結果表示中、両方向の範囲切替、入力パネルと結果画面の状態共有・画面配置。

## 調査結果

既存coreDebug / arm64-v8a APKを、read-onlyのPixel 9 / API 35（x86_64＋arm64変換対応）へインストールした。新規debugデータ、日本語、暗テーマ、360dp幅。ビルドは実施していない。
使用APKのSHA-256: `C8032D7B6CC10E7366200CF7EEA539E5A70F6506CD44447FCABDC6A5EA5C2582`。先に画面確認した版と同一。

### 再現した不具合

1. 検索欄へ`Nirvana`を入力し、文字の候補`nirvana`を押して検索確定。Nirvanaの結果が表示される。
2. 結果画面の検索欄を開き、文字を消して`Quruli`を入力。入力中の候補は正しくくるりへ変わる。
3. 候補`quruli`を押して検索確定。検索欄は`quruli`だが、結果はニルヴァーナ、Scoff等のまま残る。
4. 続けて「曲」へ絞ると、新たに表示される一覧もNirvanaの検索結果になる。
5. 結果画面から戻り、入力欄から改めて`Quruli`を検索すると、くるり、GO BACK TO CHINA、別れ、三日月等の正しい結果になる。

不具合の再現はCONFIRMED。コードから予測した現象と一致した。ユーザー端末の個別操作列を完全に確認したという意味ではない。

証拠（各XML／同名PNGを保存、PNGを直接確認）:

- `build/search-source-investigation-result-online`
- `build/search-source-investigation-new-suggestions`
- `build/search-source-investigation-second-result`
- `build/search-source-investigation-second-result-songs`
- `build/search-source-investigation-new-entry-result`

### 原因と変更履歴

- `SearchScreen.kt:220`の検索確定で`launchSingleTop = true`を指定。同じ`search/{query}`の結果画面で再検索した際に、結果を持つViewModelが再利用される。
- `OnlineSearchViewModel.kt:31`は初期のSavedStateHandleから検索語を`val query`へ一度だけ読み、その後の語の変更を受け取らない。
- 同ViewModelは`summaryPage`と種別別の`viewStateMap`も保持する。既存結果がある場合は取得を省略し、新しい種別を選んだ場合でも最初の`query`で通信する。
- 検索欄は新しい画面引数を表示するため、「検索欄だけ新しく、結果と問い合わせ語は古い」という不一致が生じる。
- HEAD版の検索確定は`navController.navigate("search/${it.urlEncode()}")`で、singleTop指定がなかった。今回保持している共通検索改修で画面再利用を追加した際、ViewModelの寿命との整合を取れていない。

### 切り分け・検証の不足

- 同じ`nirvana`で結果画面のONLINE→LOCAL→ONLINEを操作すると、オンライン結果→ローカルの「見つかりませんでした」→オンライン結果へ正しく変わった。この切替そのものの障害は今回再現していない。証拠は`build/search-source-investigation-result-local`、`build/search-source-investigation-result-online-again`のXML／PNG。
- 既存のSearchSourceTestは検索欄への語の復元等、SharedSearchScopeTestは範囲・保存状態とFilterChipを中心に確認している。実際のNavigationとOnlineSearchViewModelを通す連続した異なる検索語の検索は対象外であり、以前の19件成功ではこの問題を検出できない。

### 修正方針（未実装）

- 再検索時は新しい検索語に対応した結果状態を用意する。従来どおり別の検索エントリを作る方法、または結果を明示的に検索語へ結び付ける方法で、singleTopによる古いViewModelの持越しを防ぐ。
- 検証は「Aを検索→同じ検索欄からBを確定→検索欄・実際の要求・結果がBで一致」を実Navigation経由で行う。途中の範囲切替・種別変更・遅いAの応答を含める。

今回変更したのは調査記録のみ。アプリ修正、テスト追加・実行、新しいAPKの生成は行っていない。検証用エミュレータは停止し、保存AVD・ユーザー実機を変更していない。

## 追加の操作仕様検討: ローカル検索・フォーカス・戻る

ユーザーは修正に併せて、ローカルの検索確定時に余計なロードを行わないこと（オンライン／ローカル切替時の再検索は必要）、検索欄外の操作でフォーカスを外すこと、アーティスト画面から検索画面へ戻れない動作の整理を希望し、自然な操作仕様を相談した。

### コード上の原因

- HEAD版の`SearchScreen.kt`はローカルの検索確定時に`focusManager.clearFocus(true)`だけを実行していた。
- 現行の共通`onSearch`はローカルでも`search/{query}`へ遷移する。入力中の一覧と確定後の一覧が別のナビゲーション所有者となり、LocalSearchViewModelの初期空結果・検索語再設定を通るため、結果の再作成が入る。
- LocalSearchScreenには明示的なローディング表示はないが、検索語を渡す際に300msのdebounceがある。DB検索自体はFlowによる非同期処理であり、画面を作り直さないこととDBを同期処理にすることは別である。
- ローカルのArtist／Album／Playlistを押すと、画面移動より先に`onDismiss()`を実行する。入力パネル側では検索非アクティブ化に接続され、検索語を消去し、裸の`search`ルートなら`navigateUp()`で検索エントリを除く。ホーム上の検索パネルの場合も、検索が独立した戻り先になっていない。
- 確定済みの結果画面側は`onDismiss = {}`であるため、同じ項目でも入力中と確定後で戻り先の扱いが異なる。

### 提案する操作（検討中）

- 検索画面の表示・検索条件・結果と、入力欄のフォーカス／キーボードを別の状態として扱う。検索開始時から同じ検索画面を戻り先にできる構成とする。
- ローカルは入力に応じて現在の結果領域を更新する。確定はフォーカスとキーボードを閉じるだけとし、別の結果画面を作り直さない。
- 余白・絞り込み・結果等を操作したらフォーカスを外してよい。検索語・結果は保持する。文字の入力途中に強制的にフォーカスを外すことはしない。
- アーティスト等を開く際は検索画面を履歴に残す。戻ると同じ語・範囲・種別・結果・スクロール位置へ戻り、キーボードは閉じたままとする。
- オンライン／ローカル切替時は検索語を保持して切替先の範囲で再検索する。入力欄や画面全体を閉じず結果領域を更新し、前の範囲の遅い応答を混ぜない。
- キーボード表示中の端末の戻るはまずキーボードを閉じる。検索画面自体を戻る操作で終了した場合は、検索を開始した元の画面へ戻る。
- オンラインの再検索は新しい語・範囲に検索状態を対応させる。再検索ごとに履歴へ結果画面を積み上げるだけの修正より、同一検索画面で条件変更を扱う設計の方が上記の操作と整合する。

これらはユーザーへ提示する操作案であり、検索UIの全仕様が確定した意味ではない。今回の追加確認はソースと履歴のみで、アプリの変更・テスト・ビルド・エミュレータ操作は行っていない。

## 合意後の実装（2026-09-07）

ユーザーの「はい，ではその方針で実装してください」により、上記の操作案を実装対象として確定した。以下はそれ以前の「未実装」「検討中」の記述を更新する。

- 検索開始時から `search` のナビゲーション項目を作り、その項目が入力・候補・ローカル結果・オンライン結果を所有する。同じ検索中の確定や対象切替では新しい画面を積まない。
- 入力は検索項目の `SearchSessionViewModel` に置き、携帯／タブレット配置変更でも維持する。フォーカスは結果の表示条件から分離した。
- ローカル検索の確定はフォーカスとキーボードを閉じるだけ。入力中と確定後の LocalSearchScreen/ViewModel を共用する。入力後の余分な300ms待機も取り除いた。DBは従来の非同期Flowを維持し、UIスレッドを同期DB処理で止めない。
- 結果・絞り込み・余白の操作でフォーカスを外す。詳細への移動で検索項目を削除しない。ナビゲーションで検索結果とリスト位置を保持し、候補と結果の表示切替にも別々の保存枠を用意した。候補の入力補完矢印は入力欄へフォーカスを戻す。
- OnlineSearchViewModel は新しい検索語で全カテゴリの旧キャッシュを破棄し、選択カテゴリは維持する。検索語・要求世代・表示中かどうか・カテゴリを照合し、古い結果と追加読み込みを採用しない。
- LOCAL→ONLINEの明示切替は現在の入力で再検索する。同じ語の詳細画面からの戻りではキャッシュを使用する。共通の検索対象保存・通信断時のローカル退避は維持する。
- SearchSourceTestの旧ルート文字列復元用3件は対象ヘルパー削除に合わせて削除。実際のOnlineSearchViewModelの10件と、製品SearchSession/SearchBarを実NavHostで扱う端末テスト3件で回帰を確認する。後者の結果データはfixtureであり、実際の通信・DB結果は別にアプリで確認する。

### 検証経過

- `:app:testCoreDebugUnitTest`（OnlineSearchViewModelTest / SearchSourceTest / ArtistDisplayTest）と coreDebug arm64 APK生成が成功。単体26件、失敗・除外0。最初のコンパイルではワイルドカードimportのR競合を修正した。
- 最終の端末検証用ビルドは上記テストと `:app:assembleCoreDebug :app:assembleCoreDebugAndroidTest`、50秒。`build/search-session-device-build.log`。
- 端末の最終結果とcoreReleaseの成果物情報は確認後に追記する。開始HEADは `4def7ed1`、既存改修を含む未コミット差分あり。
### 最終の確認結果

最終コードの関連テスト・coreDebug / coreRelease arm64 APK・端末テストAPK生成は成功。`build/search-session-final-build.log`、3分32秒。Gradleを同じ作業ディレクトリで並列実行していない。

| 確認 | 結果と証拠 |
| --- | --- |
| 実OnlineSearchViewModelの検索語更新・遅延応答・カテゴリ・キャッシュ・情報補完 | PASS、10件。SearchSource7件とArtistDisplay9件を合わせて単体26件、失敗・除外0、計0.383秒 |
| 製品SearchSession/SearchBar＋実NavHostの入力・戻り先・位置保持・物理Enter | PASS、4件。実DataStoreと検索対象コントロール4件と合わせ端末8件、31.532秒。`build/search-session-device-tests-complete.log` |
| 通常アプリの再検索 | Nirvanaの結果からQuruliを再検索し、くるり／GO BACK TO CHINA／別れ／三日月へ更新。`search-session-final-result-a.xml`、`search-session-final-result-b.xml`、最後の`search-session-final-online.xml`（build配下） |
| 同じ語でONLINE→LOCAL→ONLINE | quruliを保持し、ローカルの該当なしからオンラインのくるりの曲へ戻る。`search-session-final-local-switch.xml`、`search-session-final-online-return.xml` |
| ローカル入力とフォーカス | 検証用の保存曲5件で、入力したProbeに応じて先頭3曲を表示。アーティスト絞り込みの操作でfocused=falseを確認。確定時に結果を作り直さないこととIME／物理Enter経路は上記の製品入力部の端末テストで確認。Gboardの文字確定を経由するadbキー送出と、アプリへ届いたEnterは区別した |
| ローカル詳細からの戻り | Probe Artistを開き、戻るとProbe／LOCAL／アーティスト絞り込み／focused=falseを保持。`search-session-probe-artists.xml`、`search-session-probe-return.xml` |
| 実ローカル一覧のスクロール位置 | 「すべて」を122pxスクロール→Probe Artist→戻る。Probe Song 01の上端458px、Probe Artistの上端970pxが移動前後で一致。`search-session-scrolled-before-detail.xml`、`search-session-scrolled-after-detail.xml` |
| 携帯→タブレット→携帯の配置変更 | 720×1280 → 1280×1440 → 720×1280（density320）でquruliと閉じたフォーカスを保持。`search-session-tablet.xml`、`search-session-phone-return.xml` |
| coreReleaseの確認 | 起動・初期案内・検索開始・LOCAL切替・語の保持・該当なし表示を確認。キーボード表示中のBackは検索語Nirvanaを残し、次のBackで開始画面へ戻る。`search-session-release-*.xml/png` |
| 配布APKのオンライン通信 | 検証環境の証明書でSSLHandshakeException（Trust anchor for certification path not found）。releaseでの実通信確認はBLOCKED、debugの通常アプリで上記再検索を確認。TLS設定の変更は行っていない |
| クラッシュ・APK属性 | crashバッファに記録なし。coreReleaseの署名v2検証成功、metadataはarm64-v8aのみ、非debuggable |

途中の端末テストは外側の空のClick semanticsと、IME/ウィンドウを含むシステム座標送出で失敗した。製品の戻る判定を緩めず、テストホストのビューへタッチを配送するよう修正し、最終8件はすべて成功。物理EnterはBasicTextFieldより先に処理し、KeyUpで一度だけ確定する回帰テストも追加した。

- debug APK SHA-256: `F12B2D377ECCA1E30B537471C218B00454E8B4B5DF11763031E3BE91861BF1CE`
- 配布用: `app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`
- coreRelease SHA-256: `7725614DF98667B7519C33C82B33168087FB5736D4765DDFFBC14EA2A64A6741`

検証はread-onlyのPixel 9 / API35、arm64ネイティブ変換を利用。debugアプリのDBへProbe Artistと保存扱いのメタデータ5曲を投入した。実際の音声ファイル・再生を検証した意味ではなく、保存曲プレビューと検索・遷移用のfixtureである。ユーザーの実機・保存AVDは変更していない。アルバム／プレイリスト実データからの詳細復帰は今回の通常アプリ操作では個別に確認していないが、同じ検索画面を残すonDismiss接続を用いる。
検証用エミュレータを停止し、操作占有を終了した。差分の空白チェックはPASS。
