# Task 098: アプリのアイコンを案 C にし、アプリの名前を「YLuncher」に変える

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの判断 2026-10-31)
- アイコンは `build/mock/app-icon-mock.html` の **案 C(Driver Display)** に決めた(最初に D と伝えたが、画像を見て C に変更)。横長の角丸の画面の枠・仕切りの縦線・時計を思わせる2本の短い線。本体は枠と線だけ、中継アプリ(`panelhost`、`com.gt8d.launcher.panel`)は仕切りの右側(時計の側)を白い面にして、2本の線を背景の色で抜く。背景は `#17171D`。SVG(108x108 の viewport):
  - 本体: `<rect x="24" y="35" width="60" height="38" rx="7" fill="none" stroke="#FFF" stroke-width="5"/><path d="M60 36V72" fill="none" stroke="#FFF" stroke-width="5"/><rect x="67" y="47" width="4" height="14" rx="2" fill="#FFF"/><rect x="75" y="47" width="4" height="14" rx="2" fill="#FFF"/>`
  - 中継: `<rect x="24" y="35" width="60" height="38" rx="7" fill="none" stroke="#FFF" stroke-width="5"/><path d="M60 35H77Q84 35 84 42V66Q84 73 77 73H60Z" fill="#FFF"/><rect x="67" y="47" width="4" height="14" rx="2" fill="#17171D"/><rect x="75" y="47" width="4" height="14" rx="2" fill="#17171D"/>`
- 中継アプリには今アイコンの指定が無い(標準のアイコンになっている)。
- アプリの名前: 本体「YLuncher」、分割のパネルの画面(PanelActivity)「YLuncher パネル」、中継アプリ「YLuncher パネル」。帯の常駐通知のチャンネル名「YLuncher」、本文「YLuncher 動作中」。**ログのタグ `GT8D` は変えない**(場面テストが使う)。パッケージ名も変えない。

## 変更・作成するファイル(これで全部)
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/gt8d/launcher/edge/EdgeStripService.kt`(通知の名前と本文の定数だけ)
- create `panelhost/src/main/res/drawable/ic_launcher_foreground.xml`
- create `panelhost/src/main/res/drawable/ic_launcher_background.xml`
- create `panelhost/src/main/res/drawable/ic_launcher_monochrome.xml`
- create `panelhost/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `panelhost/src/main/AndroidManifest.xml`
- `panelhost/src/main/res/values/strings.xml`
- その他、`"GT8D Launcher"`・`"GT8D パネル"` を利用者に見せる文字として使っている所があれば、報告して止まる(勝手に増やさない)。テストがその文字を期待していれば、そのテストの名前を報告する。

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh assembleDebug :panelhost:assembleDebug`
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.edge.*'`

## 仕様
1. vector drawable(108dp・viewport 108)に SVG を移す。`rect` の角丸は path(`M…A…` または `Q`)で描く。stroke の枠は `strokeColor`・`strokeWidth` で(塗り無し)。背景は `#FF17171D` の単色。monochrome は前景と同じ形の白(Android 13 のテーマアイコン用。中継の抜きの線は monochrome では穴として表現するか、省いてよい)。
2. 本体の `mipmap-anydpi-v26/ic_launcher.xml` は今のまま(背景・前景・monochrome)。中継アプリにも同じ形の `ic_launcher.xml` を作り、マニフェストの `<application android:icon="@mipmap/ic_launcher">` を足す。minSdk は 35 なので PNG の mipmap は要らない。
3. 名前: 本体のマニフェストの `android:label="GT8D Launcher"`(application と、ホームの activity)→「YLuncher」、PanelActivity の「GT8D パネル」→「YLuncher パネル」。中継の `app_name` →「YLuncher パネル」。`EdgeStripService` の `CHANNEL_NAME` →「YLuncher」、`NOTIFICATION_TEXT` →「YLuncher 動作中」。
4. `assembleDebug :panelhost:assembleDebug` が通ること。

## この Task でしないこと
- パッケージ名・ログのタグの変更。画面の中のパネルのアイコン(`PanelIcon`)の変更。git のコミット等。

## 報告
変更点、ビルドの結果、名前を変えた所の一覧、**決めたこと**。
