# 2026-09-19: 英語原表記ONでもアルバム収録曲がカタカナになる

## 依頼・範囲

- ユーザー報告: 曲検索は英語なのにアルバムではカタカナ。例はNirvana「Smells Like Teen Spirit」。英語原表記設定はON。
- 対象はアルバム収録曲。任意の海外作品での再現確認を許可され、既存DBの保持も不要と明示された。
- 調査のみ。本体・テストコード・DB schemaの変更、新規ビルドは行っていない。以前の課題の未コミット差分を維持。
- 仕様の所在への質問に対応し、過去の合意と現在の実装を[仕様整理](../../metadata-language-spec.md)に集約。新たな表示条件の合意・実装ではない。

## 結論と限界

**初期化したdebugアプリでも、英語原表記ONでNevermindとAbbey Roadのアルバム収録曲がカタカナになることを確認。古いDBやMVの混入を必要としない。**

今回の環境では、曲詳細のgetQueueが空応答となり、Art Trackの種別を得られず、Main原題取得・英語判定へ進めない。ブックマーク後には英語アルバムAPIから同じ曲IDの英語名を取得できたが、これは名称候補であり原題の証拠にはならない。ONでも `ORIGINAL_UNCONFIRMED` により日本語が選ばれる。

ただし匿名の検証環境では対象のアルバム曲がすべて再生制限表示になり、Nirvanaの曲フィルター検索は0件。このためユーザーの「検索では英語」という一対の画面差を同条件で再現したとは扱わない。実機はADB未接続で、現在の曲ID・判定・取得状態は未確認。今回の取得制限と、前課題で修正した再生履歴によるDBデッドロックも別の現象。

## 新規DBによる実測

- 対象ソース: HEAD `14e73317` と既存未コミット差分。
- 既存coreDebug / arm64-v8a、0.10.2-b1 (71)。インストール済みAPKと既存ビルド出力のSHA-256一致: `e0bad32d6ab5bd648d0e1ae81e0cef21beba38e9dd43788eb743792ebfaf13ef`。
- Pixel_9_API_35 / emulator-5556、Android 15、x86_64上のarm64実行対応。
- debugを初期化し、Welcomeをスキップ。UIから英語原表記ON・コンテンツ日本語を設定。checked=trueをXMLで確認。初回は国US、追加で国JPへ変更し同じ結果を確認。
- 検索のアルバム結果から開き、ブックマーク前後の画面・DBを採取。提供バックアップは投入していない。

| 対象 | Nevermind | Abbey Road (Super Deluxe Edition) |
| --- | --- | --- |
| album ID | `MPREb_jPOYfjGgApr` | `MPREb_tQfaWH32ovE` |
| 正式曲数 | 13 | 40 |
| 先頭の曲ID | `ljUtuoFt-8c` | `wqaKHHxQFZc` |
| 日本語名 | スメルズ・ライク・ティーン・スピリット | カム・トゥゲザー (2019 Mix) |
| 保存後の英語名 | Smells Like Teen Spirit | Come Together (2019 Mix) |
| 日英名称候補のある曲数 | 各13 | 各40 |
| 原題und / 判定 | 0 / 0 | 0 / 0 |
| JPの曲詳細取得 | 日英26状態すべてEMPTY | 日英80状態すべてEMPTY |
| アルバム先頭の表示 | カタカナ | カタカナ |

NevermindはUSでの日英26状態もEMPTY。日本語候補のsourceは `album`、英語候補は `album-original-context`。英語候補のoriginEvidenceJsonはnull。

- [Nevermind](../images/2026-09-19-album-song-language/nevermind-ja.png): 英語名取得後もカタカナ。
- [Abbey Road](../images/2026-09-19-album-song-language/abbey-ja.png): 別作品の初回表示でもカタカナ。後続DB採取で40曲の英語候補を確認。
- 設定・画面XML、DBコピー・抽出JSON、APKハッシュ: 無視対象 `build/diagnostics/album-song-language-20260919/`。最終DBのintegrity_checkはok。
- 実機は操作していない。検証後はdebugを停止してエミュレータを終了し、操作制限を解除。

## コードとの照合

1. `AlbumViewModel.load` はコンテンツ言語のアルバムを取得し、曲とIDを保存。`YouTube.album` のobserverが各曲の名前補完を予約する。
2. `MetadataNameRepository.saveMusicResult` は英語getQueue応答で `MUSIC_VIDEO_TYPE_ATV` を確認した場合に原題取得を開始。今回のEMPTYでは進まない。
3. `captureAlbumOriginals` は保存対象アルバムの英語曲目を名称候補として保存。英語名が存在するだけでは配信原題の証拠を作らない。
4. `latestOriginalRows` / `originalCandidate` はund + `art-track-original:` の有効な原題を分類。通常の英語アルバム候補は対象外。
5. `OriginalNamePolicy.select` は英語原題判定が無ければ、ONでも `ORIGINAL_UNCONFIRMED` として設定言語を採用。今回のDB状態から表示を説明できる。
6. EMPTYの再取得間隔は24時間。ブックマーク後の再予約も `due` で省略され、新しい英語名が入っても原題確認は復旧しない。

曲検索のSongItem.displayTitleとアルバムのSongEntity.displayTitleは同じIDの共通表示マップを読む。ただしマップにIDが無い場合は各画面の元タイトルへ戻るため、「同IDなら必ず同じ表示」とまでは言えない。

実測していない別経路として、同IDでも補完前に元タイトルが違う場合の表示差、原題再取得で判定なしJSONが保存され再分類まで一時失効する経路も確認。今回の53曲は原題自体が未取得であり、モデルの誤判定とは区別する。

## 過去DBによる説明の訂正

初回は提供DBの同名2 IDの評価差をユーザー画面の原因として説明したが、現画面IDを未確認のまま断定しており撤回した。

- 旧DBには `ljUtuoFt-8c` と `hTWKbfoikeg` のindex=0があり、前者だけ英語原題判定がある。前のアルバム重複課題の資料であり、現在の画面の証明ではない。
- 前回修復DBの名称関連3テーブルは元DBと全行一致。前回release画像・XMLでは先頭が英語。全体は8曲に英語判定、4曲は名称未取得でカタカナ、末曲は元タイトル自体が英語だった。
- 今回の新規DBでは `ljUtuoFt-8c` 自体がカタカナになった。MVの選択は原因の必要条件ではない。

## 継続改修の判断材料（未実装）

- 原題確認を曲詳細取得の成功だけに依存させず、取得不可でも正規アルバム収録曲の確認を復旧できる経路を検討する。
- 一時的な空応答で24時間止まる扱い、アルバムを明示的に開いた場合の再試行、原題が不変な再取得での判定保持を検討する。
- 英語候補があるだけで一律に英語採用する変更は「正式な原表記が英語なら」の意味を変えるため、取得経路の修復と区別する。
- 新規/保存済みDB、同一曲ID、取得成功/空応答/復旧、ON/OFF、検索とアルバムの往復を合格確認に含める。今回の調査だけで問題解消とは扱わない。
