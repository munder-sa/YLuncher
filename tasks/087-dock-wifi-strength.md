# Task 087: ドックの Wi-Fi マークを、接続と電波の強さに連動させる

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(司令塔の調査 2026-10-31、ユーザー了承済み)
- 今のドックの Wi-Fi マーク(`ui/panel/Dock.kt` の `dock_wifi`)は、`ui/panel/PanelEnvironment.kt` の `readDockStatus` の `WifiManager.isWifiEnabled` だけを見て、白(ON)か薄い白(OFF)にしている。形は常に `Icons.Default.Wifi`(4本)。電波の強さ・接続の有無は見ていない。更新は Wi-Fi の ON/OFF の放送(`WIFI_STATE_CHANGED_ACTION`)と ON_RESUME だけ。
- **不具合**: マニフェスト(合成後も)に `android.permission.ACCESS_WIFI_STATE` が無い。`isWifiEnabled` は SecurityException になり `runCatching` で false → 実機では Wi-Fi につながっていても(RSSI -51dBm)常に薄い表示。

## 変更・作成するファイル(これで全部)
- `app/src/main/AndroidManifest.xml`
- create `app/src/main/java/com/gt8d/launcher/ui/panel/WifiIndicator.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelModels.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelEnvironment.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/Dock.kt`
- create `app/src/test/java/com/gt8d/launcher/ui/panel/WifiIndicatorTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/DockTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelEnvironmentTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. マニフェストに `ACCESS_WIFI_STATE` と `ACCESS_NETWORK_STATE`(どちらも normal。実行時の許可は不要)。
2. `WifiIndicator.kt`:
   ```kotlin
   sealed interface WifiIndicator {
       data object Off : WifiIndicator            // Wi-Fi の機能が OFF
       data object Disconnected : WifiIndicator   // ON だがどこにもつながっていない
       data class Connected(val bars: Int) : WifiIndicator  // 0..4
   }
   fun wifiIndicatorOf(enabled: Boolean, connected: Boolean, level: Int, maxLevel: Int): WifiIndicator
   ```
   `!enabled` → Off、`!connected` → Disconnected、それ以外 → `Connected(bars)`。`bars = if (maxLevel <= 0) 0 else (level.coerceIn(0, maxLevel) * 4 + maxLevel / 2) / maxLevel` を 0..4 に収める(四捨五入)。
3. `DockStatus`: `data class DockStatus(val wifiOn: Boolean, val bluetoothOn: Boolean?, val wifi: WifiIndicator = if (wifiOn) WifiIndicator.Connected(4) else WifiIndicator.Off)`(既存のテストの呼び出しを壊さない)。
4. `readDockStatus`: `WifiManager.isWifiEnabled`、接続は `ConnectivityManager.getNetworkCapabilities(activeNetwork)` が `TRANSPORT_WIFI` を持ち、その `transportInfo as? WifiInfo` の `rssi`(無ければ未接続扱い)。`level = wifiManager.calculateSignalLevel(rssi)`、`maxLevel = wifiManager.maxSignalLevel`。`DockStatus(wifiOn = enabled, bluetoothOn = …, wifi = wifiIndicatorOf(...))`。それぞれ `runCatching` で失敗は安全側(Off/Disconnected)。
5. 更新のきっかけを足す: 今の受信(`WIFI_STATE_CHANGED_ACTION`・Bluetooth)に `WifiManager.RSSI_CHANGED_ACTION` と `WifiManager.NETWORK_STATE_CHANGED_ACTION` を足す。加えて `ConnectivityManager.registerDefaultNetworkCallback`(`onCapabilitiesChanged`・`onLost`)で更新する(`DisposableEffect` で解除)。更新は今と同じく `environment.copy(dockStatus = readDockStatus(context), …)`。コールバックはメインスレッドで状態を書く(`Handler(Looper.getMainLooper())` 付きの登録、または post)。
6. `Dock.kt` の表示(`dock_wifi`):
   - `Off` → `Icons.Default.SignalWifiOff`、薄い白(今の OFF と同じ 0.35)、`contentDescription = "Wi-Fi オフ"`
   - `Disconnected` → `Icons.Default.SignalWifi0Bar`、薄い白、`"Wi-Fi 未接続"`
   - `Connected(n)` → `Icons.Default.SignalWifi0Bar`〜`SignalWifi4Bar`(n に対応)、白、`"Wi-Fi 強さ n/4"`
   - アイコンの名前は使っている material-icons-extended に実在するものを確かめて使う(無ければ近いものを選び報告)。大きさ・位置は今のまま。
   - semantics に `val WifiBars = SemanticsPropertyKey<Int>("WifiBars")`(Off/未接続は -1)を付ける。

## テスト(足す。既存は弱めない)
- `WifiIndicatorTest`: `wifiIndicatorOf(false, …)`→Off、`(true,false,…)`→Disconnected、`(true,true,level,4)` で level 0..4 → bars 0..4、`maxLevel=3` で level 0..3 → 0,1,3,4、`maxLevel=0` → 0、level が範囲外でも 0..4。
- `DockTest`: `Connected(3)` で `WifiBars = 3`・説明「Wi-Fi 強さ 3/4」、`Disconnected`・`Off` で -1 と説明。既存の `DockStatus(wifiOn = true, …)` は Connected(4) 扱い。
- `PanelEnvironmentTest`: Robolectric の Shadow(`ShadowWifiManager`・`ShadowConnectivityManager`/`ShadowNetworkCapabilities`)で、Wi-Fi ON・接続・RSSI を設定すると Connected になり、`RSSI_CHANGED_ACTION` を送ると bars が変わる。権限が無い場合の扱いは、Shadow で再現できなければ省いてよい(報告する)。既存の `DockStatus(true, true)` などの比較は、新しい項目を含めて合わせる(意味は変えない)。

## この Task でしないこと
- Bluetooth の権限の扱いの変更。ドックの他の部分の変更。git のコミット等。

## 報告
変更点、使ったアイコンの名前、テストの結果(件数)、**決めたこと**。
