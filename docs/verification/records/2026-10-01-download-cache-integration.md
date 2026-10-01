# 2026-10-01: Media3ダウンロード・中断再開・同時再生

## 範囲・合格条件

- `REGRESSION-DL-001` の実DownloadManager/index/cacheと再生の結合を確認する。着手HEAD `17425de2`、別作業の音声403対策を保全した作業ツリー。
- YouTube実通信を使わず、OkHttp interceptorのWAV/HTTP Range/応答Bodyを実Media3 DataSource/Downloaderで読み、indexと二段cacheを検証する。
- 完了時に全バイト一致・全span・index件数を確認し、URL再解決/上流open/read/HTTP呼出し0でオフライン再読込みできること。

## テスト・結果

- `DownloadCacheLifecycleTest` 3件:
  - 応答Bodyの途中失敗→FAILEDをindexへ保存→manager再作成→手動retry→同じ音源全体を保存。
  - 転送中のstopReason停止→reader中断/partial span確定→resume→削除。download cacheだけを消し、独立player cacheを保持。
  - 空cacheから実ExoPlayerとDLを同時開始。2readerゲートで同時性を保証し、再生/seek/DL完了後に完全オフライン読込。
- `PASS`: 最終テスト3件、2.971秒、失敗/スキップ0。`final-download.txt`。
- 途中の2回はテストfixtureの生成時失敗: CacheDataSourceはcachedでもupstreamを生成するためFactoryでthrowできない。またByteArrayDataSourceは空配列を拒否する。禁止判定をopen/read/resolverへ移し、非空のダミー配列を使って解消。`download-integration.txt` / `download-final.txt` に失敗を保全し、途中をPASS件数へ加算しない。
- cleanupはゲート開放→player/manager release→開いたBodyが0になるのを待機→専用cache/index DB/HTTP資源を閉じる。global preferences/auth/DownloadUtil singletonは変更しない。
- 実行: 専用read-only emulator-5556 / ADB5038、class単独instrumentation。ソースhash/APK hash/個別statusは `build/diagnostics/fork-regression-phase2-20261001/`。
- 後続の復元APIだけの変更では、再生/ダウンロード/テスト入力の変更0をhashで照合し、この端末結果を再利用した。元APK hashは `apk-before-api24-compat.json`、最終版の差分は `source-final.json` に保存。

## 残る範囲

- 製品DownloadUtilからのDB印・画面・外部音源削除の一連の経路は、本テストで確認した範囲に含めない。既存util単体回帰とは区別する。
- 復元UIの初回fixtureで、音源/indexのないDL印が起動再照合により解除されることを観測。曲/保存/リンク/歌詞/キューの削除を示す結果ではない。通常backupはDBと設定のみで、音源・index/cacheは含まない。
- 実YouTube転送/認証、実機負荷、通信断の機種別挙動は未確認。既知TLS制約を再調査しない。
- 今回は追加テストと記録だけをコミットし、製品ダウンロードコードと既存の未コミット403対策は変更しない。
