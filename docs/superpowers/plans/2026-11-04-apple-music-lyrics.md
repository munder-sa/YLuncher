# Apple Music 歌詞取得の実装計画

2026-11-04。状態: ユーザーが実装開始を了承、Task 175から順に着手。ユーザー了承済みの手動入力方式を使い、実機の単語同期表示までを段階的に確認する。[設計](../specs/2026-11-04-apple-music-lyrics-design.md)を判断の基準にする。

計画作成時の基準は `perf-artwork-copy` / `2b28074`。既存の性能改善と2.2.1-beta1を引き継ぎ、Codex再起動でGit操作の制限が解消した後、司令塔が `feat-apple-lyrics` を作成した。

## 進め方

アプリとテストの変更は `codex-run tasks/NNN-xxx.md` へ委譲する。司令塔は各段階の差分・ログ・テスト結果を確認する。同じ作業場所では直列とし、全工程を一度に実装担当へ渡さない。

| 順番 | 内容 | 完了条件 |
|---|---|---|
| 175 完了 | 検証スクリプトの構造診断を追加 | 追加20ケース成功。認証付き実測でJSON経路・TTML構造を記録 |
| 176 完了 | Apple 用HTTP・公開Bearer・TTML取得 | 対象14件+client/parser32件成功 |
| 177 完了 | 手動入力と接続状態 | Session9件+設定31件成功 |
| 178 完了 | キャッシュとRepository統合 | 新規18件+既存44件成功 |
| 179 実機導入・認証済み | ベータと実機確認 | 2.3.0-beta1 / 41を本体/中継へ導入、版/権限/UI確認済み。地域jpで接続成功、実歌詞は未確認 |
| 後続 | 日常利用・正式版 | 保存/ログイン方式、版番号、公開範囲を実測後に決める |

175〜178は実装と対象検証が完了。[179の指示書](../../../tasks/179-apple-lyrics-beta.md)による2.3.0-beta1 / 41のAPKはビルド・版/署名検証後、実機の本体/中継へ導入済み。ユーザー依頼のCodex再起動でADBのforbidden判定が解消した。権限維持と未接続UIを確認後、利用者が実機へトークンを入力し、地域jpで接続成功。司令塔も接続済み表示と入力欄の消去を確認した。歌詞API・Kotlin解析・単語同期表示の確認はこれから。以下の候補・コマンドは各指示書作成時の計画であり、実行許可は個別Task/allowを参照する。

## Task 175 取得構造を確定する

[指示書](../../../tasks/175-apple-lyrics-contract-probe.md)と[allow](../../../tasks/175-apple-lyrics-contract-probe.allow)を使用する。変更は既存 `scripts/apple-lyrics-probe.py` のみ。認証や通信方式を増やさず、本文を含まない構造診断と合成データの自己確認を追加する。

実装担当は `python3 scripts/apple-lyrics-probe.py --self-test` のみ実行する。現在のWSLには `python` がなく `python3` があるため、Task 175とallowをこの表記へ揃えた。司令塔は差分・出力非漏洩をレビューした後、ユーザーがローカルの非表示ダイアログへ入力できる時に既存の対話モードを実行する。チャットへのトークン入力や端末内部探索は行わない。

この段階で記録するのは JSON の構造経路、値の型分類、TTML の namespace・role・時刻形式・重なりなどの件数。Kotlinパーサーとの実データ互換性は179で確認する。実測構造が取れなければ176のJSONデコーダーを推測実装しない。

## Task 176 取得処理を分離する

候補ファイルは `media/lyrics/AppleLyricsTransport.kt`、`AppleWebTokenProvider.kt`、`AppleLyricsClient.kt` と対応する3テスト。既存 `TtmlParser.kt` は読み取りのみを基本とする。変更が必要と実測で判明した場合は別Taskとして根拠と期待値を先に定義する。

- トークン・接続先・時計・通信を差替え可能にし、テストで実通信を行わない。
- 固定ホスト/固定経路、公開/認証付き要求の分離、リダイレクト拒否、サイズ/時間上限、接続切断を検証する。
- Bearer の期限、公開資産変更、更新の多重化防止、401/403/404/429/5xx、壊れたJSON/XML、非対応TTMLを検証する。
- 戻り値は成功・対象外・認証再確認・取得不可・非対応・不正応答・通信失敗・待機を区別し、HTTPコードだけで欠曲と認証切れを断定しない。
- 合成データで開始時刻・UTF-16位置・背景除外を確認し、実歌詞のサンプルは保存しない。

将来の対象テストコマンド:

```sh
/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsTransportTest' --tests 'com.gt8d.launcher.media.lyrics.AppleWebTokenProviderTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsClientTest' --tests 'com.gt8d.launcher.media.lyrics.TtmlParserTest'
```

## Task 177 設定と認証状態を追加する

