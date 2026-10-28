# Task 17a: リンクの振り分け規則(前面の判定と行き先)

**最初に `tasks/RULES.md` を読み、従うこと。** 計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 17** の判定部分、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.7 に従う。Activity・Manifest・画面は次の Task(17b)で作るので、ここでは作らない。

## 作成するファイル(これで全部。既存ファイルは変えない)

- `app/src/main/java/com/gt8d/launcher/link/LinkRouter.kt`
- `app/src/test/java/com/gt8d/launcher/link/LinkRouterTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.LinkRouterTest`(途中の確認用。1分ほど)
- `/home/munde/tools/gradle-run.sh test`(最後に1回だけ。4〜8分かかる。各テストクラスの最初で1〜2分の無出力があるのは正常)
- Gradle はバックグラウンドに回さず、終わるまで待つ形で実行する

## 仕様(パッケージ `com.gt8d.launcher.link`。Android の API を使わない純粋な Kotlin。URL の解析は `java.net.URI`)

```kotlin
sealed interface FrontState {
    data object Home : FrontState
    data object AppPanel : FrontState
    data class AppApp(val left: String, val right: String) : FrontState
    data class Fullscreen(val pkg: String) : FrontState
    data object Unknown : FrontState
}

data class FrontClues(
    val homeResumed: Boolean,                    // 自作の HomeActivity が再開中
    val panelInSplit: Boolean,                   // 自作の PanelActivity が分割の中で再開中
    val lastPair: Pair<String, String>?,         // SetRunner が最後に開いたセットの左右のパッケージ
    val otherForegroundAfterLastPair: Boolean,   // そのあとに左右以外のアプリが前面に来た
    val referrer: String?,                       // リンクを送ってきたアプリのパッケージ名(referrerPackage で正規化済み)
)

sealed interface LinkRoute {
    data object Chrome : LinkRoute
    data object FullscreenBrowser : LinkRoute
    data object PanelTaskBrowser : LinkRoute
    data class VendorSplitThenBrowser(val left: String) : LinkRoute
}

object LinkRouter {
    const val CHROME_PKG = "com.android.chrome"
    fun referrerPackage(raw: String?): String?
    fun detectFront(clues: FrontClues, ownPkg: String): FrontState
    fun route(url: String, cfg: LinkConfig, front: FrontState, vendorUsable: Boolean): LinkRoute
}
```
(`LinkConfig`・`LinkMode` は既存の `com.gt8d.launcher.config`)

### referrerPackage
- `"android-app://com.example.app"` や `"android-app://com.example.app/..."` → `"com.example.app"`
- null、空、`android-app://` で始まらないもの(`https://...` など)→ null

### detectFront(この順で最初に当てはまったもの)
1. `homeResumed` → `Home`
2. `panelInSplit` → `AppPanel`
3. `lastPair` があり、左右どちらも `ownPkg` ではなく、`otherForegroundAfterLastPair` が false で、`referrer` が左右のどちらか → `AppApp(left, right)`
4. `referrer` があり、`ownPkg` ではない → `Fullscreen(referrer)`
5. それ以外 → `Unknown`

### route(この順)
1. `cfg.mode == LinkMode.Chrome` → `Chrome`
2. URL のホストが `cfg.chromeDomains` のどれかと一致する、またはそのサブドメイン(`host == d` か `host.endsWith(".$d")`。大文字小文字は区別しない)→ `Chrome`。URL が解析できない・ホストが無いときは、この規則に当てはまらないとする
3. `front` で決める:
   - `Home` → `FullscreenBrowser`
   - `AppPanel` → `PanelTaskBrowser`
   - `AppApp(left, _)` → `vendorUsable` なら `VendorSplitThenBrowser(left)`、でなければ `FullscreenBrowser`
   - `Fullscreen(pkg)` → `vendorUsable` なら `VendorSplitThenBrowser(pkg)`、でなければ `FullscreenBrowser`
   - `Unknown` → `FullscreenBrowser`

## 手順(TDD)

1. `LinkRouterTest`(純粋な JUnit)を書く。`own = "com.gt8d.launcher"`、`cfg = LinkConfig()`(既定: `InAppSplit`、`["accounts.google.com"]`)、`clues(...)` は全部 false/null を既定にした補助関数でよい
   - `referrer_parsesAndroidAppScheme`: `"android-app://jp.unext.mediaplayer"` → `"jp.unext.mediaplayer"`、`"android-app://org.videolan.vlc/path"` → `"org.videolan.vlc"`、`null`・`""`・`"https://example.com"` → null
   - `detect_homeResumed_isHome`: `homeResumed = true`(ほかに `panelInSplit = true` があっても)→ `Home`
   - `detect_panelInSplit_isAppPanel`: `panelInSplit = true` → `AppPanel`
   - `detect_lastPairAndReferrerMatches_isAppApp`: `lastPair = "org.videolan.vlc" to "com.apple.android.music"`、`referrer = "com.apple.android.music"` → `AppApp("org.videolan.vlc", "com.apple.android.music")`
   - `detect_lastPairWithPanel_notAppApp`: `lastPair = "org.videolan.vlc" to own`、`referrer = "org.videolan.vlc"` → `Fullscreen("org.videolan.vlc")`
   - `detect_otherForegroundAfterPair_isFullscreen`: 上の AppApp の条件に `otherForegroundAfterLastPair = true` を足す → `Fullscreen("com.apple.android.music")`
   - `detect_referrerOnly_isFullscreen`: `referrer = "jp.unext.mediaplayer"` だけ → `Fullscreen("jp.unext.mediaplayer")`
   - `detect_referrerIsOwn_isUnknown`: `referrer = own` だけ → `Unknown`
   - `detect_noClues_isUnknown`: 全部なし → `Unknown`
   - `route_chromeMode_alwaysChrome`: `cfg.copy(mode = LinkMode.Chrome)`、front `Home` → `Chrome`
   - `route_googleLogin_goesChrome`: `"https://accounts.google.com/signin"` → `Chrome`、`"https://ACCOUNTS.google.com/x"` → `Chrome`、`"https://a.accounts.google.com/x"` → `Chrome`、`"https://accounts.google.com.evil.example/x"`(front `Home`)→ `FullscreenBrowser`
   - `route_badUrl_notChrome`: `"not a url"`(front `Home`)→ `FullscreenBrowser`
   - `route_home_fullscreenBrowser`、`route_appPanel_panelTaskBrowser`、`route_appApp_vendorSplitWithLeft`(`AppApp("a","b")` → `VendorSplitThenBrowser("a")`)、`route_fullscreen_vendorSplitWithThatApp`(`Fullscreen("x")` → `VendorSplitThenBrowser("x")`)、`route_unknown_fullscreenBrowser`(URL は `"https://example.com/"`、`vendorUsable = true`)
   - `route_vendorUnusable_fullscreenBrowser`: `vendorUsable = false` のとき `AppApp("a","b")`・`Fullscreen("x")` → どちらも `FullscreenBrowser`、`AppPanel` → `PanelTaskBrowser` のまま
2. `testDebugUnitTest --tests com.gt8d.launcher.link.LinkRouterTest` で失敗(またはコンパイルできない)ことを確認する
3. 実装する
4. `testDebugUnitTest --tests com.gt8d.launcher.link.LinkRouterTest` で通るまで直す(最大3回)。通ったら最後に `/home/munde/tools/gradle-run.sh test` を1回実行する。失敗したら、失敗したテスト名とエラーの先頭30行を報告して止まる

## 報告

最後の `test` の結果(実行件数と失敗件数)、作成したファイル名、**決めたこと**。
