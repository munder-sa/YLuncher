# 家での実験①〜⑦ の結果(2026-10-28)

端末: GT8D(Qualcomm Lito、Android 15 / API 35)、画面 1920x1080、density 238(≒ 1291dp × 726dp)。
方法: debug ビルドの SpikeActivity／SpikeReceiver(`app/src/debug/.../spike/`)を `adb.exe` 経由で操作し、`screencap`・`dumpsys activity activities`・`logcat -s GT8D-SPIKE` で確認。

## 判定

| 実験 | 判定 | 要点 |
|---|---|---|
| ① 2アプリの分割(YouTube｜Apple Music) | **不合格** | `NEW_TASK` → 300ms → `NEW_TASK \| LAUNCH_ADJACENT \| MULTIPLE_TASK` の手順では、右(Apple Music)が全画面で開き、左(YouTube)は背面の全画面タスクのままだった。分割にならない |
| ② アプリ｜自作パネル(YouTube｜SpikePanelActivity) | **不合格** | ①と同じ。Spike パネルが全画面タスクになり、YouTube は背面。画面のボタンからの実行(フォアグラウンドの Activity が起点)でも同じ |
| ③ スワイプ帯(オーバーレイ 8px) | **合格** | 左右の帯とも `TYPE_APPLICATION_OVERLAY` の窓がタッチを受ける。DOWN→MOVE→UP が、指が帯の外へ出ても途切れず届く(`input swipe` による注入。実際の車経由のタッチは実験⑨で確認)。静止した長押しも MOVE が連続して届く |
| ④ メディアセッション | **合格** | 通知アクセス許可後、`MediaSessionManager.getActiveSessions` で Apple Music を取得。曲名・アーティスト・duration(281000)・ジャケット・位置・`lastPositionUpdateTime` が取れる。☆: customActions に `com.apple.android.music.playback.action.FAVORITE`(お気に入り)がある |
| ⑤ 分割中の別組み合わせの起動 | **不合格** | ユーザーが純正操作で作った分割(YouTube｜Apple Music、および YouTube｜Spike パネル)の状態で、同じ手順(起動元はアプリのコンテキスト)を撃つと、右が全画面で開き、分割は解除された。分割の中の Activity 自身を起動元にした場合は未確認(分割を作る手段が無いため、後回し) |
| ⑥ 左右の指定どおりに開くか | 未実施 | 分割が作れないため |
| ⑦ ナビゲーションモード | `navigation_mode=0`(3ボタン) | ジェスチャーナビではないので、画面端のスワイプはシステムの戻るジェスチャーと競合しない |

## 補足

- 実験前、端末は YouTube(左・約 1470px)｜Apple Music(右・約 450px)の分割表示だった(純正ランチャーの操作で作られたもの)。分割自体は端末が対応している。
- ④の `lastPositionUpdateTime`(21337091)は `SystemClock.elapsedRealtime()` と同じ時間軸(オーバーレイのタッチ時刻 `eventTime` と近い値)。`positionAt(nowMs)` には `SystemClock.elapsedRealtime()` を渡す。
- ユーザーのスクリーンは `1920x1088` でキャプチャされる(scrcpy 表示上)。実際の `wm size` は 1920x1080。

## 追加実験(分割を作る別の方法)

| 案 | 判定 | 要点 |
|---|---|---|
| アクセシビリティ `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` | **不合格** | Spike のアクセシビリティサービス(タッチ探索なし)を有効にし、全画面の Spike パネルの上で呼ぶと、戻り値 `false`。何も起きない。この端末の Quickstep は実装していない |
| フリーフォーム + `ActivityOptions.setLaunchBounds` | **不合格** | `settings put global enable_freeform_support 1` → 再起動後も値は残る。ただし `FEATURE_FREEFORM_WINDOW_MANAGEMENT=false` のまま。左右の範囲を指定して起動しても、`mode=fullscreen` で全画面に開き、範囲は無視された |
| フリーフォーム + 非公開 `ActivityOptions.setLaunchWindowingMode(5)`(リフレクション) | **合格(条件つき)** | `settings put global hidden_api_policy 1`(元は未設定)を入れ、**アプリのプロセスを再起動したあと**に、`setLaunchBounds` と併用すると、指定どおりの範囲・`mode=freeform` で左右に並ぶ(YouTube 0〜960 ｜ Googleマップ 960〜1920 を確認。logcat: `Accessing hidden method ...setLaunchWindowingMode(I)V (blocked,test-api, reflection, allowed)`)。各ウィンドウの上にタイトルバー(戻る・最小化・最大化・閉じる)が付く |

