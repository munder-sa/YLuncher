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

- 再起動のたびに、端末でワイヤレスデバッグの有効化が必要(adb は Windows の `adb.exe` で届く。WSL の adb は届かない)
- 端末に残した設定変更: `enable_freeform_support=1`(元は未設定)、アクセシビリティに Spike のサービスを追加(元は `com.carsyso.main/...AccessibilityHookerService` のみ)。debug APK と通知アクセス・オーバーレイの許可も残っている

## ゲート判定

①②が不合格。計画の Task 1 Step 5 に従い、後続(Task 4 の起動手順)を確定させず、ユーザーに相談する。
