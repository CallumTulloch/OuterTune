# 曲なび・ライブラリなびの条件ラベル

## 現象と期待動作

日本語UIでは条件チップが日本語になる。指定の7項目（アルバム、アーティスト、プレイリスト、いいね、ダウンロード、ライブラリ、フォルダ）を、既存の英語UIと同じ Albums / Artists / Playlists / Liked / DL / Library / Folders にする。

3点メニューは日本語を維持し、「いいね済みのみ」を「いいね済み」に短縮する。曲名・人物名などのコンテンツ言語、英語表記利用設定、フィルターの意味や保存値は変更しない。他のUI言語の翻訳も維持する。

## 根拠と範囲

- 対象ソース：`cb347a1f` からの差分。直前の言語・再生修正はコミット済み。
- チップは `LibrarySongsScreen` と `LibraryScreen` で文言を組み立てる。後者の共通表示は分類選択後の各画面でも使用される。
- 共用資源 `albums` 等を変更するとナビ・設定などへ波及するため、7個のチップ専用英語資源を用意する。`navigationFilterLabel` が実際のUI設定の先頭言語がjaの場合だけ専用資源を選択し、それ以外は既存資源を参照する。アプリ全体のlocaleやコンテンツ言語を操作しない。
- `LibraryLikedFilterMenu` は既存 `filter_liked_only` を使い続け、日本語値だけを短縮する。

## 合格条件

1. 日本語UIの曲なびで Liked / Library / DL / Folders が表示され、選択・解除できる。
2. 日本語UIのライブラリなびで Albums / Artists / Playlists が表示され、分類を切り替えた後も DL / Library / Folders の英語表記が維持される。
3. アルバム・アーティストの3点メニューは日本語で「いいね済み」と表示され、チェックを切り替えられる。
4. 英語UIの既存表示を維持し、他言語では元の翻訳へ解決される。日本語の画面見出し・下部ナビなどへ英語化が波及しない。
5. 実際のcore-release / arm64-v8aで文字の収まりを確認する。軽微な文言変更のため実装をなぞる単体テストは追加しない。

## 結果

途中案の観測：defaultを既存資源へのalias、values-jaだけ英語にすると、第1希望fr・第2希望jaの実際のrelease画面で、UIはフランス語なのにチップが Liked / Library / DL / Folders となった。`build/navigation-labels-ui/fr-ja-songs.xml` とpngが証拠。日本語の第2希望へのfallbackが専用資源を選んだため、上記の明示的なUI言語判定へ修正した。この途中APKは配布しない。

## 最終確認（ルート担当が直接実行）

最終ソースは `cb347a1f` + 本改修。以下は最終APKへ更新後に行った確認であり、途中案の結果を転記していない。

- `:app:assembleCoreRelease --offline --console=plain`：PASS、2分3秒。ログ `build/navigation-labels-release-final.log`。途中案のビルドは4分13秒で、配布対象ではない。
- Pixel_9_API_35、Android 15、1080×2424。arm64 core-releaseを実際にインストールして直接操作した。言語はAndroidのアプリ言語設定で `fr,ja` → `ja,fr` → `en,ja` と切り替えた。
- 日本語UIの曲なび：Liked / Library / DL / Folders を表示し、4条件すべての選択・解除を確認。UI階層上のcheckedもfalse→true→false。
- 日本語UIのライブラリなび：Albums / Artists / Playlists の表示と各分類への切替を確認。分類選択後も DL / Library / Folders を維持。プレイリスト分類で3条件すべての選択・解除を確認。
- アルバム・アーティスト双方の3点メニュー：親「ライブラリを絞り込み」、子「いいね済み」を実際の文字列として確認。チェックの選択・解除と、再度開いた際のchecked=trueを確認。「いいね済みのみ」「Liked only」への置換漏れはない。
- `fr,ja`：Favoris / Bibliothèque / Téléchargés / Dossiers / Albums / Artistes / Playlists の既存訳を確認。途中案の英語化は解消。
- `en,ja`：曲なび・ライブラリなび・プレイリスト分類の7条件が既存英語表記を維持。
- 日本語の検索・追加日時・下部ナビ・件数・自動再生リスト名は従来の日本語を維持。日本語UIの条件ボタンとメニューの文字の収まりをスクリーンショットでも確認。
- `git diff --check`：PASS。最終crash bufferは空（`build/navigation-labels-final-crash.log`）。

各操作のUI階層は `build/navigation-labels-ui/final-*.xml`。特に `final-ja-song-folders-on/off.xml`、`final-ja-playlist-folders-on/off.xml` は各条件を順次選択・解除した状態、`final-ja-album-menu-off/on.xml` と `final-ja-artist-menu-off/on.xml` はメニューの状態を保存している。

画面証拠：[曲なび](../images/2026-09-12-navigation-labels/songs-ja.png)、[ライブラリなび](../images/2026-09-12-navigation-labels/library-ja.png)、[3点メニュー](../images/2026-09-12-navigation-labels/liked-menu-ja.png)、[フランス語優先・日本語第2希望](../images/2026-09-12-navigation-labels/songs-fr-ja.png)。

軽微な文言・表示選択の変更として、単体テストの追加と従来の全試験の再実行は行っていない。既存NavigationUiTestは同じ資源から期待値を取得し言語の誤りを見逃すため、今回の根拠には使わず、上記の実画面・実文字列で確認した。空の検証用ライブラリで選択状態と表示を確認しており、実アカウントの絞り込み結果、音声再生、別のAndroidバージョンは今回の検証範囲外。フィルター処理・保存設定・DB・コンテンツ言語のコード変更なし。

## 配布物

- `build/distributions/OuterTune-navigation-labels-20260912-arm64.apk`
- core-release / arm64-v8a、version 71 / 0.10.2-b1、9,073,778 bytes。
- SHA-256：`1ad3561b3114b9ef0a1b92ab90e325f9ed81d57dbcc0b7f39d5d0b972040976f`
- `apksigner verify --verbose`：PASS、v2署名、signer 1。ZIP内のnative libraryはarm64-v8aのみ。
- 最終APK生成後は文書と検証画像だけを更新。read-only検証エミュレータを終了し、こちらの操作終了を連絡済み。
