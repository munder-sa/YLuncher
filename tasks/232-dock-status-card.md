最初に `tasks/RULES.md` を読み、従う。

# Task 232: ドックのステータスを独立カードにし、SIM の電波を表示する

## 背景(ユーザーと合意済み。モックの「案 C」)
- 右端のドック最上部のステータスは、Wi-Fi と Bluetooth を 20dp のアイコンで 1 行(高さ 36dp)に並べているだけで、小さく、SIM の電波が無い。
- ドックに使える高さは 694dp(画面 726dp − 上下 16dp)、今のドックは 644dp で、下に 50dp 空いている。
- 合意した形: **ステータスを、ドックから切り離した別のカードにする**。2 段で、上段に SIM の電波と回線の種類、下段に Wi-Fi と Bluetooth。アイコンは 30dp。
- `Dock` はホーム(`PanelScreen.kt`)と分割時のパネル(`PanelContent.kt`)の両方で使う共通の部品。**両方が同じ見た目に変わること**(呼び出し側は変えない)。
- 司令塔が実機(GT8D、Android 15、targetSdk 35)で確認済み: SIM は KDDI、`TelephonyDisplayInfo {network=LTE, overrideNetwork=NR_NSA}`、SIM スロットは 2 つで片方は空。

## 変えること

### 1. モデル(新規 `CellIndicator.kt`、`PanelModels.kt`)
- `ui/panel/CellIndicator.kt` を新規作成する。
  ```kotlin
  sealed interface CellIndicator {
      data object Unavailable : CellIndicator   // 端末に電話機能が無い・読めない → 何も表示しない
      data object NoSim : CellIndicator
      data object Airplane : CellIndicator
      data class Signal(val bars: Int, val label: String?) : CellIndicator  // bars は 0..4。0 は圏外の表示
  }
  ```
- 同じファイルに、Android に依存しない純粋な関数を置く(単体テストの対象)。
  - `fun cellIndicatorOf(hasTelephony: Boolean, airplane: Boolean, simReady: Boolean, level: Int?, networkType: Int, overrideType: Int): CellIndicator`
    - `!hasTelephony` → `Unavailable`。次に `airplane` → `Airplane`。次に `!simReady` → `NoSim`。
    - それ以外 → `Signal(bars = (level ?: 0).coerceIn(0, 4), label = if (bars == 0) null else cellLabelOf(networkType, overrideType))`。
  - `fun cellLabelOf(networkType: Int, overrideType: Int): String?`
    - `overrideType` が `TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA`・`OVERRIDE_NETWORK_TYPE_NR_ADVANCED` → `"5G"`。
    - `networkType` が `NETWORK_TYPE_NR` → `"5G"`、`NETWORK_TYPE_LTE` → `"4G"`、`UMTS`・`HSDPA`・`HSUPA`・`HSPA`・`HSPAP`・`EVDO_0`・`EVDO_A`・`EVDO_B`・`EHRPD`・`TD_SCDMA` → `"3G"`、`GPRS`・`EDGE`・`CDMA`・`1xRTT`・`IDEN`・`GSM` → `"2G"`、それ以外(`UNKNOWN`・`IWLAN` を含む)→ `null`。
    - 定数は `android.telephony.TelephonyManager` / `TelephonyDisplayInfo` の定数(Int)を参照してよい(コンパイル時定数なので単体テストで使える)。
- `PanelModels.kt` の `DockStatus` に、既定値つきの引数を**最後に**足す: `val cell: CellIndicator = CellIndicator.Unavailable`。既存の引数の順と既定値は変えない。

### 2. 読み取りと監視(`PanelEnvironment.kt`、`AndroidManifest.xml`)
- `AndroidManifest.xml` に `<uses-permission android:name="android.permission.READ_BASIC_PHONE_STATE" />` を足す(通常の権限。実行時の許可画面は出ない)。**`READ_PHONE_STATE` は足さない。**
- `WifiReader` と同じ形で、差し替えできる読み取り口を置く。
  ```kotlin
  internal object CellReader {
      var read: (Context) -> CellIndicator = ::readCellFromSystem
      /** 監視を始め、やめる関数を返す。onChange は main スレッドで呼ぶ。 */
      var observe: (Context, () -> Unit) -> (() -> Unit) = ::observeCellFromSystem
  }
  ```
- `readCellFromSystem(context)`:
  - `PackageManager.FEATURE_TELEPHONY` が無い、または `TelephonyManager` が取れない → `Unavailable`。
  - 対象は既定のデータ用 SIM: `SubscriptionManager.getDefaultDataSubscriptionId()` が有効(`INVALID_SUBSCRIPTION_ID` でない)なら `createForSubscriptionId` したもの、無効なら元の `TelephonyManager`。
  - 機内モード: `Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1`。
  - SIM: `simState == TelephonyManager.SIM_STATE_READY`。
  - 強さ: `signalStrength?.level`。
  - 回線の種類: `dataNetworkType`(`READ_BASIC_PHONE_STATE` で読める)。override は、監視で最後に受け取った `TelephonyDisplayInfo.overrideNetworkType`(まだ受け取っていなければ `OVERRIDE_NETWORK_TYPE_NONE`)。最後の値は `PanelEnvironment.kt` の中の private な保持先に置き、監視をやめたら `NONE` に戻す。
  - **個々の読み取りは `runCatching` で包み、例外(SecurityException を含む)は「読めなかった値」(level=null、networkType=UNKNOWN など)として続行する。** 全体が失敗したら `Unavailable`。
