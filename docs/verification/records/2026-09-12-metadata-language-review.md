# コンテンツ言語と英語表記の再点検

- 現象：日本語が適切な対象が英語になる。英語表記利用をOFFにしても戻らない場合がある（ユーザー報告、個別の曲IDは未特定）。
- 期待：英語と指定言語の取得名をID別に保持し、OFFでは指定言語、ONでも裏付けと矛盾しない英語原表記だけを使用する。未取得の名前を推測しない。表示変更で再生ID・元データを変更しない。
- 開始時：HEAD `7bc8bfa5847652d9cd6e7883de562574be36147e`、既存の未コミット変更あり。既存差分を保持する。
- 範囲：名称候補の選択、原表記判定、補完取得と設定反映。再生復旧は別記録 `2026-09-12-playback-recovery.md`。
- 合格条件：日英の切替、未取得時の元表記維持、競合する原表記の保守的判定、指定言語の補完、表示切替で再生同一性維持を回帰検証する。単体成功と端末確認を区別する。

## 調査中の確認事項

- 共通表示マップが指定言語未取得時にも英語fallbackを公開するため、呼出元に日本語名があっても英語で上書きする。
- 詳細補完で取得した曲に含まれる人物・アルバム名は保存されるが、当該対象の他言語・詳細名の取得はその場では予約されず、定期更新まで待つことがある。
- 原表記の大文字小文字別判定の片方だけで英語を採用する経路、およびUNKNOWNの競合名を表示判断から除外する経路を確認。

## 検証

対象は開始時HEAD＋既存差分＋今回差分。DB schema変更なし。

| 確認 | 結果 |
| --- | --- |
| 単体・検証用ビルド | PASS。`:app:testCoreDebugUnitTest :innertube:test :app:assembleCoreDebugAndroidTest :app:assembleCoreDebug --offline`、1分8秒。app 284件成功（本体2.632秒）、innertube 70件中57件成功・既存13件skip（本体1.700秒）。`build/language-playback-review-final-2.log` |
| Roomと実Repository | PASS。MetadataNames DB7件、ArtistCredit DB6件、AlbumMetadata Repository6件、実Repository言語切替1件。新規MetadataNameRepository3件もPASS（0.610秒）。詳細補完から日英の人物・アルバムを取得し、未保存対象をライブラリへ増やさない、初回取得中の言語切替、設定言語通信失敗時に英語で上書きしないことを確認。 |
| 実プレイヤーとキャッシュ | PASS。キャッシュ4件、実ExoPlayerでの日本語→英語→日本語変更と8秒へのシーク1件。言語変更後も位置進行・再生ID/URI/key保持、エラーなし。生成WAVと実キャッシュを使い、YouTube実通信は置換。 |
| 端末検証総数 | PASS 28件。初回26件中25件成功＋追加クラス初期化エラー1件（JUnitが非void関数を拒否）。テスト宣言のみ修正後に当該クラス3件を再実行して成功。成功済み25件の本番コードは以後未変更。`build/language-playback-device-tests.log`（5.423秒）、`build/language-repository-device-final.log`（0.610秒）。 |
| 端末 | Pixel_9_API_35 / emulator-5556、x86_64＋arm64変換。arm64-v8aのcore-debug APK。一時read-only/no-snapshot状態で実行。 |

途中のビルド失敗：追加テストのAlbumItem引数不足、DownloadUtilの不要resolver、OnlinePlaylistの型推論循環を修正。最終成功は上表のソースに対する結果。初回通常sandboxではGradle wrapperのネットワーク利用が制限され、既存ホスト環境でoffline実行へ切替。エミュレータの古いsnapshot認証状態は、snapshotを読まない一時起動で解消。

配布版：PASS。`:app:assembleCoreDebugAndroidTest :app:assembleCoreRelease --offline`、3分15秒。`build/language-playback-release.log`。core-release / arm64-v8aのみ、署名検証成功（`build/language-playback-release-signature.log`）。

- 配布用コピー：`build/distributions/OuterTune-language-playback-20260912-arm64.apk`、9,070,974 bytes。
- SHA-256：`001fd1cc4645ad1b0a9dda23745ccb016a1f64f2bbc1f603facc20ebc37761f3`。
- 配布版を一時端末へ新規インストールしMainActivityのcold start成功、Welcome画面表示とcrashバッファに記録なしを確認。`build/language-playback-release-ui.xml`、`build/language-playback-release.png`、`build/language-playback-crash.log`。
- 配布版のYouTube実通信を通した言語変更・再生は未確認。debugで実Repositoryと実ExoPlayerを使う検証に合格したことと区別する。ユーザーが観測した曲の実機条件も未特定。
- 終了時に一時エミュレータを停止し、元のAVD状態には書き戻さない。commit/pushは行っていない。

## 実装

- 指定言語の候補がない場合は共通表示マップへ英語fallbackを公開せず、各画面が持つ元表記を維持。手動名と裏付けのある英語原表記の優先は保持。
- 詳細で発見した人物・アルバムにも英語と指定言語、正式見出しの補完を予約。未保存アルバムの全曲展開は行わない。初回取得中の設定変更では、まだ名称キャッシュにない保存済み対象も再予約。
- 強い名称候補とその観測時刻を一組で保持。後着の弱い付属表記が古い正式候補の時刻を更新して、最新の日本語候補へ勝つ不具合を修正。DB構造の変更なし。
- 大文字小文字別の双方が英語を支持する条件へ修正。日本語・明確な非英語を含むアルバムから曖昧な名称へ英語判定を波及させず、言語不明の別原表記も競合確認に含める。Art Trackの説明クレジットと原題の整合も確認。
- 判定器v2、Main原情報取得キーv3、名称取得キーnames-v2により旧判定・旧解析・旧優先度で保持した結果を再評価／再取得する。別名とユーザーの保存データは保持。
- localeの変更通知を追加し、表示中クレジット、アーティスト、アルバム、ホーム、一覧、プレイリストの取得へ反映。旧言語の応答を破棄し、続きの要求を元ページの言語へ固定。プレイリストの取得多重化・失敗時の無限ループ・循環トークンを防ぐ。

## 境界と残る確認

- 利用者が実際に遭遇した曲ID・再生条件は未特定。今回の自動再現条件で確認した原因と区別する。
- 配信元が日本語要求にも英語だけを返す場合や、通信に失敗した場合は日本語を作らず取得済みの表記を使う。
- 原言語の自動推定は完全ではない。国籍・歌唱言語・Latin文字だけでは英語確定としない。名前順の並べ替えは既存の元名称基準のまま。
- 再生検証は別記録。端末の新規データや自動fixtureと、利用者の既存データでの再現を混同しない。

後続のコンテンツ言語決定・通知の統一、追加で検出した不具合、最終APKと検証結果は[コンテンツ言語の網羅記録](2026-09-12-content-locale-coverage.md)を参照。
