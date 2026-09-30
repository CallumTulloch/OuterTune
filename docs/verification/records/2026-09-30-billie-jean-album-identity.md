# 2026-09-30: Billie Jean の再生元とアルバム収録曲のID不一致

**現在の状態:** 根拠のある楽曲版への対応付けを実装し、取得済み応答を使った通常UIで、4:55の再生→プレイヤーからThriller→同曲の再生中表示→保存・DL・同じ行からの再生までPASS。配布用APKは生成済み。実機の今回の新APKでの動作と実通信は未確認。以下の前半は修正前の調査、末尾が最終検証。

## 現象と範囲

- 実機検証中のユーザー報告: 検索で再生した Billie Jean のプレイヤーでタイトルを選択し、アルバムを開くと、音声は続くがアルバム内の同曲に再生中表示が付かない。再生元の表示時間は **4:55**。時間の違いも見えるとの報告。
- 期待: プレイヤーから所属アルバムへ移動した際、実際に再生している音源と収録曲の対応が正しいこと。曲名だけで別音源を同一視しない。
- 対象APK: coreRelease / arm64-v8a / versionCode 71。インストール済みAPKのSHA-256 `dd63e1fe7d481e7cdaf2409a6d2dcc6b6a5fcf6f2a2d40087dcfe1838f9a767f` が今回の配布物と一致。
- 調査時のソースは `528e4e7f` と未コミットのメモリ対策差分。今回、アプリのコード・実機の再生状態・インストール状態は変更していない。
- 実機の受動監視を継続。読取時にはユーザーが別曲へ進んでいたため、問題発生時の実機内部IDを直接取得できていない。以下のIDは公開APIで得た同条件の候補と応答であり、実機DBの取得結果ではない。

## 公開APIで確認した応答

既存の `build/diagnostics/1989-display-20260927/raw_probe.py` の関数をインポートして再利用。WEB_REMIX、認証なし、gl=JP / hl=en。実機の認証・言語条件と同一とは扱わない。生応答は今回の無視対象診断フォルダー `build/diagnostics/physical-workflow-20260930-151020/billie-jean-api/` に保存した。

| 経路 | Billie Jean のID | 時間 | 種別 |
| --- | --- | --- | --- |
| `michael jackson` の曲検索 | `Kr4EQDVETuA` | 4:55 | ATV（楽曲版） |
| `billie jean` の曲検索内の Thriller 版 | `Kr4EQDVETuA` | 4:55 | ATV |
| 上記IDの get_queue | `Kr4EQDVETuA` | 4:55 | ATV |
| 検索・get_queue が示すアルバム `MPREb_dqWTncCjkSp` の曲棚 | `Zi_XLOBDo_Y` | 棚の時間表示は4:55 | OMV（MV版） |
| 同じアルバムのプレイリストを browse | `Kr4EQDVETuA` | 4:55 | 楽曲版ID |
| 同じプレイリストを next | `Zi_XLOBDo_Y` | 4:56 | OMV |
| アルバム曲棚側のIDを get_queue | `Zi_XLOBDo_Y` | 4:56 | OMV |

- アルバム名・リンク先は Thriller で一致。アルバム側の canonical playlist は `OLAK5uy_l1U925dsiDi2DqlG-KCbODG6BaibpxbQE`。
- アルバムの曲棚・プレイリストbrowse・nextの3経路で `playlistSetVideoId=5CFC2BF6F37BEF73` が一致。同じ項目に異なる動画IDが返ることを確認した。曲名や一覧位置による推測ではない。
- アルバム曲棚はMV版IDと楽曲版の4:55表記を同時に返しており、後続の再生情報の4:56と異なる。これだけでユーザーが見た遷移先時間まで直接確認済みとはしない。

## コード上の経路

- `ui/menu/PlayerAlbumNavigation.kt`: オンライン曲ではキューの `MediaMetadata.album.id` を使う。
- `ui/menu/PlayerMenu.kt`: 得られたIDの `album/<id>` へ移動する。
- `innertube/YouTube.kt` の `album`: アルバムに曲棚があればそれを採用する。`albumSongs` によるプレイリストbrowseは曲棚がない場合だけ使用する。
- `innertube/pages/AlbumPage.kt` の `getSong`: 曲棚の `playlistItemData.videoId` 等をSongItemのIDとして読み込む。今回の曲棚ではMV版IDとなる。
- `ui/screens/AlbumScreen.kt`: `song.id == mediaMetadata?.id` の完全一致で再生中表示を判定する。そのため上記の楽曲版を再生中でもMV版IDの行には表示が付かない。
- 既存の `PlaylistSongReferenceParser` は同じプレイリスト項目のbrowse/nextの対応を検証するが、現在は名称の根拠に用い、再生IDの置換には使用しない。名称参照を無条件に音源同一性として流用しない。

## 判定と修正時の合格条件

- **OBSERVED:** 4:55の楽曲版からリンクされるアルバムでMV版IDが返り、現行の再生中表示判定に一致しない経路を確認。実機報告と整合する具体的な原因候補。
- **未修正:** この調査では曲名比較による表示上の回避、音源IDの置換、APK更新は行っていない。
- 修正時は、同じアルバム・プレイリスト項目に属する楽曲版を根拠付きで選び、曲数・順序・利用不可項目を保持することを検証する。別バージョン、MV、ライブ版を曲名だけで統合しない。
- 検索の4:55を再生 → プレイヤーからThriller → 対応する楽曲行の再生中表示 → 同じ行から再生した際のID・長さ → 保存・DL・再起動後の維持、を一連で確認する。今回、この修正後検証は未実施。
- 発熱は同時に確認された別の未解決事項。15:18台の実機ログでSKIN 39.7℃と温度制御ステータス1を観測。ID不一致を発熱の原因とは断定しない。