候補は `media/lyrics/AppleMusicSession.kt`、`settings/AppleMusicLyricsScreen.kt`、`SettingsNav.kt`、`SettingsScreen.kt`、`AppGraph.kt` と対応テスト。設定の既存actions構造とテストを読み、具体的な追加位置を指示書で固定する。

- 状態は未設定、確認中、利用可能、再確認が必要を区別。利用可能状態は検証済み storefront と認証世代を持つ。秘密値を公開する接続状態・自動ログ・保存用のモデルに載せない。入力欄の一時状態だけは秘密値を扱うため、画面を離れたら破棄する。
- マスク入力と接続確認・解除。入力の検証、処理の取り消し、画面破棄、差替え、失敗後の再入力を検証する。
- 歌詞オフでも利用者による接続確認は可能。自動通信と明示操作の区別をテストする。
- 再起動後の再入力をUIで説明する。保存機能やバックグラウンド更新は追加しない。

対象テストは追加したSession/Screenと変更したSettingsNav/SettingsScreenに限定し、指示書・allowに `:app:testDebugUnitTest --tests '完全修飾名'` を全件記載する。

## Task 178 取得元とキャッシュを統合する

候補は `media/lyrics/AppleLyricsCache.kt`、`LyricsRepository.kt`、`AppGraph.kt` と対応テスト。既存 `LyricsState` の画面契約・LRCLIB / AMLL のディスク形式を維持する。Apple が無効なときに既存テストの期待を変えず通る構成にする。

- 有効な再生元・ID・認証・歌詞オンのときだけ Apple を試す。
- AMLL / LRCLIB キャッシュを暫定表示し、Apple成功後に格上げする。既存キャッシュによる早期returnでAppleが飛ばされないことを確認する。
- Apple失敗・待機では既存結果を保持し、既存取得を妨げない。Apple 404/認証失敗をLRCLIBキャッシュへ書かない。
- 再生位置の連続更新で再取得ゼロ、曲/ID変更で取得、曲送り後の旧結果破棄、歌詞オフでキャンセル、解除/認証差替えによるキャッシュ破棄を確認する。
- 同じ曲を表示したまま接続が成立した場合も取得できるよう、認証世代を観測キーに加える。秘密値はキーにしない。
- 初期ベータのメモリキャッシュ上限・TTL・再認証時の破棄を偽時計で検証する。

将来の対象テストコマンド:

```sh
/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsCacheTest' --tests 'com.gt8d.launcher.media.lyrics.LyricsRepositoryTest' --tests 'com.gt8d.launcher.media.lyrics.LyricsCacheTest' --tests 'com.gt8d.launcher.media.lyrics.AmllCacheTest' --tests 'com.gt8d.launcher.media.LyricsIdentityTest'
```

既存テストを無効化・削除・緩和して新しい順序へ合わせない。仕様が衝突した場合は変更理由を整理し、司令塔へ返す。

## Task 179 ベータで実取得と表示を確かめる

版ファイル2つと必要な一時診断だけを具体的な指示書で許可する。現在の40より大きいversionCode、両モジュールで同じ `<次の版>-betaN` とする。ビルドは `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`。実装担当はadbを使わず、司令塔が実機確認を行う。

| 場面 | 判定 |
|---|---|
| Lemonと別のカタログ曲 | Appleから取得し、Kotlinパーサー採用、再生中の音源と主旋律が一致 |
| 単語同期なし・取得不能・認証再確認 | AMLL/LRCLIBで表示継続。設定の状態が区別される |
| ホームと分割パネル | 同じ取得状態を共有し、表示と操作に退行がない |
| 曲送り・停止・シーク・ID不明・別アプリ | 旧曲を表示しない。不要なApple通信をしない |
| オフ・解除・差替え・プロセス再作成 | 通信停止、秘密情報/Appleキャッシュの破棄、再入力の挙動が設計どおり |
| 初回・再取得・キャッシュ | HTTP要求数、取得時間、解析時間、メモリを分けて記録。描画中の毎フレーム処理を増やさない |

実機にない地域・再現できないエラーは偽通信テストの結果と区別する。ネットワーク障害を模擬するために接続中のADB経路を無計画に切断しない。実データでパーサー非対応なら診断を根拠に対象形式を別Taskで扱い、成功扱いにしない。

ベータ合格後に日常利用用の認証保持を決める。正式版を作る場合はその範囲を確定し、必要な全テストを1回、版・成果物を照合してからmain/タグ/公開へ進む。今回の計画作成は公開の許可を含まない。

## 今回の確認

`tasks/RULES.md`、`AGENTS.md`、`docs/HANDOFF.md`、Task 170と検証スクリプト、現行のNowPlaying・LyricsRepository・TtmlParser・各キャッシュ・AppGraph・設定の入口を読んだ。Gitの状態は追跡済み変更なし、既存の未追跡4ファイルがある状態から開始した。ビルド・テスト・実通信の再検証は実行していない。計画作成の検証は文書差分・リンク・空白に限定する。