- `observeCellFromSystem(context, onChange)`:
  - `TelephonyCallback` で `SignalStrengthsListener` と `DisplayInfoListener` を実装したものを、上と同じ対象の `TelephonyManager` に `registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)` で登録する。どちらのコールバックも(DisplayInfo は override の値を保持してから)`onChange()` を呼ぶ。
  - 登録が例外になったら、`DisplayInfoListener` を外した `SignalStrengthsListener` だけのコールバックで登録し直す。それも失敗したら何も登録しない(例外を外へ出さない)。
  - 戻り値は、登録したコールバックを `unregisterTelephonyCallback` する関数(失敗は握りつぶしてよい。ここは既存の `runCatching { unregister… }` と同じ扱い)。
- `readDockStatus` で `cell = CellReader.read(context)` を入れる。
- `observeVisibleRadios` の `start()` の中で `CellReader.observe(context) { refresh() }` を呼び、`release` で解除する(Wi-Fi の受信機・ネットワークのコールバックと同じ寿命。見えていない間は監視しない)。
- `radioActions` に `Intent.ACTION_AIRPLANE_MODE_CHANGED` を足す。
- 既存の「値が変わったときだけ `radioRevision` を増やす」動きは変えない(電波の通知は頻繁に来るので、`DockStatus` が同じなら再描画を増やさないこと)。

### 3. 見た目(`Dock.kt`、新規 `CellSignalIcon.kt`)
- `Dock` の引数は変えない。中身を次の構造にする。
  ```
  Column(Modifier.width(88.dp).testTag("dock"), verticalArrangement = spacedBy(8.dp)) {
      Surface(color = Palette.Background1, shape = RoundedCornerShape(16.dp),
              modifier = Modifier.width(88.dp).height(80.dp).testTag("dock_status")) { …ステータス… }
      Surface(color = Palette.Background1, shape = RoundedCornerShape(16.dp),
              modifier = Modifier.width(88.dp).weight(1f, fill = false).testTag("dock_apps")) {
          Column(Modifier.width(88.dp).verticalScroll(rememberScrollState()), …今までと同じ spacedBy(4.dp)…) { …アプリ・設定・ホーム・セット追加・車・アプリ一覧… }
      }
  }
  ```
  - `testTag("dock")` は外側の Column に付ける(既存のテストが全体の位置と「dock の子孫にスクロールがあること」を見ている)。
  - 高さが足りないとき、**ステータスのカードは固定のまま、下のカードの中だけがスクロールする**。
  - 下のカードの中身と順番、各ボタンの大きさ・タグ・説明は今までどおり。今までのステータスの `Row`(36dp)は下のカードから無くす。
- ステータスのカードの中: `Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = CenterHorizontally)` に 2 行。各行は高さ 36dp の `Row`(中央寄せ)。
  - **上段(testTag `dock_cell_row`)**: `status.cell` による。
    - `Unavailable` → 上段そのものを出さない(下段だけが中央に来る)。
    - `Signal(bars, label)` → バーのアイコン(30dp、testTag `dock_cell`)+ label があれば `Text`(16sp、Bold、白、testTag `dock_cell_label`)。間は 5dp。説明は `bars == 0` なら「モバイル通信 圏外」、それ以外は「モバイル通信 強さ $bars/4」(label があれば後ろに「 $label」)。
    - `NoSim` → バー 0 本のアイコンに赤い斜線(30dp、testTag `dock_cell`)。説明「SIM なし」。
    - `Airplane` → `Icons.Default.AirplanemodeActive`(30dp、白、testTag `dock_cell`)。説明「機内モード」。
    - `dock_cell` には `semantics { this[CellBars] = … }` を付ける(`WifiBars` と同じ形の `val CellBars = SemanticsPropertyKey<Int>("CellBars")`。Signal は bars、それ以外は -1)。
  - **下段**: 今までの Wi-Fi と Bluetooth のアイコン。大きさを 20dp → **30dp**、間を 4dp → **8dp** に変える。タグ(`dock_wifi`・`dock_bt`)・説明・色・`WifiBars`・「Bluetooth の権限が無ければ出さない」は今までどおり。
