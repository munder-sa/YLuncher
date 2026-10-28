# Task 13: スワイプ帯と幕の窓

**最初に `tasks/RULES.md` を読み、従うこと。**

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 13**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.2・§4.3(幕の安全・操作の安全)・§5.2 に従う。計画の Global Constraints を守る。

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 新規: `app/src/main/java/com/gt8d/launcher/edge/EdgeGesture.kt`、`EdgeStripService.kt`、`CurtainWindow.kt`、`WindowManagerFacade.kt`、`BootReceiver.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/edge/EdgeGestureTest.kt`、`CurtainWindowTest.kt`
- 変更: `app/src/main/java/com/gt8d/launcher/curtain/CurtainController.kt`(下の「CurtainController の修正」だけ)
- 変更: `app/src/test/java/com/gt8d/launcher/curtain/CurtainControllerTest.kt`(テストを1つ足すだけ。既存のテストは変えない)
- 変更: `app/src/main/AndroidManifest.xml`

## 仕様

### EdgeGesture(パッケージ `com.gt8d.launcher.edge`、純粋な Kotlin)
```kotlin
enum class Side { Left, Right }
enum class EdgeAction { Next, Prev, Home, None }
object EdgeGesture {
    fun classify(side: Side, downX: Float, downY: Float, upX: Float, upY: Float, durationMs: Long, longPressMs: Int, density: Float): EdgeAction
}
```
- 内向き移動 = 左帯は `upX - downX`、右帯は `downX - upX`(px)。dp = px / density
- 内向き移動 ≥ 48dp かつ |dy| < 内向き移動の 0.5 倍 → 左帯は `Next`、右帯は `Prev`
- 移動距離(√(dx²+dy²))< 16dp かつ `durationMs ≥ longPressMs` → `Home`
- それ以外 `None`

### WindowManagerFacade(テストのための薄い抽象)
```kotlin
interface WindowManagerFacade {
    fun addView(view: View, params: WindowManager.LayoutParams)
    fun removeView(view: View)
}
class AndroidWindowManagerFacade(private val wm: WindowManager) : WindowManagerFacade
```

### CurtainWindow(`CurtainView` の実装)
```kotlin
class CurtainWindow(
    private val context: Context,
    private val wm: WindowManagerFacade,
    private val contentFactory: (Context) -> ComposeView,      // 既定は ComposeView(context)
    private val readdStrips: () -> Unit,                         // 幕の上に帯を置き直す
) : CurtainView
```
- `show(target)`: `ComposeView` に `CurtainContent(target, showProgress)` を表示する(`showProgress` と `target` は Compose の state で持ち、`update`・`showProgress` で書き換える)。`TYPE_APPLICATION_OVERLAY`、`MATCH_PARENT`×`MATCH_PARENT`、`FLAG_LAYOUT_IN_SCREEN`、`PixelFormat.TRANSLUCENT`。**タッチを遮る**(`FLAG_NOT_TOUCHABLE` を付けない。`FLAG_NOT_FOCUSABLE` は付ける)。フェードインは `view.alpha` を 0→1、120ms。追加後に `readdStrips()` を呼ぶ
- オーバーレイの窓に `ComposeView` を置くため、`LifecycleOwner`・`SavedStateRegistryOwner` を自前で用意して `setViewTreeLifecycleOwner` 等で設定する(Service 上で Compose を使う一般的な方法)
- `hide(reason)`: フェードアウト 150ms のあと `removeView`。**`removeView` は例外を握りつぶして必ず試みる**。フェード中に例外が出ても、`finally` で `removeView` する。二重の `removeView` はしない
- 二重の `show` は前の窓を外してから出し直す

### CurtainController の修正(Task 12 の補強)
- `hide(reason)` の中の `view.hide(reason)` を `try { ... } finally { onHidden?.invoke(reason) }` にする(`view.hide` が例外を投げても、`onHidden` を必ず1回呼ぶ。例外は外に投げ直してよい)
- テストを1つ足す: `onHidden_calledEvenIfViewHideThrows`(`view.hide` が例外を投げる `FakeCurtainView` で `ready()` → `onHidden(Ready)` が1回。例外が外に出るなら `runCatching` で包んで呼ぶ)

### EdgeStripService
```kotlin
class EdgeStripService : Service() {
    companion object {
        fun ensureRunning(context: Context)          // startForegroundService(Intent(context, EdgeStripService::class.java))
        @Volatile var listener: ((EdgeAction) -> Unit)? = null
        @Volatile var curtainView: CurtainView? = null   // onCreate で CurtainWindow を作って入れ、onDestroy で null
        @Volatile var gestureConfig: GestureConfig = GestureConfig()
    }
}
```
- `onCreate`: 通知チャネル「GT8D Launcher」、通知「GT8D Launcher 動作中」1件で `startForeground`(type `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`)。左右の帯の窓を追加
- 帯: 左右に幅 `gestureConfig.stripWidthPx`・高さ `MATCH_PARENT` の `TYPE_APPLICATION_OVERLAY`、`FLAG_NOT_FOCUSABLE`。`View.OnTouchListener` で DOWN の座標・時刻と UP の座標・時刻を取り、`EdgeGesture.classify` の結果が `None` 以外なら `listener?.invoke(action)`。長押しは、DOWN から `longPressMs` 経っても指が 16dp 以内なら、UP を待たずに `Home` を出してよい(その場合 UP では何もしない)
- glow=ON なら押下中だけ帯の背景を `Palette.Accent` の 30% 不透明にする(フェード 100ms)
- `readdStrips`: 帯の窓を `removeView` → `addView` し直す(幕より上に置くため)
- `onDestroy`: 帯と幕を外す(例外は握りつぶす)

### BootReceiver
- `BOOT_COMPLETED` で `EdgeStripService.ensureRunning(context)` だけを行う(E の再判定は後の Task 16 で足す)

### AndroidManifest.xml
- 追加: `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`、`POST_NOTIFICATIONS`
- `<service android:name=".edge.EdgeStripService" android:exported="false" android:foregroundServiceType="specialUse">` と、その中に `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="edge-swipe-overlay" />`
- `<receiver android:name=".edge.BootReceiver" android:exported="true">`、`BOOT_COMPLETED` の intent-filter

## 手順(TDD)

1. テストを書く:
   - `EdgeGestureTest`(純粋な JUnit、density=1.5):
     - `classify_leftInward_isNext`(左帯、80dp 内向き、縦 5dp)、`classify_rightInward_isPrev`
     - `classify_longPress_isHome`(移動 4dp・1100ms、longPressMs=1000)
     - `classify_tapOrDiagonal_isNone`: 4dp・100ms → None、内向き 60dp・縦 50dp → None、外向き 60dp → None
   - `CurtainWindowTest`(Robolectric、`@Config(sdk = [35])`。呼び出しを記録する `FakeWindowManagerFacade`):
     - `show_addsTouchBlockingOverlay_andReaddsStrips`: show → addView 1回、params.type が `TYPE_APPLICATION_OVERLAY`、`FLAG_NOT_TOUCHABLE` が付いていない、`readdStrips` 1回
     - `hide_removesWindow`: show → hide → (Robolectric の Looper を進めて)removeView 1回
     - `hide_removesWindow_evenIfRemoveThrowsOnce`: `removeView` が例外を投げても、hide が例外を外に投げない
   - `CurtainControllerTest.onHidden_calledEvenIfViewHideThrows`(上記)
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 報告

`test assembleDebug` の結果(実行件数と失敗件数)、作成・変更したファイル名、**決めたこと**。
