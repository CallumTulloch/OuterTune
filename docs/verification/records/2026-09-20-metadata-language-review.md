# 2026-09-20：名称表示の残件・ちらつきと仕様見直し

## ユーザーの指摘と進行変更

- 同じアルバム内にカタカナ表示が残る。エミュレータで自分で確認すること。
- 英語と日本語が一瞬ずつ切り替わることがある。
- その調査中に「漏れや不具合が多い。仕様を改めて決めるべきではないか。今回直せば完全に穴がふさがる認識か」と指摘された。
- 完全解消とは判断できないと回答し、追加実装・ビルドを停止。相談用の [仕様見直し案](../../metadata-language-behavior-proposal.md) を作成した。承認済みの追加仕様としては扱わない。

## 調査で確認できたこと

- 前回配布版のNevermind全13曲確認では、先頭等3曲の改善にとどまり、Breed等に日本語が残っていた。[前回の範囲と画面](2026-09-19-album-song-name-language.md)。これをアルバム全体の解消としない。
- コード上、名称参照の表示評価が全DBの原題候補集合の指紋に依存している。別作品の原題が追加されても既存評価が一時的に採用されなくなり、別workerの再評価後に復帰する。英語→日本語→英語を発生させ得る具体的な公開順序である。今回の停止時点では、遅延を制御した時系列テストや全画面の動画検証は未実施。
- Nevermindのcanonical playlist `OLAK5uy_lYnxawfGdkGePjdFhIYaS6LjP-Md6UYf0` に対するMusic browseとMusic nextを照合。13曲すべてが同一`playlistSetVideoId`で一意に対応し、前者の音源IDと後者の通常一覧のIDを名前・順番の推測なしに対応づけられた。
- 例：Breedは `ox_BG6sLPq8` と `J6EDW5WFb2M`、Lithiumは `_oWUgfpGi0M` と `pkcJEvMcnEg`。nextのpanel/current endpoint/各曲endpointは要求playlist IDと一致した。browseはplaylist IDのechoがないため要求VL IDに結び付いた応答として扱う。原題の照合・言語判定は別途必要。
- raw応答はgit無視対象 `build/diagnostics/album-song-language-20260919/provider-track-identity-probe/actual-playlist-{browse,next}.{raw,summary}.json`。個々の取得方式をユーザーの必須仕様にしない。

## 停止時点

- HEAD `7e48d375`。前回の未コミット改修は維持。新たなcommit/pushは行っていない。
- プレイリスト項目IDのAPI・モデルは未実装。アプリ側参照helperの草案はcompile対象外の `build/diagnostics/album-name-stability-20260920/proposals/PlaylistSongReference.kt.draft` へ保管した。
- 今回の表示安定化の書きかけは同proposals配下の `album-duplicate-code-flicker-draft/` へバイト一致コピー・今回分のpatch・SHA256とともに保管した。今回分の変更だけを逆適用し、既存の未コミット改修を保持して直前のソースへ戻した。今回の草案を完成・PASSと扱わず、前回のAPKを今回の追加修正済みとも扱わない。
- 今回の調査ではGradle・追加APK生成・インストールをしていない。実機は操作していない。エミュレータのアプリを停止し、検証用の通信無効化を戻して操作制限を解除。
- 次は初回取得時と再取得時の表示規則、原題確認未完了の扱い、全曲・全画面・時系列の合格条件を決める。その後に取得と判定の状態遷移を実装し、決定的なテストと実画面で確認する。
