# 調査: 純正ランチャーの分割機能に、外から呼べる入口があるか(2026-10-28)

方法: 読み取りのみ。`dumpsys package`、APK の取り出し(`pm path` → `adb pull`、作業はリポジトリ外)、SDK の `dexdump -d` による逆アセンブル(jadx は未導入。GitHub からの取得がサンドボックスで不可のため)。端末の設定変更・試し撃ちはしていない。

## 判定: **入口なし(不合格)**

分割を外部から開ける exported な Activity / Receiver / Service と extras は見つからなかった。手順4(試し撃ち)は、入口候補が無いため行っていない。

## 画面とショートカットの正体

| 対象 | 正体 |
|---|---|
| 既定のホーム | `com.android.launcher3/.uioverrides.QuickstepLauncher`(`/system/app/SdLauncher3/SdLauncher3.apk`)。AOSP Launcher3 にメーカー(suding)の独自ホーム画面を組み込んだもの |
| ペアのショートカット | AOSP 標準のアプリペア(`com.android.launcher3.apppairs.AppPairIcon`、`model.data.AppPairInfo`)。起動は Launcher3 内部から SystemUI の Shell(Recents 担当専用)へ |
| ホーム左の大きな枠(地図・YouTube などが表示される) | `com.suding.launcher.home.space.TaskViewManager`。SystemUI の TaskView(仮想ディスプレイ)に `setLaunchDisplayId` + `setLaunchWindowingMode(1=全画面)` でアプリを埋め込む。分割ではない。特権 API |
| 「よく使うアプリ」の選択 | `com.suding.launcher.home.hot.WindowAppsChoseView`(アプリの選択リスト。分割の起動はしていない) |

ペアのタップ時の logcat(D の実験で、アクセシビリティからクリックしたとき):
```
D/SplitScreenDebug(3183): toggleSplit2Screen()            ← 3183 = com.android.launcher3
D/SplitScreenManager(3183): toggleSplit2Screen getSplitPlaceholder()->launchExistingSplitPair 126
D/ShellSplitScreen(2840): startTasks: task1=126 task2=128 position=0 snapPosition=1   ← 2840 = SystemUI
D/ShellSplitScreen(2840): updateWindowBounds: topLeftStage=Rect(0, 0 - 1152, 1080) bottomRightStage=Rect(1167, 0 - 1920, 1080)
```
ペアの起動は Launcher3 の内部処理から SystemUI の Shell(Recents 担当専用)への呼び出しで、外部から同じ経路に入る入口は無い。(「アプリケーションの選択」画面の Activity 名は、画面を開く操作が D と重なるため未記録)

## exported な部品(印 ★ = 名前が分割系のもの。該当なし)

| パッケージ | 部品 | 種別 | intent-filter | 権限 | 備考 |
|---|---|---|---|---|---|
| com.android.launcher3 | `.uioverrides.QuickstepLauncher` | Activity | MAIN / HOME / DEFAULT / LAUNCHER_APP | なし | ホーム本体 |
| com.android.launcher3 | `.secondarydisplay.SecondaryDisplayLauncher` | Activity | SECONDARY_HOME | なし | |
| com.android.launcher3 | `com.suding.launcher.activity.AllAppsActivity` | Activity | MAIN / LAUNCHER | なし | 「すべてのアプリ」 |
| com.android.launcher3 | `.WidgetPickerActivity` | Activity | PICK | なし | |
| com.android.launcher3 | `.dragndrop.AddItemActivity` | Activity | CONFIRM_PIN_APPWIDGET / CONFIRM_PIN_SHORTCUT | なし | ピン留めの確認 |
| com.android.launcher3 | `.settings.SettingsActivity` | Activity | APPLICATION_PREFERENCES | なし | |
| com.android.launcher3 | `com.android.quickstep.interaction.*` | Activity | GESTURE_SANDBOX 等 | なし | ジェスチャー練習 |
| com.android.launcher3 | `com.android.quickstep.TouchInteractionService` | Service | QUICKSTEP_SERVICE | STATUS_BAR_SERVICE | SystemUI 専用 |
| com.android.launcher3 | `com.carsyso.mainsdk.core.MainSDKService` | Service | `com.android.launcher3.MainSDKService` | **なし** | メーカー SDK。中身は下記 |
| com.carsyso.main | `.core.MainService` | Service | `com.carsyso.main.core.MainService` | **なし** | MainAiBox(システムアプリ)の本体サービス |
| com.carsyso.main | `.misc.accessibility.AccessibilityHookerService` | Service | AccessibilityService | BIND_ACCESSIBILITY_SERVICE | |
| com.carsyso.main | `.ui.MainActivity` | Activity | MAIN | なし | |

## APK から分かったこと

- Launcher3 のメーカー独自クラス(`com.suding.*`、`com.carsyso.*`)で、分割系 API(`SplitSelectStateController`、`SystemUiProxy.startTasks` 等、`LAUNCH_ADJACENT`、`ISplitScreen`)を呼ぶものは無い。`setLaunchWindowingMode` を呼ぶのは `TaskViewManager`(上記の埋め込み表示)だけ
- メーカー SDK(`com.carsyso.mainsdk`、MainAiBox の `MainService` に接続するクライアント)の API は、車の機能(Bluetooth・ラジオ・CAN・車両・キー・設定・輝度・言語など)。分割・ウィンドウ操作のメソッドは無い
- MainAiBox には古い Android(9〜12)向けの SystemUI 共有ライブラリ(`com.android.systemui.shared9..12`、`FreeformTool`)が同梱されているが、外部 API として公開されている形跡は無い

## 副産物(分割以外)

- `com.carsyso.mainsdk.manager.SystemManager.requestSwitchOriginalMpu` / `requestSwitchOriginalMpuSource` / `requestOriginalMpuSwitchSource`: **純正画面(車の元のディスプレイ)への切り替え**と思われる。仕様の「車ボタン」(実験⑧、Task 13)の仕組みの最有力候補。呼び出しには MainAiBox の `MainService` への bind と AIDL の再現が必要(未試行)
- `SettingsManager.requestSetAdbMode` / `getAdbMode`: adb のモード設定。再起動ごとのワイヤレスデバッグ有効化の手間を減らせる可能性(未試行・慎重に扱う)
