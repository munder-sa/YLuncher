最初に `tasks/RULES.md` を読み、従う。

# Task 177b Apple Music歌詞の接続設定

176a/b/c・177aは対象テスト成功。初期ベータの手動トークン入力画面とAppGraphの共有Sessionを作る。実装担当は司令塔の権限を引き継がない。

## 変更してよいファイル

- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsNav.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsScreen.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsActivity.kt`
- `app/src/test/java/com/gt8d/launcher/settings/SettingsActivityTest.kt` (新規テストの追加だけ)

## 作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/settings/AppleMusicLyricsScreen.kt`
- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicLyricsScreenTest.kt`

Session/client/transport/provider/Repository・既存テストの本文/期待値・依存・版・manifestは変更しない。実通信なし。歌詞取得への配線は178で行う。

## 配線

1. AppGraphに `val appleMusic: AppleMusicSession by lazy` を追加する。内部にAppleLyricsUrlConnectionTransport、AppleWebTokenProvider、AppleLyricsClientをそれぞれ1インスタンス生成し、Sessionへclient::connectとclient::fetchを渡す。構築で通信しない。lyricsと無関係なAppGraph処理は変えない。既存LyricsRepositoryへの引数はまだ変えない。
2. `SettingsRoute.AppleMusicLyrics` を追加。SettingsHost末尾に省略可能な `appleMusic: AppleMusicSession? = null` を追加し、AppGraphへ直接フォールバックしない。SettingsActivityからAppGraph.appleMusicを渡す。
3. 設定ルートの「画面」と「システム」の間に「音楽」sectionを追加し、「Apple Music の歌詞」行(tag `settings_item_apple_music_lyrics`)から遷移する。既存detailItemsとtake(5)/drop(5)の分類・既存項目の相対順は維持。新画面のトップバーは「Apple Music の歌詞」。戻る動作は既存navで処理。
4. SettingsActivityの既存open_route分岐に `apple_music_lyrics` を追加。exportedや外部アクセス権限を変えない。Sessionがnullのテスト用Hostで新画面に入った場合は固定文「接続設定を利用できません」を表示し、通信しない。

## 画面

- `@Composable fun AppleMusicLyricsScreen(session: AppleMusicSession, modifier: Modifier = Modifier)`。既存Palette/Dims/SettingsSection/SettingsButtonを使う。Host側がスクロールを持つため、画面内部で縦スクロールを二重にしない。
- state.collectAsStateで状態を表示(tag `apple_music_status`)。「未接続」「接続を確認中」「接続済み」「接続の再確認が必要」を区別し、接続済みでは検証済み地域コードも表示する。
- 画面に「Apple Music Web のトークンを入力して接続を確認します。」「アプリの終了・再起動後は再入力が必要です。」「取得できない曲は、ほかの歌詞サービスで表示します。」を示す。歌詞オン/オフを変更せず、接続確認だけでは歌詞を取得しない。
- OutlinedTextField等の標準入力(tag `apple_music_token`)、ラベル「Web用トークン」、PasswordVisualTransformationとPasswordキーボード、singleLine。秘密値を含む独自semantics/補助文/ログは追加しない。
- 入力は `remember(session)` のみ、rememberSaveable/Bundle/設定/ファイルを使わない。クリップボードの自動読取・ブラウザー・WebView・自動ログインなし。入力は画面退出時に破棄する。
- 「接続確認」ボタン(tag `apple_music_connect`)は入力が空白のみ、またはCHECKING中なら無効。入力値をローカルに取り、欄を即座に空にしてからrememberCoroutineScopeでsession.connectを呼ぶ。接続中は入力を無効にしてよい。例外/秘密値をUIに出さない。
- 「解除」ボタン(tag `apple_music_disconnect`)は未接続かつ入力空のとき無効。それ以外で入力を消し、実行中の画面の接続Jobを取り消してsession.disconnectを呼ぶ。完了済み接続は画面退出で解除しない。確認中の退出はJobキャンセルでUNCONFIGUREDへ戻す。
- 失敗の補助文はreason別の固定文とする。AUTH_REQUIRED「接続を確認できませんでした。トークンを確認して再入力してください。」、NETWORK「通信できませんでした。接続を確認してやり直してください。」、TIMEOUT「確認に時間がかかりました。もう一度お試しください。」、WAIT「しばらく待ってから接続を確認してください。」、INVALID「入力内容または応答を確認できませんでした。」、ほか「接続を確認できませんでした。時間をおいてやり直してください。」。Retry-Afterが正なら秒数を添えてよいがタイマー/自動再試行は不要。
- 狭いパネルでもボタンが画面からはみ出さないよう縦配置する。文字の長い説明は折り返す。

## テスト

既存のCompose/Robolectric登録方法を参照し、偽connect/fetchを持つ実Sessionを注入する。ネットワークや実トークンを使わない。

AppleMusicLyricsScreenTestに以下の名前で追加する。

- `rootOpensAppleMusicSettingsAndReturns`: Hostの新section/行/タイトル、戻るとルート、画面を開いただけでは要求0。
- `masksInputAndClearsItBeforeConnecting`: Password semantics/空入力無効、submit直後入力が空、CHECKING/CONNECTEDと地域の表示、connect1回・lyrics fetch0。
- `disconnectClearsConnectionAndInput`: 接続済みから解除、確認中から解除、再接続可能、入力残留なし。
- `leavingCancelsPendingCheckAndDropsInput`: 戻る/画面破棄で未送信入力と確認中Jobを破棄、遅い完了で接続が復活しない。
- `completedConnectionSurvivesNavigation`: 成功後に戻って再入場しても接続維持、欄は空、余分なconnect/fetchなし。
- `recreationDoesNotRestoreInput`: StateRestorationTester等でsave/restoreしても入力なし。rememberSaveableへ変更しない。
- `showsFailuresWithoutSecretsAndAllowsRetry`: 固定の各失敗文、再入力して再接続、秘密値・例外文が診断ログ/表示文へ混ざらない。標準パスワード入力自体のEditableTextを秘密ログと誤認しない。
- `narrowPaneKeepsControlsAccessible`: w516dpのHost上で説明・入力・接続/解除に到達できる。

SettingsActivityTestへ `appleMusicRouteOpensConnectionSettings` を追加し、open_route=apple_music_lyricsでタイトル/入力/再入力説明が出ることを確認する。既存6テストは変更しない。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.settings.AppleMusicLyricsScreenTest' --tests 'com.gt8d.launcher.settings.SettingsScreenTest' --tests 'com.gt8d.launcher.settings.SettingsNavTest' --tests 'com.gt8d.launcher.settings.SettingsActivityTest'`

変更後1回、失敗修正時のみ再実行。177a/176の成功済みテストは再実行しない。実通信・assemble・adb・Git操作を行わない。追加/既存の件数、結果XML、未確認事項と決めたことを日本語で報告する。