## 追加改修

- 上記は修正前の調査記録。ユーザーの改修指示後、`YouTube.album` に楽曲版の解決処理を追加した。実機監視はユーザーの指示により15:32に終了しており、再開していない。
- アルバムのOMV曲棚に対し、同じcanonical playlistのbrowse応答を取得する。全項目のplaylistSetVideoId、playlist/albumの所属、endpointとの整合、全continuationの完了を確かめ、直接OMV→ATVが確認できた場合だけ音源ID・時間・endpoint・creditをまとめて楽曲版へ置き換える。曲名や表示位置は対応根拠に使わない。
- 曲順・曲数・利用不可状態を保つ。不明な対応を部分置換せず、重複IDで別項目を潰さない。通信失敗や不正な続きページは失敗とする。正常なheader-only応答などで対応が未確定なら新規アルバムでは元の棚を表示し、保存済みの曲一覧は上書きしない。
- 対応未確定の既存アルバムは曲一覧を保持して再試行を表示する。利用不可状態は、取得した棚と保存済み一覧でIDが完全一致した曲だけ更新する。再起動直後でも同じIDの制限を復元し、MVと楽曲版の間では制限を転記しない。この場合、header/localeの更新は完全な応答を得るまで保留する。DB schemaは変更しない。
- 捕捉応答を匿名化したfixtureによる12件のresolver試験、保存済み一覧保護の2件のDAO試験、利用不可状態の3件のJVM試験、実際のRoom再読込み・保存/DL対応を検査するdevice試験、通常UIからの再生→アルバム→保存/DLを検証するopt-in fixtureを追加。実行結果は後述する。

## 最終検証と成果物

- **PASS:** innertubeの関連46件（新規resolver12件を含む）と利用不可状態のJVM3件。既存のKing Gnuの21曲/13曲利用不可を保持し、不明・重複・別アルバム・矛盾したendpoint・続きページ失敗では誤置換しない。
- **PASS:** `AlbumTrackMembershipTest` と `AlbumSourceIdentityDeviceTest.capturedAlbumKeepsPlaybackLibraryAndDownloadIdentityAcrossReopenAndFailedRefresh`。本番の`YouTube.album`、`YouTubeQueue`、Roomを通して9曲の楽曲版IDとBillie Jeanの295秒、保存・お気に入り・プレイリスト・DL対応を確認。DBを閉じて再読込みし、通信失敗・header-only応答後も同じ一覧を維持。DL対応の単独DB試験は実ダウンロードとは数えない。
- **PASS:** opt-in通常UI試験 `normalPlayerToAlbumUiUsesCanonicalAudioAndDownload`、163.46秒。既存のUI取得/操作用`ui.py`を再利用。検索結果のMichael Jackson / 4:55を再生し、展開したプレイヤーのタイトルからThrillerへ移動。6曲目Billie Jeanに強調表示と再生アニメーションが付き、行のIDは`Kr4EQDVETuA`、表示は4:55。元のMV IDは収録一覧にない。
- 同じ行のメニューでライブラリへ追加、Download完了、Playを実行。MediaControllerの現在ID、9曲のDB順序、保存状態、完成したMedia3 downloadのrequest ID/customCacheKeyと全音源cacheがすべて楽曲版IDで一致。MV版のdownloadがないこともassert。`billie-ui-instrumentation.txt`末尾は`OK (1 test)`、`billie-evidence.txt`に`UI_OK`。
- 画像は `build/diagnostics/workflow-repair-20260930/billie-album-playing.png`、`billie-downloaded.png`、`billie-album-replayed.png`。音源はテスト用295秒の無音WAV、画像は固定画像、Music応答は取得済みJSON。実曲の転送品質や今回の実通信成功を主張しない。HTTP差替え・fixtureはandroidTestだけで、配布APKへ含めていない。
- **PASS:** fixture終了後にdebugプロセスを停止・再起動し、保存一覧の1曲、同じID、4:55、DL表示を確認（`billie-restarted-library.*`）。再起動後の再生はfixtureなしの通信経路へ戻り、既知のTLS制約があるため未確認。DB再読込みとUI上の保存維持は、通信後の再生成功と区別する。
- **PASS:** 配布用coreRelease/arm64 APKの署名、ABI、fixture非混入、エミュレータのインストール済みbase.apkとのハッシュ一致。releaseで起動→既存の保存一覧→プロセス再起動→同じ保存一覧を確認。releaseの実通信は既知の証明書制約に従って未確認。
- 成果物: `build/distributions/OuterTune-0.10.2-b1-core-arm64-v8a-release-workflow-album-20260930.apk`、versionCode71、9,202,061 bytes。SHA-256: `abb309c3265037bd4edbcacf86c3fae2a6f59a1761f1851d74a24370b99ad560`。
- 発熱・メモリ・通知の測定結果は[連続操作の記録](2026-09-30-emulator-workflow-memory.md)を参照。実機にはインストールせず、停止済みの監視も再開していない。
