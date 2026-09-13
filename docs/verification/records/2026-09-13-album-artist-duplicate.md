# 特别版アルバムをまとめて保存したときの同名アーティスト重複の調査

2026-09-13。開始ソース `f7826b9e`。本件は原因調査の依頼であり、人物の同一性や保存方式を変更する本番コードの修正は行わない。同時進行のプレイヤーと歌詞調整は別課題。

## 現象と期待

- ユーザー報告：It Will Fit Me Just As Well のアルバム全体をライブラリに追加すると、中国語表記のアーティストが重複する。
- 対象はサウンドトラックではなく、特别版 `MPREb_NUdafp1DlA5`。同アルバムの本曲と伴奏曲を区別する。
- 期待：同じ人物が二つ並ぶように見える理由を、返却名・オンラインID・内部ID・曲との関連まで特定する。名前の区切りや一致だけを使って別人を自動統合しない。
- 未取得：今回のユーザー端末のDBそのもの。以下の新規データでの再現と、端末で過去に起きた全状態の一致は区別する。

## 現在の実応答

`build/album-artist-duplicate-20260913/` に匿名・地域JP・WEB_REMIXの取得結果を保存。Cookieやユーザーアカウントは使用していない。各 `.meta.json` に取得UTC時刻・要求・HTTP status・SHA-256を記録した。

| 対象 | 確認した内容 |
| --- | --- |
| アルバム `MPREb_NUdafp1DlA5`、ja/en | 2曲。本曲 `TSZhKssbW2g`、特别版伴奏 `xDWhuDRnevk`。各曲のアーティスト列は空。見出しのアーティストは一つの結合Runで、名前Runに人物IDなし |
| 2曲の get_queue、ja | 各曲で `翟锦彦`、独立した区切り、`8082Audio` の別々のRunを返した。翟锦彦のRunはIDなし、8082Audio自身のRunは `UChWKQRswWTLRXp98zmgHtdQ` |
| 両曲のクレジット、ja/en | 演奏欄にそれぞれ翟锦彦と8082Audioの独立したRunがある。各演奏者Runには人物IDなし。曲IDは応答の曲情報と一致 |
| `UChWKQRswWTLRXp98zmgHtdQ` のページ、ja | ページ名8082Audio。翟锦彦のIDとして流用する根拠はない |

アルバムの表題は `我也去当个天命人玩玩（《黑神话：悟空》最终预告） (特别版)`。曲名には `(It Will Fit Me Just As Well)` があり、本曲・伴奏の動画IDは別である。

**2026-09-05の応答との違い**：古い `target-queue.json` は結合された名前が一Runで、追加クレジットから個別名を確認していた。今回のget_queueは独立したRunと8082Audioの直接IDを返した。現在の `NextPage.fromPlaylistPanelVideoRenderer` はこれだけで `COMPLETE` として翟锦彦・8082Audioの2名を採用する。古いfixtureの解析経路を今回の唯一の原因とは扱わない。どちらの経路でも、最終的な「翟锦彦は個別名を確認済み・オンラインIDなし」という保存入力は同じになる。

## 原因となる現在の経路

1. `AlbumViewModel.load` は取得したアルバムの全曲を `DatabaseDao.upsert(AlbumPage)` でDBに保存する。ライブラリ追加の前から、アルバムを開くことで曲行は存在する。
2. アルバムの `SongListItem` は `rememberResolvedArtistMetadata(..., request = true)` を使い、空の曲クレジットを各曲のget_queueから補完する。
3. `ArtistCreditRepository.publish` / `ArtistIdentity.withStableRefs` はオンラインIDのない個別名に `stableId(videoId, name)` を割り当てる。ハッシュ材料に**曲ID**が入る。
4. `ArtistCreditDao.resolveCreditArtist` は内部参照または確認済みオンラインIDで既存の行を再利用する。同名だけを使ったオンラインアーティストの照合は行わない。
5. 翟锦彦は2曲で別の内部IDになる。一方、8082Audioは両曲で同じオンラインIDがあるため一つの行になる。
6. `AlbumMenu` の「すべてライブラリに追加」は各曲の `inLibrary` を更新する。人物を二重作成する専用処理ではなく、既にできた曲別の人物行をライブラリ一覧に同時に表示するきっかけになる。
7. 新しい `artist_identity` / `artist_display` は同じオンラインIDと手動のフォルダ紐付けをまとめるが、オンラインIDなしの非フォルダ由来の行は元の内部IDを維持する。この二つは表示でも別行になる。

| 人物表記 | 曲 | 生成・採用される内部ID | オンラインID |
| --- | --- | --- | --- |
| 翟锦彦 | `TSZhKssbW2g` | `LA56498d4e4b30c00f27a3c1001ec8067b` | なし |
| 翟锦彦 | `xDWhuDRnevk` | `LAa14505d06d383ca94876af698abc9b52` | なし |
| 8082Audio | 上記2曲 | `UChWKQRswWTLRXp98zmgHtdQ` | 同じID |

これはファイル由来のローカルアーティストではない。上の2件のLA行も `isLocal=false` であり、先のフォルダ紐付け機能の管理対象にはならない。LA接頭辞だけでフォルダ曲由来と判定してはいけない。

