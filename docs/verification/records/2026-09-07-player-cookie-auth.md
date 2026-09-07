# ログイン済みの再生要求に使うCookie認証

## 課題・合格条件

- ユーザー報告：Nirvana「Lithium」で `Source error (2000): unexpected x0` と
  「この動画は一部のユーザには適さない可能性があります」が表示される。OuterTuneにはログイン済み。
- 期待：保存したアカウントの認証を再生要求へ正しく渡し、そのアカウントで利用できる曲を再生する。
- 今回は認証ヘッダーの不備を修正する。各Cookieに対応する署名、Cookie欠落時の扱い、
  実際のHTTP要求への反映を自動テストし、coreRelease / arm64-v8aを生成する。
- 着手時HEAD `64de9042`。作業ツリーに未コミット差分なし。アルバムタイルの修正は別コミット済み。

## 確認済みの根拠・未確認事項

- 既存のNevermindメタデータにあるLithiumは `_oWUgfpGi0M`。
  別の検索結果には `553VogDv5DI` がある。ユーザーがエラーを見た版の共有URLは未確認。
- 認証を送らない読み取り用player要求で、`_oWUgfpGi0M` のIOS応答は
  `LOGIN_REQUIRED`、報告と同種の注意文、ログインを求めるconfirmDialog、streamingDataなし。
  WEB_REMIX / ANDROID_VRでも年齢確認のためのログイン要求を確認した。
  確認フラグ `contentCheckOk` / `racyCheckOk` は既存コード・実際の要求ともtrue。
  アプリが独自に追加したチェックではなく、配信元が返した応答である。
- 読み取り用応答の記録：`build/lithium-player-status.json`。実アカウントのCookie・署名は収集していない。
  別IDの応答がOKでも、同じ録音として置換したり、再生成功と扱ったりしない。
- 認証処理は `SAPISID` だけから作った同じ署名を、`SAPISIDHASH`、`SAPISID1PHASH`、
  `SAPISID3PHASH` の全てに使用していた。後二者はそれぞれ `__Secure-1PAPISID`、
  `__Secure-3PAPISID` を使う必要があり、Cookieがない方式の署名を送るのも誤り。
- 参照：認証処理の一次資料 [yt-dlpの実装](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py)
  の `_get_sid_cookies` / `_get_sid_authorization_header`。署名をCookieごとに生成し、
  SAPISIDがない場合の3P Cookieによる補完を確認。以下のKotlin実装・テストはこの仕様に基づく。
- ユーザーの保存Cookieが実際に異なる値か、期限内か、対象の正確なID・アカウントで
  修正後に配信元が再生を許可するかは未確認。認証実装の不備と、報告された曲の原因確定を区別する。
- MV候補をエミュレータで開く操作は自動承認レビューに拒否された（詳細理由なし）。
  同操作の迂回再試行は行わず、上記の読み取り用応答とソースの調査に切り替えた。
  エミュレータは一旦終了し、利用可能と通知済み。

## 実装・検証

- `cookieAuthorization` でCookieごとに署名を生成する。欠落・空のCookieは省略し、
  SAPISIDがない場合だけ3P CookieをSAPISIDHASHにも使う。
- `InnerTube.ytClient` からこの関数を使用する。ログイン対応クライアントだけにCookieと認証を送る既存条件を維持。
  再生候補の順序・確認フラグ・配信元の再生可否判定・対象の動画IDは変更していない。DB変更なし。
- PASS：`:innertube:test --tests com.zionhuang.innertube.CookieAuthTest --tests com.zionhuang.innertube.PlayerAuthenticationTest --max-workers=2`。
  9件成功（署名6件、実HTTP要求3件）、失敗・skipなし。ビルドを含め11秒、テスト本体合計約0.84秒。
  ログ `build/player-cookie-auth-tests.log`。
- 署名テスト：異なる3種類の値、SAPISIDのみ、3Pによる欠落・空値の補完、1Pのみ、SIDなし・空値、originと時刻の反映。
  期待値はPythonのSHA-1でも独立計算した固定値。
- HTTPテスト：127.0.0.1の一時サーバーへ実際のInnerTube/Ktor要求を送り、WEB_REMIX・ANDROID・TVの2方式で
  Cookieと署名の一致、アカウント指定、既存確認フラグを検証。
  VR・IOSへアカウント情報が渡らないこと、Cookie差し替え・ログアウト後に以前の署名が残らないことも確認。
  全て合成した認証データを使い、このテストから外部通信は行わない。
- PASS：`:app:assembleCoreRelease --max-workers=2`、2分46秒。release必須lint成功。
  ログ `build/player-cookie-auth-release-build.log`。既存のaboutlibraries Compose mapping収集警告あり。
  上記のテスト・APK生成後に製品コードの変更なし。
- PASS：生成した同一APKのv2署名検証、ABIはarm64-v8aのみ、versionName `0.10.2-b1` / versionCode `71`。
  APK：`app/build/outputs/apk/core/release/OuterTune-0.10.2-b1-core-arm64-v8a-release-71.apk`。
  SHA-256：`6C0F54AD166BDFA23F010116178E4AFD781E03DB4CBCA7293DF4D379C54C56C3`。
- PASS：Pixel_9_API_35 / emulator-5556（x86_64＋arm64変換）へ同一coreReleaseを新規インストール。
  コールド起動1.34秒、初期設定をスキップし、ホーム→ライブラリ→設定→ライブラリ設定へ移動。
  タイルサイズ初期値Bigを含め正常表示。クラッシュバッファに出力なし。
  証跡：`build/player-cookie-auth-release-verification/` のstartup、home、library、settings、library-settingsのPNG/XML。
  新規インストールなのでログイン・端末メディアへの権限付与・オンライン再生はこの確認に含まれない。
- エミュレータは事前通知の上、`-read-only -no-snapshot -no-window -no-audio` で使用。
  確認後終了し、元のAVD保存状態は更新していない。
- BLOCKED：ユーザーのアカウント・対象の正確な共有URLを使うLithiumの実再生。
  自動テスト成功は認証処理の修正確認であり、ユーザー報告の再生不良が解消したという判定ではない。
