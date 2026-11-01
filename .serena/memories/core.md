# YLuncher core
GT8D(車載 Android 15 端末, minSdk/targetSdk 35)向けカスタムランチャー。「画面セット」(左右の分割: アプリ+パネル)を開く・切り替えるのが中心機能。
Tech: `mem:tech_stack`。コマンド: `mem:suggested_commands`。規約・作業の流れ: `mem:conventions`。完了時の検証: `mem:task_completion`。

## モジュール
- `:app` (`com.gt8d.launcher`) 本体。
- `:panelhost` (`com.gt8d.launcher.panel`) 中継アプリ。`PanelHostActivity` 1つだけ。純正ランチャーの分割(E)の右側に渡され、自分のタスク内で本体の `ui.panel.PanelActivity` を開く(署名権限 `com.gt8d.launcher.permission.OPEN_PANEL`)。本体を直接右に渡すとホームのタスクが入る/SystemUI が落ちるため分けている。インストールは本体→中継の順、同じ署名鍵。両モジュールの versionCode/Name は揃える。

## app のパッケージ (app/src/main/java/com/gt8d/launcher)
- `AppGraph` 手動 DI のシングルトン(init で配線, *ForTest のリセット口あり)。`LauncherApp` = Application。
- `config/` 設定モデル(`Model.kt`: LauncherConfig, ScreenSet, Slot{App,Panel}, Side, SplitRatio, LinkConfig…, CONFIG_VERSION)、`ConfigCodec`(kotlinx.serialization, 版の移行)、`ConfigRepository`(DataStore)。設定の形を変えるときは版と移行を必ず扱う。
- `sets/` `SetRunner`(セットを開く状態機械: 分割の待ち、失敗時のフォールバック、キャンセル、リンク用の open)、`SetNavigator`、`PanelSwapSaver`。
- `split/` 分割の起動: `SplitLauncher`, `VendorBridge`(純正ランチャー E 経由)、`SplitModeSelector`, `PanelPlacement`, `ActivityLauncher`。
- `link/` 外部リンク(VIEW)の受け口 `LinkEntryActivity` → `LinkRouter` で「左のアプリ | 外部ブラウザ」の分割へ。`BrowserChooser`。
- `foreground/` 前面アプリの監視(UsageStats + 時計ずれ補正 `OwnResumeClock`)。
- `edge/` 画面端のジェスチャ用前景サービス `EdgeStripService`、オーバーレイ窓、`BootReceiver`。`curtain/` セット切替中の幕。`safety/` SafetyGuard(失敗の検出と回復)。`boot/` 起動時のセット。`media/` NotificationListener による再生中情報。`notice/` 通知オーバーレイ。`apps/` アプリ一覧・頻度・非表示・分割リダイレクト(`SplitRedirect`: manifest meta-data `com.gt8d.launcher.split.*`)。`settings/` 設定画面(Compose, 画面ごとにファイル)。`diag/` DiagLog。`time/`。
- `ui/` Compose: `home/`(HomeActivity=HOME, 時計・セットカード・ミニプレイヤー・アプリの段)、`panel/`(PanelActivity, ドック, アイコングリッド, MediaCard)、`layout/`, `apps/`, `gesture/`, `theme/`。
- `app/src/debug/` debug 専用: `debug/DebugPanelReceiver`(場面テストが使う)、`spike/`(初期の実験)。リリース版には無い。

## 不変条件・落とし穴
- 分割(E)は右のパッケージの既存タスクをパッケージ名で選ぶ。冷えた E は約3.3〜3.6秒、途中で呼び直すと分割が解ける → 形跡があれば呼び直さない。
- 仕切りのドラッグでは Activity は作り直されない。分割に入るときは作り直される。
- 再インストールで通知リスナーの許可が外れる。
- 仕様・計画: docs/superpowers/specs/*.md, docs/superpowers/plans/*.md(版ごと: 初版, v1.5 左パネル, v1.6 UX)。導入手順 docs/install-v*.md。
