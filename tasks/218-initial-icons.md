最初に tasks/RULES.md を読む。

# Task218 初回の画面別アイコン準備

あなたは実装担当。ユーザー承認済み。独立worktreeでPanelScreenの初回準備だけを改善。時計/歌詞/AppCatalogState内部/版/依存/Git/ADB操作は変更禁止。

- 現在のrequired（全設定分）を、現在表示する画面で使うforegroundと残りのbackgroundへ分ける。初回preparedはapps.readyかつforegroundのinfo完了だけを待ち、非表示の遅い取得に待たされない。表示対象の初回アイコンは従来どおり揃えてから出す。既存panelScreen_waitsForInitialInfo_andReflectsAsyncCompletionのassertを維持する。
- 正しい表示対象は実際のHomeWideContent/PanelContent/MediaCard/Dockのコードから導く。homeWideはセット左右・panelApps.take(MAX_PANEL_APPS)・再生/idle・実際のdock・設定。通常PanelView.AppsはpanelApps.take(MAX_PANEL_APPS)・再生/idle・dock・設定、PanelView.Setsはセット左右・dock・設定。homeAppsやdockModeで使われない固定/頻出、表示外セットなどを無条件foregroundへ入れない。現在はページ内/スクロール内の絞込みまで行わず、その画面の表示候補全体をforegroundにする（ページ切替の一瞬の空表示を避ける）。nowが存在する際にidleも必要かは既存表示経路を確認する。
- catalog.requestはまずforegroundだけ、prepared後に最低1フレーム待ってから既存required全体の残りをrequest。任意秒数の遅延を追加しない。既存catalogの世代/失敗fallback/アンインストール/設定変更/アプリ一覧時全件取得を維持。config/now/view/catalog変更で最新foregroundをrequestし、古い予約が新しい画面を塞がない。
- preparedは従来どおりcatalog単位、一度表示後は背景取得や設定変更で画面全体を消さない。apps.ready待ちは維持。タイル位置/順番/launch先/installed判定を変えない。必要なら純粋なinternal InitialIconPlan helperを作る。AppInfoを新しい独自placeholderへ変更しない。
- 新規InitialIconPreparationTestで本物のPanelScreenを使い、AppCatalogSourceのCompletableDeferredで非表示セットのinfoを止めても表示対象完了後に時計/対象アイコンが表示されること、対象infoは完了まで従来どおり待つこと、残りも後で要求されること、Apps/Sets/homeWideでの対象差、設定/画面変更、missingとクリック先維持を検証。AppGraph等のtest fixture setup/cleanupは既存最新例に倣い、永続データ/コルーチンを残さない。非表示infoの読取りをキャンセルできたという主張はしない。

許可ファイルはallowの1実装と新規helper/test。既存テスト変更は禁止。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.PanelScreenTest' --tests 'com.gt8d.launcher.ui.panel.InitialIconPreparationTest' --tests 'com.gt8d.launcher.apps.AppCatalogStateTest'`

成功後は再実行なし。日本語で差分・試験件数/失敗・決定事項・未確認を報告。実機の起動時間は司令塔が別途比較する。
