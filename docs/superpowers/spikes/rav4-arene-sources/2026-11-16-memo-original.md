# 新型RAV4（Arene）× GT8D メーター地図暗転・ナビ連携 検証＆調査メモ

作成日: 2026-11-16
対象リポジトリ: [munder-sa/YLuncher](https://github.com/munder-sa/YLuncher)

## 1. 有線Android Auto実車検証：`tools/carprobe` の事前改修要否と手順

`@gt8d-ipv6-guard` の抽象Unixソケット経由で `a0call` から `NAV_FOCUS_NATIVE` を送信し、Finish操作で `service.adb.listen_addrs` のクリアと `ctl.stop adbd` まで安全に実行できる構成になっていれば、**必須のアプリ改修はなく、そのまま有線Android Autoでの1次実車テストに持ち込み可能**。

### 1-1. 実車で守るべき操作順序（有線Android Auto特有の競合回避）

[docs/HANDOFF.md](https://github.com/munder-sa/YLuncher/blob/main/docs/HANDOFF.md) のDHU検証で記録されている通り、GT8D側で `adbd` が動作しているとUSB構成にadbが存在しない状態でも `UsbFfs` のバインド試行が走り続け、USBモード遷移（MTP `18D1:4EE1` → AOA `18D1:2D00`）や投影フレームレートに干渉する懸念がある。実車では以下の順序を徹底する。

1. **デバッグOFFで車両へUSB接続:** USBデバッグ・ワイヤレスデバッグをオフにした状態でGT8Dを車両の通信用USBポートへ接続し、有線Android Auto投影を確立させる。
2. **初期症状の確認:** 投影画面が安定した状態で、12.3インチ デジタルメーター内の地図表示エリアが暗転（ブラックアウト）しているかを確認する（[lucky Car Channel 公式note（実機検証・復活手順）](https://note.com/lucky_car_ch/n/n32a4eeb045d4)）。
3. **`carprobe` からのフォーカス送信:** 投影確立後に `carprobe` を開き、`@gt8d-ipv6-guard` 経由で `a0call` を実行してメーター内地図が純正表示へ復帰するかを確認する。
4. **クリーンアップ:** 検証完了後はFinishボタンでプロパティをリセットし、`adbd` を停止してから終了する。

### 1-2. 任意確認項目（手戻り防止用）

* **`NAV_FOCUS_PROJECTED`（`2`）→ `NAV_FOCUS_NATIVE`（`1`）の2段送信可否:** 接続直後にGT8D側がナビフォーカス未保持扱いになっている場合、`NAV_FOCUS_NATIVE`（`1`）単発では車載機側が「状態変化なし（no-op）」として無視する可能性がある（[open-android-auto Navigation Channel 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md)）。一度 `PROJECTED`（`2`）でフォーカス遷移エッジを作ってから `NATIVE`（`1`）へ戻せると1回の実車テストで切り分けが完結する。
* **接続ネゴシエーション・解像度ログの採取範囲:** 実車の `ServiceDiscoveryResponse` で `DISPLAY_TYPE_CLUSTER` や Channel 11（`NAVIGATION_STATUS`）が広告されているか、および [docs/HANDOFF.md](https://github.com/munder-sa/YLuncher/blob/main/docs/HANDOFF.md) で未確認だった実車要求解像度（`VideoConfig`）が採取ログに残るかを確認する（[open-android-auto Display Routing 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md)）。

## 2. Android Auto経路で意識すべき技術的盲点

### 2-1. 暗転トリガーが「ナビフォーカス」ではなく「クラスタ映像バインド」である可能性

* 新型RAV4にスマートフォンを直結した場合、ルート案内を開始していない（`NAV_FOCUS_PROJECTED` 未取得の）待機状態でもメーター内にスマホ側の地図タイルが表示される（[Reddit r/rav4club](https://www.reddit.com/r/rav4club/comments/1sb1872/maps_in_the_digital_gauge_cluster_on_the_26/)、[Android for Cars ナビアプリ品質ガイドライン NF-9](https://developer.android.com/training/cars/apps/navigation)）。
* そのため、Arene側がメーター描画先を外部機器へ切り替えているトリガーはナビフォーカスの奪取ではなく、接続時の `ServiceDiscoveryResponse` によるクラスタ映像チャネル（`DISPLAY_TYPE_CLUSTER`）の成立そのものである可能性が高い（[open-android-auto Display Routing 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md)）。

### 2-2. プロトコル上のメッセージ方向の非対称性

* Android Autoの制御チャネル（GAL type 1、msg 13 `NavigationFocusRequest` / msg 14 `NavigationFocusResponse`）では、通常スマホ側は案内開始時に `NAV_FOCUS_PROJECTED`（`2`）を要求し、案内終了時はChannel 11で `NavigationState(INACTIVE)` を送る（[open-android-auto Navigation Channel 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md)）。
* 一方、`NAV_FOCUS_NATIVE`（`1`）は本来ヘッドユニット側が純正ナビ実行時に主張・応答する値である（[Android for Cars DHU テストガイド](https://developer.android.com/training/cars/testing/dhu?hl=ja)）。投影デバイス側から送られた `NAV_FOCUS_NATIVE` を受けて、Areneが車載内部の純正ナビオーナーシップ（[AOSP Instrument Cluster API](https://source.android.com/docs/automotive/displays/cluster_api) の `CarAppFocusManager` 相当）まで能動的に引き戻す実装になっているかが成否を分ける。

## 3. CarPlay偽装接続時の技術構造と追加調査計画（Claude引き継ぎ用）

GT8DをワイヤレスCarPlay等（CarPlay偽装モード）で接続する場合（[lucky Car Channel 公式note（GT8D検証）](https://note.com/lucky_car_ch/n/n3b41e5108a04)）、Android Auto逆投影用の `sdAutoReverse` とは**別プロセス・別プロトコル**で動作するため、`a0call`（`NAV_FOCUS_NATIVE`）は適用できない。CarPlay偽装時は以下の仕様・仮説に基づき別系統での調査が必要となる。

### 3-1. CarPlayプロトコル上の構造的特徴（Android Autoとの違い）

* **AI BOX側がResource Manager（審判）になる:** Android Autoでは車載機側がフォーカス承認者だが、CarPlayではiPhone側（＝偽装しているGT8D側）にResource Manager（Controller）が存在し、車載機（Accessory）からの `changeModes` を受けて、GT8D側から `modesChanged` コマンドで現在の `mainScreen`・`mainAudio` および共有 `appStates`（`TurnByTurn` / `PhoneCall` / `Speech`）のオーナーを通知する（[Apple WWDC16 Session 723](https://developer.apple.com/videos/play/wwdc2016/723/)）。`TurnByTurn` は後勝ち（last-in-wins）で排他制御される。
* **RTSP上のメイン映像（`type 110`）とクラスタ映像（`type 111`）の分離:** CarPlayはUSB（NCM）でもワイヤレス（Wi-Fi）でもIP上のAirPlay / RTSPで通信し、センター画面用の主映像ストリーム（`Main` / `type 110`）とは別に、クラスタ用のセカンダリ映像ストリーム（`AltScreen` / `type 111`）と `iAP2 Route Guidance` メタデータを持つ（[Apple WWDC19 Session 252](https://developer.apple.com/videos/play/wwdc2019/252/)、[MHI2Q-CarPlay-AltScreen](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/README_EN.md)）。
* **同ベンダー（`suding`）製アダプタでの既知挙動:** 同じ `suding` ベンダーの単機能ワイヤレスアダプタ解析事例では、車載機が広告したセカンダリ（クラスタ）ディスプレイ情報を標準ファームが破棄し、`type 111` を開かない実装になっていることが確認されている（[OMKE/wireless-carplay-virtual-cockpit](https://github.com/OMKE/wireless-carplay-virtual-cockpit)）。

### 3-2. Ottocast P3 Proとの挙動差から導かれる「Plug-in R15世代」仮説

* **確認されている事実1（機種差）:** 公開実機検証において、CarPlay接続のOttocast P3 Proだけは最初からメーター地図が暗転しない一方、新型RAV4の純正サイドメニューバーを非表示にできず全画面化できないという制約がある（[lucky Car Channel 公式note（AI BOX 4機種比較）](https://note.com/lucky_car_ch/n/n99144259bcb3)、[Ottocast P3 PRO検証動画](https://www.youtube.com/watch?v=MObPSBBcQy4)）。
* **確認されている事実2（CarPlay仕様）:** 「ネイティブUIの戻る領域を残さず全画面化する動的リサイズ（複数 `View Area`）」と「メータークラスタ向けの複数H.264ビデオストリーム」は、どちらも **iOS 13 / CarPlay Communication Plug-in R15** で同時に導入された機能群である（[Apple WWDC19 Session 252](https://developer.apple.com/videos/play/wwdc2019/252/)）。
* **検証すべき技術仮説:** P3 ProはR15以前の単一固定画面仕様でネゴシエーションしているためAreneがクラスタ切替を行わない（代わりに純正サイドバーが残る）のに対し、GT8DやHE PRO系はR15相当の新仕様でネゴシエーションしてセンター全画面化を実現している結果、Arene側もクラスタ映像待機状態（`type 111` 未送出＝黒画面）へ遷移している可能性が高い。

### 3-3. GT8Dファームウェア内での静的・動的調査タスク一覧

1. **CarPlay逆投影デーモン・ライブラリの特定:** [docs/HANDOFF.md](https://github.com/munder-sa/YLuncher/blob/main/docs/HANDOFF.md) で確認されている `sdsdk816` や `MainAiBox` 周辺を含め、GT8D内でCarPlay偽装（USB `05ac:12a8` / ワイヤレスiAP2＋AirPlay RTSP）を担うバイナリ・JNIライブラリを特定する（参考：[OMKE/wireless-carplay-virtual-cockpit](https://github.com/OMKE/wireless-carplay-virtual-cockpit)）。
2. **`persist.suding.*` 等の設定プロパティ抽出:** 特定したバイナリから参照されているシステムプロパティ・設定ファイルを列挙し、AltScreen（`type 111`）、複数 `View Area`、またはプロトコル互換モードを切り替えるフラグが存在するか確認する。
3. **RTSPネゴシエーション実態の確認:** 実車とCarPlay偽装で接続した際の `logcat`（必要に応じてネットワークインターフェース上のRTSP通信）から、Areneの `/info` 応答に含まれる `displays` に対してGT8Dが `type 111` を `SETUP` しているのか、それとも `type 110` のみ開いているのにArene側が勝手に黒画面にしているのかを切り分ける（[MHI2Q-CarPlay-AltScreen](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/README_EN.md)）。
4. **`modesChanged`（`TurnByTurn` オーナーシップ）制御口の探索:** CarPlay担当デーモンに外部（Binder / Unixソケット / Intent / ATコマンド）から `modesChanged` を送信させ、`appStates` の `TurnByTurn` オーナーを車載機（Accessory）側へ明示変更させるIPC口が存在するか静的解析で確認する（[Apple WWDC16 Session 723](https://developer.apple.com/videos/play/wwdc2016/723/)）。

## 4. 実車テスト結果に応じた全体判断マトリクス

| 実車テスト結果 | 技術的解釈 | 次のアクション（ファーム非改変範囲） |
| :--- | :--- | :--- |
| 有線AA接続時点で暗転しない | 暗転はワイヤレスCarPlay経路（[Apple WWDC19 Session 252](https://developer.apple.com/videos/play/wwdc2019/252/) のセカンダリストリーム等）固有の現象 | 有線/無線Android Auto運用を軸にするか、無線CarPlayが必要なら上記セクション3のCarPlayデーモン調査へ進む |
| 有線AAで暗転するが、`NAV_FOCUS_NATIVE`（単発または `PROJECTED` → `NATIVE`）で純正地図が復帰する | Arene側が制御チャネルmsg 13のフォーカス遷移でメーター描画元を純正ナビへ戻す実装になっている（[open-android-auto Navigation Channel 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md)） | [munder-sa/YLuncher](https://github.com/munder-sa/YLuncher) または常駐フックで投影確立直後に自動送信し、手動の4ステップ操作（[lucky Car Channel 公式note](https://note.com/lucky_car_ch/n/n32a4eeb045d4)）を完全自動化（無線AAでも同様に効くか確認） |
| 有線AAでも暗転し、フォーカス送信でも変化しない（No-op） | 車載機側が純正ナビからの自発的な案内開始（[RAV4 PHEV マルチメディア取扱説明書](https://manual.toyota.jp/rav4/3098/phev/ja_JP/contents/whl1746788804120.php)）しか受け付けないか、`DISPLAY_TYPE_CLUSTER` 接続確立に紐づいて固定されている（[open-android-auto Display Routing 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md)） | 採取した `logcat` から実車の `ServiceDiscoveryResponse` を確認し、`sdAutoReverse` / CarPlayデーモン双方でクラスタ機能広告を抑止できる `persist.*` プロパティの有無を調査 |

## 5. 主要リファレンス一覧

* **車両公式仕様:** [RAV4 PHEV 取扱説明書（マルチインフォメーションディスプレイ）](https://manual.toyota.jp/rav4/3098/phev/ja_JP/contents/vhch03se010403.php) / [マルチメディア取扱説明書（Apple CarPlay/Android Auto使用上の留意事項）](https://manual.toyota.jp/rav4/3098/phev/ja_JP/contents/whl1746788804120.php) / [マルチメディア取扱説明書（故障とお考えになる前に）](https://manual.toyota.jp/rav4/3098/phev/ja_JP/contents/xkq1759402158292.php)
* **Android Auto / AOSP仕様:** [open-android-auto Navigation Channel 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md) / [open-android-auto Display Routing 仕様](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md) / [Android for Cars DHU テストガイド](https://developer.android.com/training/cars/testing/dhu?hl=ja) / [AOSP Instrument Cluster API](https://source.android.com/docs/automotive/displays/cluster_api)
* **Apple CarPlay仕様・解析資料:** [Apple WWDC16 Session 723（TurnByTurn appState / modesChanged）](https://developer.apple.com/videos/play/wwdc2016/723/) / [Apple WWDC19 Session 252（Plug-in R15 / Second Screen H.264）](https://developer.apple.com/videos/play/wwdc2019/252/) / [CPSupportsInstrumentClusterNavigationScene](https://developer.apple.com/documentation/bundleresources/information-property-list/uiapplicationscenemanifest/cpsupportsinstrumentclusternavigationscene) / [MHI2Q-CarPlay-AltScreen（type 111 / iAP2 RGI構造）](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/README_EN.md) / [OMKE/wireless-carplay-virtual-cockpit（Suding製ドングル解析）](https://github.com/OMKE/wireless-carplay-virtual-cockpit)
* **実機挙動レポート:** [lucky Car Channel 公式note（復活手順）](https://note.com/lucky_car_ch/n/n32a4eeb045d4) / [lucky Car Channel 公式note（4機種比較）](https://note.com/lucky_car_ch/n/n99144259bcb3) / [lucky Car Channel 公式note（GT8D検証）](https://note.com/lucky_car_ch/n/n3b41e5108a04) / [Reddit r/rav4club](https://www.reddit.com/r/rav4club/comments/1sb1872/maps_in_the_digital_gauge_cluster_on_the_26/)