- `ui/panel/CellSignalIcon.kt` を新規作成する(`SetAddIcon.kt` と同じ書き方の `ImageVector`)。
  - `fun cellSignalIcon(bars: Int, slashed: Boolean = false): ImageVector`。24×24 の viewport に、下端をそろえた 4 本の角丸の棒(x = 2.5 / 7.5 / 12.5 / 17.5、幅 3.6、高さ 6 / 10 / 14 / 18、下端 y = 21)。`bars` 本目までは白(alpha 1.0)、残りは白の alpha 0.28。`slashed` のときは (3,3)→(21,21) の線(太さ 2.2、色 `Color(0xFFFF7A6B)`、端は丸)を重ねる。
  - `Icon` で描くと tint で単色になるので、**色の違いを保つために `Image(imageVector = …)` で描くこと**(tint を掛けない)。
  - `bars`・`slashed` の組は 10 通りしかないので、`remember(bars, slashed)` で作り直しを避ける。

## テスト

### 既存のテスト
- `DockTest.kt`・`PanelEnvironmentTest.kt`・`PanelContentTest.kt`・`PanelScreenTest.kt`・`LayoutMatrixTest.kt` などの既存の検証は弱めない。
- **今回の仕様変更で値が変わってよいのは次だけ**(該当する既存の検証があれば、新しい値に合わせて更新してよい。報告の「決めたこと」に 1 件ずつ書く): Wi-Fi・Bluetooth のアイコンの大きさ 20dp → 30dp、ステータスが下のカードから独立したことによる各ボタンの縦位置。これ以外で既存のテストが落ちる場合は、直さずに止めて報告する。

### 追加(それぞれ別のテスト)
- 新規 `CellIndicatorTest.kt`(純粋な関数。Robolectric 不要):
  1. `cellIndicatorOf`: 電話機能なし → Unavailable(他の引数に関係なく)。機内モード → Airplane(SIM あり・なしに関係なく)。SIM なし → NoSim。
  2. level=3・LTE・override NONE → `Signal(3, "4G")`。level=null → `Signal(0, null)`。level=0 → label は null。level=9 → bars は 4、level=-1 → bars は 0。
  3. `cellLabelOf`: LTE+NR_NSA → "5G"、LTE+NR_ADVANCED → "5G"、NR+NONE → "5G"、LTE+NONE → "4G"、LTE+LTE_CA → "4G"、HSPAP → "3G"、EDGE → "2G"、UNKNOWN → null、IWLAN → null。
- `DockTest.kt` に追加:
  4. `dock_status` の高さが 80dp・幅が 88dp。`dock_status` の下端と `dock_apps` の上端の間が 8dp。
  5. `Signal(3, "5G")`: `dock_cell` が表示され `CellBars` が 3、説明が「モバイル通信 強さ 3/4 5G」、`dock_cell_label` の文字が「5G」。`dock_cell` の大きさが 30dp。
  6. `Signal(0, null)`: 説明「モバイル通信 圏外」、`dock_cell_label` が存在しない。`NoSim`: 説明「SIM なし」、`CellBars` が -1。`Airplane`: 説明「機内モード」。
  7. `Unavailable`(既定値): `dock_cell_row` が存在せず、`dock_wifi` は表示される。
  8. `dock_wifi` と `dock_bt` の大きさが 30dp で、`dock_cell` より下にある。
  9. 高さの足りない親(例: 高さ 400dp)に置いたとき、`dock_status` は全体が表示されたままで、`dock_apps` の中がスクロールできる(`dock_applist` へスクロールして表示できる)。
- `PanelEnvironmentTest.kt` に追加(既存の `WifiReader` の差し替えと同じやり方。テストの後で必ず元に戻す):
  10. `CellReader.read` を差し替えると、その値が `dockStatus.cell` に入る。
  11. `CellReader.observe` を差し替え、見えている間(STARTED)だけ監視が登録され、STOP で解除の関数が呼ばれ、再び START で登録し直されること。監視の `onChange` を呼ぶと `dockStatus.cell` が読み直されること。値が変わらなければ `radioRevision` が増えないこと。

## 作る・変えるファイル(これ以外は変えない)
- 変更: `app/src/main/java/com/gt8d/launcher/ui/panel/Dock.kt`
- 変更: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelModels.kt`
- 変更: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelEnvironment.kt`
- 変更: `app/src/main/AndroidManifest.xml`
- 新規: `app/src/main/java/com/gt8d/launcher/ui/panel/CellIndicator.kt`
- 新規: `app/src/main/java/com/gt8d/launcher/ui/panel/CellSignalIcon.kt`
- 変更: `app/src/test/java/com/gt8d/launcher/ui/panel/DockTest.kt`
- 変更: `app/src/test/java/com/gt8d/launcher/ui/panel/PanelEnvironmentTest.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/ui/panel/CellIndicatorTest.kt`

上記の範囲で済まない場合(ほかの既存テストの更新が要る場合を含む)は、止めて報告する。版(versionCode・versionName)は変えない。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.layout.*'

## 報告
- 実行したコマンドと結果(件数・失敗数)、変更の要約、「決めたこと」。
- 実機で確かめていないこと(SIM の値の読み取り、権限)は「未確認」と書く。
