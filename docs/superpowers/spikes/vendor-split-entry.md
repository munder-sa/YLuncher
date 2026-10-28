# 調査: 純正ランチャーの分割機能に、外から呼べる入口があるか(2026-10-28)

方法: 読み取りのみ。`dumpsys package`、APK の取り出し(`pm path` → `adb pull`、作業はリポジトリ外)、SDK の `dexdump -d` による逆アセンブル(jadx は未導入。GitHub からの取得がサンドボックスで不可のため)。端末の設定変更・試し撃ちはしていない。

## 判定: **入口あり(合格)** — `com.sd.factoryconfig/.SplitActivity`

当初 Launcher3 と MainAiBox だけを調べて「入口なし」としたが、ユーザーが純正の「ホーム画面のウィジェット設定」の一覧で見つけた「プラント設定」(= `com.sd.factoryconfig`、英語名 FactoryConfig、v1.3.8)に、**外部から起動できる分割の入口**があった。

### 呼び出し方(試し撃ちで確認)
```
adb shell "am start -n com.sd.factoryconfig/.SplitActivity --es split_shortcur_package 'com.google.android.youtube|com.apple.android.music' --es split_percent 3:2"
```
- `split_shortcur_package`(綴りは原文のまま): `左のパッケージ|右のパッケージ`
- `split_percent`: `"1:1"` / `"2:3"` / `"3:2"` / `"3:7"` / `"7:3"`(左:右)。`SplitActivity` が `persist.splitscreen.percent` に書いてから分割する
- `SplitActivity` は `MAIN` の intent-filter を持つ(exported)。ランチャーの一覧には出ない(LAUNCHER カテゴリが無い)

### 仕組み(`SplitActivity.onCreate` の逆アセンブル)
1. extras `split_shortcur_package` を `\|` で分割 → 左右のパッケージ
2. `split_percent` があれば `persist.splitscreen.percent` に設定(システム権限)
3. 顧客設定 `ro.board.reverse.custom` が `mmb` で始まるときだけ、先に Recents/QuickstepLauncher を開く(この端末は `zhiduojing-wireless` なので開かない)
4. ブロードキャスト `com.android.intent.ext.main.split.two.screen`(extras `setting_key_split_screen_l` / `setting_key_split_screen_r` = パッケージ名)を送る。Launcher3 が実行時に登録した受信側で分割を開く
- ホームのペアのショートカットは、同アプリの `SplitAppActivity`(比率 1:1/2:3/3:2/3:7/7:3 を選んで `ShortcutManager.requestPinShortcut`)が作る

### 試し撃ちの結果
| 試験 | 結果 |
|---|---|
| YouTube｜Apple Music、3:2 | **本物の分割**(`mode=multi-window`、タイトルバーなし)、左 60%・右 40% |
| YouTube｜com.gt8d.launcher、1:1 | 自作アプリ(パッケージ名指定。ランチャー用 Activity が開く)を右に置けた |
| Googleマップ｜Apple Music、7:3 を録画しながら | `screenrecord --output-format=raw-frames`(240x136)をコマ送りで確認。切り替え前の画面 → クロスフェード1コマ → 新しい分割。**純正ホームも `SplitActivity` の画面も1コマも映らない** |

### 自作アプリから呼ぶ実験(E1〜E4。debug の `vendor_split` step、帯のオーバーレイを表示した状態でブロードキャスト受信側から `startActivity`)

| 実験 | 結果 | 画面の流れ(`screenrecord` 生フレームのコマ送り) |
|---|---|---|
| E1 自作アプリから YouTube｜Apple Music 3:2(起点はホーム) | **合格**。`resolveActivity` で `SplitActivity(exported=true)` を検出、`startActivity` 成功、分割 60:40 | 起点のホーム → 分割 |
| E2 分割(YouTube｜Apple Music)→ Googleマップ｜自作アプリ 7:3 | 分割は 70:30 で成立。ただし**途中に余計な画面** | 薄いグレー → **自作アプリが全画面** → **純正ホーム+「アプリケーションの選択」** → 分割 |
| E2b 分割(Googleマップ｜自作)→ YouTube｜Apple Music 3:2 | **合格** | 元の分割 → **暗い無地 約 0.5 秒**(`SplitActivity` の画面)→ 新しい分割。ホームは映らない |
| E3 Apple Music を強制停止 → YouTube｜Apple Music 3:2 | 分割は成立し、**右側も空にならない**(D の問題は起きない)。ただし途中に余計な画面 | 暗い画面 → Apple Music の起動画面(全画面)→ **純正ホーム+「アプリケーションの選択」** → 分割 |
| E4 分割(YouTube｜Apple Music)→ YouTube｜自作アプリ 2:3 | **合格**。比率 40:60 | 元の分割 → 暗い無地(数コマ)→ 新しい分割 |

**余計な画面の条件**: 指定したアプリのどちらかに**タスクがまだ無い**(その起動で初めて開く/強制停止・再起動のあと)とき。ランチャーは、そのアプリをまず全画面で起動し、ホームの分割選択の画面を経て、`launchExistingSplitPair` で分割する(ログ: `SplitScreenManager: start mRightPackageName=...` から `launchTasks ... launchExistingSplitPair` まで約 2.5 秒)。両方のタスクがあれば、暗い画面 約 0.5 秒だけ。

- 車ではエンジンをかけるたびに再起動するので、**各アプリの最初の1回は必ず余計な画面が出る**。対策が必要(改訂仕様で扱う)
### 自作アプリを既定のホームにしたまま動くか(H1・H2)

debug の `SpikeActivity` に HOME の intent-filter を付け、`cmd package set-home-activity` で既定のホームにして確認(実験後、純正 `QuickstepLauncher` に戻した)。

| 実験 | 結果 | 画面の流れ |
|---|---|---|
| H1 通常時(純正ランチャーのプロセスは以前から動いている) | **合格**。YouTube｜Apple Music 3:2 | Spike → 暗い画面 → 分割。純正ホームは映らない |
| H2 **再起動直後**(`QuickstepLauncher` の画面が一度も作られていない。`dumpsys` で 0 件) | **合格**。YouTube｜Apple Music 3:2。受け口は純正ランチャーのプロセス(Recents 担当として常駐)内で `onReceive action:com.android.intent.ext.main.split.two.screen` | Spike → 暗い画面 → YouTube の起動(全画面)→ **自作のホーム(Spike)** → 分割。冷えた起動で「純正ホーム+選択画面」が出ていたステップに、**既定のホーム(自作)が出る**。純正ホームは1コマも映らない |

- `factoryconfig` は uid 1000(system)。ブロードキャストは保護されていない(`Sending non-protected broadcast ... from system`)
- 再起動後も、端末の設定変更なしで動く(E は呼ぶだけ)
- 結論: **自作アプリを既定のホームのまま、E で分割を作れる**。元の仕様の構成(自作のホーム+パネル、Launcher3 は Recents 担当)を保てる

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