アルバム見出しの結合名は `RAW` で、現行保存処理はそれを一人分のartist行にしない。今回の経路は「アルバム見出しと曲で翟锦彦を二重登録」ではなく、「異なる曲のIDなし個別名をそれぞれartist行にする」ことで説明できる。

## 再現検証

`AlbumArtistDuplicateInvestigationTest.currentAlbumSaveSeparatesTheSameIdlessCreditByRecording` を追加した。**現状の不具合を望ましい仕様として固定する回帰テストではない**。通常実行ではスキップし、`investigateAlbumArtistDuplicate=true` を指定した調査時だけ実行する。

入力は `app/src/androidTest/assets/album-artist-duplicate/special-album.json`。今回のget_queueの各 `queueDatas[n].content.playlistPanelVideoRenderer` からモデルに必要なフィールドを抜粋し、追跡フィールドだけを除いた。名前Run・ID・順序は変更していない。アルバム見出しの表題・生クレジットは同日のアルバム応答から抜粋。元応答のSHA-256と取得時刻をfixtureにも保存した。

テストは実際の `NextPage` パーサー → `insert(AlbumPage)` → `applyArtistCredit` → 全曲の `toggleInLibrary` → `savedArtistsByCreateDateAsc` を通し、次を検査する。

- 同名の翟锦彦が上表の別IDで2行、それぞれ1曲。8082Audioは1行で2曲。
- 空のアルバム曲クレジットを再保存しても、取得済み人物の関係を失わない。
- 削除・再追加を3回繰り返しても、同じ2曲から4行目以降が際限なく増えるわけではない。
- DBを閉じて開き直しても同じ3行と曲との対応を維持する。

### 最終ソースの実行結果

対象は `f7826b9e` に今回の未コミット差分を加えたソース。人物登録処理の本番コードは変更していない。

| 確認 | 結果 | 証拠 |
| --- | --- | --- |
| 調査用Androidテスト | **PASS**、1件、失敗0件、テスト本体0.492秒 | `build/album-artist-duplicate-android.log`。上記の再保存・3回の削除追加・DB再開を含む |
| 新規アプリの実通信からの再現 | **PASS（不具合の再現成功）** | 特别版アルバムで取得した2曲の名前を確認し、メニューの「すべてライブラリに追加」を実行。ライブラリに翟锦彦1曲が2行、8082Audio2曲が1行 |
| 強制終了後の再起動 | **PASS（不具合の維持を確認）** | 再起動後も同じ2件の翟锦彦と1件の8082Audioを表示 |
| 実画面操作後のDB照合 | **PASS** | `build/album-artist-duplicate-20260913/ui-db/relations.json`。上表の2個のLA IDと共有オンラインIDが各曲に紐付き、両曲の `inLibrary` が保存されている |

実画面操作はrootエージェントが担当し、本記録では保存された画面XML・ログ・DB抽出結果も照合した。ユーザーからの動作報告の引用ではない。検証環境はAPI 35のエミュレータ、ログインなしの新規アプリデータ。アルバムの入口は実際のプレイリスト `OLAK5uy_mgidIzxZdcEIBKWY_zyNgecctoSlHxY6s`。取得済みの合成クレジットをDBに注入して画面を作ったものではない。

使用APK：`app/build/outputs/apk/core/debug/OuterTune-0.10.2-b1-core-arm64-v8a-debug-71.apk`、`coreDebug`、`arm64-v8a`、`com.dd3boh.outertune.debug`、0.10.2-b1 (71)。SHA-256は `ec0769e8394b1c7ccb18eff472cac81c1a2dec7afd977978e2a8b23fa252a885`。この実通信の再現はdebug版での結果であり、release版やユーザーの実機で同じ操作を直接確認したという意味ではない。

スクリーンショットとXML：`build/diagnostics/player-adjustments-20260913/` の `debug-real-album-resolved`、`debug-album-menu`、`debug-library-after-album-add`、`debug-duplicate-after-restart`（それぞれ `.png` / `.xml`）。

**原因調査は完了。不具合は未修正。** 以下の保存仕様を判断してから修正する。

## 見落としていた観点と修正方針の判断材料

既存テストは「同じ曲の再保存」「後からオンラインIDを取得したときの統合」「同名で異なるオンラインIDを勝手に統合しない」を検証していた。**一つのアルバムに同じIDなし名が複数曲に現れた場合の一覧件数**が合格条件に含まれていなかった。2026-09-07の[旧実装検証記録](2026-09-07-artist-implementation.md)にも、アルバムを開いた後のDBが曲2件・Artist3件になったという観察はあったが、その3件の人物同一性と一覧での重複を検証していなかった。

重複をなくすために曲IDをハッシュから単純に取り除くと、無関係の同名アーティストまで共有してしまう。アルバムが同じ・表記が同じという条件だけでも、オンラインで確認した人物IDと同等の証明にはならない。また、一人の行にまとめる処理と、その人のオンラインページに紐付ける処理は分けて扱う必要がある。

以降の修正では少なくとも「確認済みオンラインIDのない個別名を、独立した人物一覧の行としてどう扱うか」を決める必要がある。候補は、ユーザーが同一人物として手動でまとめる仕組みをIDなしのオンライン曲由来の行にも拡張すること、または人物を特定できるまで曲上のクレジットとして保持して一覧登録と分けること。後者は既存のIDなし人物ページを利用する動作を変えるため、今回の調査だけで変更しない。