- フリーフォームでセットを切り替えたとき(YouTube 30% ｜ Apple Music 70% を後から撃つ): **新しく開いたタスク**は指定の範囲(576〜1920)で開くが、**既に開いているタスク**(YouTube)は前の範囲(0〜960)のまま。前のセットのウィンドウ(Googleマップ)は閉じずに背面に残り、隙間があれば見える。既存タスクの範囲を変える公開 API は無い
- `hidden_api_policy` が再起動後も残るかは未確認(グローバル設定なので残る見込み。次に再起動するときに確認する)
- 再起動のたびに、端末でワイヤレスデバッグの有効化が必要(adb は Windows の `adb.exe` で届く。WSL の adb は届かない)
- 端末に残した設定変更: `enable_freeform_support=1`(元は未設定)、アクセシビリティに Spike のサービスを追加(元は `com.carsyso.main/...AccessibilityHookerService` のみ)。debug APK と通知アクセス・オーバーレイの許可も残っている

## D: 純正のアプリペアをアクセシビリティで押す(2026-10-28)

| 確認 | 判定 | 要点 |
|---|---|---|
| D-1 自作パネルを純正の分割で選べるか | **合格** | `SpikePanelActivity` に `MAIN/LAUNCHER`(ラベル「GT8D Panel」)を付けると、ユーザーが純正の分割で「YouTube｜GT8D Panel」のペアを作り、ホームに置けた |
| D-2 ラベルで探してクリック → 分割が開くか | **合格(条件つき)** | デバッグ用アクセシビリティサービス(`canRetrieveWindowContent=true`、タッチ探索なし)で、HOME → ホームの `text`/`contentDescription` が `YouTube\|Apple Music` のノードに `ACTION_CLICK`。SystemUI が `ShellSplitScreen: startTasks task1=135 task2=134`、`topLeftStage=Rect(0,0-1152,1080) bottomRightStage=Rect(1167,0-1920,1080)` で**本物の分割(保存した比率 60:40)**を開いた。タイトルバーなし。「YouTube｜GT8D Panel」も 50:50 で開いた |
| D-3 分割中のパネル(Activity)から `NEW_TASK\|LAUNCH_ADJACENT\|MULTIPLE_TASK` | **合格** | パネルのボタン(Activity 自身が起動元)で Googleマップを開くと、**反対側(左)の YouTube が Googleマップに入れ替わり、パネルは右に残った**。分割は維持 |

条件・注意:
- ホームのラベル: ペアは「左アプリ名\|右アプリ名」(区切りは `|`、空白なし)。読み取りツリーに出るのは**表示中のページの項目だけ**。純正ホームの中央のアイコン一覧(2行×3列)に置く必要がある
- **冷えた起動に弱い**: アプリを強制停止したあと(車では再起動直後に相当)にペアを押すと、Apple Music は `SplashActivity` から本画面へ乗り換える作りのため、右側が空のまま(左だけ表示)になった。起動済みの状態では正常。対策(起動直後に一度アプリを温める、空なら押し直す等)が必要
- **案A の残骸に注意**: フリーフォームで開いたタスクが残っていると、ペアはそのタスクを再利用し(`launchExistingSplitPair`)、分割の枠の中にフリーフォームの窓(タイトルバー付き)が出る。A と D は併用しない
- ペアの起動は Launcher3 内部(`SplitScreenManager.toggleSplit2Screen` → `launchExistingSplitPair`)から SystemUI の Shell へ。既存のタスクを使い回す

## ゲート判定

①②(分割)は不合格。公式 API で分割・フリーフォームを作る方法は無い。非公開 API(`setLaunchWindowingMode`)と端末設定(`enable_freeform_support=1`、`hidden_api_policy=1`)の組み合わせでのみ、左右に並べられる。既存タスクの範囲は変えられない。後続(Task 4 の起動手順と仕様)は、ユーザーと相談して決める。
