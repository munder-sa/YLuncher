# Task 112: 「分割の行き先」を持つアプリ(GPT Live など)を分割で開けるようにする

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(実機で確認済み)
- ユーザーの自作アプリ GPT Live(`local.aibox.gptlive`)は、画面を持たないランチャー Activity(`Theme.NoDisplay`、`noHistory`)。起動すると `ACTION_VIEW https://chat.com/?mode=voice` を `com.openai.chatgpt/.ChatGptDeeplinkActivity` に `FLAG_ACTIVITY_NEW_TASK` で送り、すぐ `finish()` する。
- セット 4「Apple Music | GPT Live」(6:4)は、メーカーの分割入口(E)が右に GPT Live を置くが、GPT Live がすぐ消えて ChatGPT が別に前へ出るため、`waitBoth` がそろわず毎回 Safety(4 秒)で失敗し、3 回目からは LeftOnly になった(ログ `[Vendor] openSplit left=com.apple.android.music right=local.aibox.gptlive` → `retry skipped: progress` → `curtain hidden reason=Safety`)。
- 実機で、E に「`com.apple.android.music` | `com.openai.chatgpt`」(3:2)を渡し、分割ができてから `am start -a VIEW -d https://chat.com/?mode=voice -n com.openai.chatgpt/.ChatGptDeeplinkActivity` を送ると、**右の ChatGPT がそのまま音声モードになる**(分割は崩れない)ことを確認した。
- ユーザーの決定: GPT Live の manifest に「分割の行き先」を書き(済み)、YLuncher がそれを読んで分割で開く。今後の自作のショートカットのアプリにも使える仕組みにする。

## 取り決め(アプリ側の manifest。GPT Live 1.1 に書いたもの)
ランチャー Activity(`MAIN`/`LAUNCHER` を受ける Activity)の `<meta-data>`:
- `com.gt8d.launcher.split.PACKAGE`(必須): 分割で実際に置くアプリのパッケージ名。例 `com.openai.chatgpt`
- `com.gt8d.launcher.split.COMPONENT`(任意): 分割ができてから送る Intent の宛先 `パッケージ/クラス`(クラスは完全な名前、`.` で始まる短い形も受ける)。例 `com.openai.chatgpt/com.openai.chatgpt.ChatGptDeeplinkActivity`
- `com.gt8d.launcher.split.URI`(任意): 送る Intent の data。例 `https://chat.com/?mode=voice`

## 変更・作成するファイル(これで全部)
- 作成: `app/src/main/java/com/gt8d/launcher/apps/SplitRedirect.kt`
- 作成: `app/src/test/java/com/gt8d/launcher/apps/SplitRedirectTest.kt`
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`(`SetRunnerDeps` に渡す所だけ)
- `app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt`(行き先の Intent を送る関数を足す)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`(パネルから隣に開く所だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.apps.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.link.*' --tests 'com.gt8d.launcher.split.*'`

## 仕様
1. `SplitRedirect.kt`:
   - `data class SplitRedirect(val targetPkg: String, val component: ComponentName?, val uri: Uri?)`
   - `fun interface SplitRedirects { fun forPackage(pkg: String): SplitRedirect? }`
   - `class AndroidSplitRedirects(pm: PackageManager) : SplitRedirects`: `pkg` のランチャー Activity(`ACTION_MAIN` + `CATEGORY_LAUNCHER`、`setPackage(pkg)`、`GET_META_DATA`)の metaData を読む。`PACKAGE` が無い・空・`pkg` 自身なら null。`COMPONENT` の形が正しくない、または宛先のパッケージが `PACKAGE` と違うときは component を null とみなす(URI は使う)。`URI` は `Uri.parse`。例外(`NameNotFound` 等)は null。結果はパッケージごとにキャッシュせず毎回読む(数が少なく、アプリの更新に追従するため)。
   - 行き先のアプリが入っていないとき(`targetPkg` のランチャー Activity が無い)は null(=今までどおり元のアプリで開く)。
2. `SetRunnerDeps` に `redirects: SplitRedirects = SplitRedirects { null }` を足す。`AppGraph` は `AndroidSplitRedirects(app.packageManager)` を渡す。`ActivityLauncher` に `fun startRedirect(redirect: SplitRedirect, adjacent: Boolean): Boolean` を足す(既定の実装は false を返す。`AndroidActivityLauncher` は `ACTION_VIEW`+`uri`(uri が null なら `ACTION_MAIN`)、component があれば `setComponent`、無ければ `setPackage(targetPkg)`。フラグは `FLAG_ACTIVITY_NEW_TASK`、`adjacent` なら今の `startApp` と同じ `LAUNCH_ADJACENT | MULTIPLE_TASK` も。例外は false)。
3. `SetRunner.openSet`(分割で開く所):
   - 左右の `Slot.App` それぞれについて `redirect = d.redirects.forPackage(pkg)` を求め、**分割に渡すパッケージ**を `redirect?.targetPkg ?: pkg` にする。E への `launcher.open`、`waitBoth`(前面化の待ち・呼び直し・進み具合の判定)、`warm`、`isResizeable`、`_opening`、`lastOpenedPair`、パネルの相手(入れ替え保存の相手の比較)は、すべてこの「分割に渡すパッケージ」を使う。
   - 設定(セットの左右)は書き換えない。入れ替え保存(`PanelSwapSaver`)が設定に書く左右のパッケージは**元のパッケージ**(GPT Live)のまま。相手の比較だけ行き先で行う。
   - 入っているかの確認: 元のパッケージが入っていなければ今どおり「見つかりません」。元が入っていて行き先が入っていなければ redirect は null(1 で済む)。
   - **分割がそろった後**(`waitBoth` の ready。幕あり・起動時の幕なしの両方)に、redirect のある側について `d.apps.startRedirect(redirect, adjacent = false)` を **1 回だけ**送る(component も uri も無い redirect では送らない)。呼び直し・取り消し・失敗・時間切れのときは送らない。送った・送れなかったを `DiagLog.d("SetRunner", ...)` に残す。
   - 分割が使えず全画面に落ちるとき(`fullscreen`・`waitSingle`・LeftOnly・片方が無い等)は、**元のパッケージ**を今どおり開く(GPT Live を全画面で開けば自分で ChatGPT の音声モードへ進むため)。待つパッケージ(`waitSingle`)は、元のパッケージに redirect があれば行き先のパッケージにする(GPT Live はすぐ消えるので、前面化するのは ChatGPT)。
   - 幕の配置図・名前・通知帯の文言は元のアプリ(GPT Live)の名前・アイコンのまま。
   - リンクの分割(`openForLink`)は対象外(ブラウザ側に redirect は無い前提。今の動きを変えない)。
4. パネルから隣に開くとき(`PanelActivity.onLaunchApp`、分割の中で `adjacent = true`): redirect があれば `startRedirect(redirect, adjacent = true)` を使い、成功したら `AppGraph.runner.panelLaunched(redirect.targetPkg)`、`warm.recordAppLaunch(redirect.targetPkg, …)`。redirect が無ければ今どおり。分割の外(全画面)では今どおり元のアプリを開く。component も uri も無い redirect で隣に開くときは `startApp(targetPkg, adjacent = true)` を使う。

## テスト(足す。既存は弱めない)
- `SplitRedirectTest`(Robolectric の ShadowPackageManager で ActivityInfo に metaData を付ける): 3 つそろう → 全部入る。`PACKAGE` だけ → component・uri null。`.` で始まるクラス名 → 完全な名前になる。宛先のパッケージが違う `COMPONENT` → component null、uri はある。`PACKAGE` が自分自身・空・無し → null。行き先が入っていない → null。
- `SetRunnerTest`(今の偽物の書き方に合わせる):
  - 「Apple Music | GPT Live」で、E に `music | chatgpt` が渡る。両方の前面化(chatgpt)で ready になり、ready の後に `startRedirect` が 1 回だけ、そのとき送られる。ready の前には送られない。
  - 「パネル | GPT Live」(右がアプリ、左がパネル)と「GPT Live | パネル」でも、E・待ち・ready 後の 1 回の送信が同じ。入れ替え保存が設定に書くのは `local.aibox.gptlive`。
  - 時間切れ(Safety)・取り消し・E の失敗では送られない。呼び直しでも 2 回目は送られない。
  - LeftOnly・行き先が入っていない(redirect null)では、今どおり元のパッケージが開く・E に元のパッケージが渡る。
  - redirect が無いセットの今の動き(既存のテスト)は変わらない。
- `PanelActivityTest`: 分割の中のパネルから GPT Live を押すと `startRedirect(…, adjacent = true)` が呼ばれ、`panelLaunched` の相手が `com.openai.chatgpt`。全画面のパネル(分割の外)では元のパッケージを `startApp`。

## この Task でしないこと
- 設定データの形・セットの編集画面・リンクの分割の変更。GPT Live のソースの変更(済み)。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- **変更してよいファイルを追加**: `app/src/main/java/com/gt8d/launcher/sets/PanelSwapSaver.kt`、`app/src/test/java/com/gt8d/launcher/sets/PanelSwapSaverTest.kt`、`app/src/test/java/com/gt8d/launcher/split/AndroidActivityLauncherTest.kt`(既存)。
- **入れ替え保存**: `PanelSwapSaver` は「設定のスナップショット(元のパッケージのセット。編集の検出と保存に使う)」と「相手の比較に使う実際のパッケージ(行き先)」を別に持つ。`begin(set, partner)` の partner は行き先(chatgpt)、保存するセットは元(gptlive)のまま。パネルから別のアプリを開いたら保存しない今の抑止は残す。テスト: パネル | GPT Live で入れ替え → 設定には `local.aibox.gptlive` が左右反転で保存される。パネルから別のアプリを開いた後は保存しない。
- **元と行き先の使い分け(全部の退避の道で)**: 起動(全画面で開く `startApp`)・通知の名前・`warm.recordAppLaunch` は**元のパッケージ**。待つパッケージ(`waitSingle`)・E に渡す・`waitBoth` の判定・`_opening`・`lastOpenedPair` は**行き先**。対象: 最初の E の失敗、呼び直しの失敗(`handleLaunchFailure` に渡す)、LeftOnly、分割できないアプリ、片方が入っていない、パネルの中継が無い。`handleLaunchFailure`・`fullscreen` に「開く元」と「待つ行き先」を別に渡せるようにする。`warm.markForeground` は ready のとき行き先に。
- **送る順番**: `waitBoth` の ready の共通の分岐の中で、タイマーを止めた後、`curtain.ready()` / `finishWithoutCurtain()`(保留中の次のセットを始めうる)**より前**に送る。左右両方に redirect があれば左 → 右の順。片方の送信に失敗してももう片方は送り、ログに残す(失敗で分割を崩さない)。
- **回数**: 呼び直しを行った時点では送らない。呼び直しの後に ready になったら、その「開く操作」全体で各側 1 回だけ送る。ユーザーが改めてセットを開けば、また 1 回送る。幕なし(起動時)の ready でも同じ。幕なしの 10 秒の時間切れ・`abortBootToHome`・取り消し・Safety では送らない。テストに: 幕なし ready で 1 回、幕なしの時間切れで 0 回、次のセットが保留中の ready で「送信 → 次のセットの E」の順。
- **リンク**: `openForLink` から来た `openSet` では、左右とも redirect を求めない・送らない(今の動きのまま)。通常のセットの ready の後の `lastOpenedPair` が `(music, chatgpt)` のように行き先で左右の順を保つことをテストする。
- **同じ行き先**: 左右の分割に渡すパッケージが同じになる場合(両方とも同じ行き先、または片方が行き先と同じ元のアプリ)は、redirect を使わない(両側とも元のパッケージで今どおり開く)。
- **複数のランチャー Activity**: `queryIntentActivities` の結果のうち、metaData に `PACKAGE` を持つ最初のものを使う。`COMPONENT` は `パッケージ/クラス` の 1 つの `/` で分け、どちらも空でなく、パッケージが `PACKAGE` と同じときだけ受ける(`ComponentName.unflattenFromString` の結果を確かめる形でよい)。
- **テストの偽物**: `SetRunnerTest` の `TestEnv` に `redirects` の差し替え口と、`startRedirect` の呼び出し(引数・adjacent・順番)を記録し戻り値を変えられる偽物を足す。`Uri`・`ComponentName` を使うテストのため Robolectric が要るなら、そのテストだけ `@RunWith(RobolectricTestRunner::class)` の別クラスにしてよい(既存のテストクラスの実行方法は変えない)。入れ替え保存のテストは `scope` を渡して saver を作る。
- **パネル**: `PanelActivity` に `internal var redirects: SplitRedirects = AndroidSplitRedirects(packageManager)`(作成時に初期化。テストで差し替え)と `internal var launchRedirect: (SplitRedirect, Boolean) -> Boolean` を足す。隣に開くとき `launchRedirect` が false なら、元のアプリを今どおり `launchApp(pkg, true)` で開き直す。`warm.recordAppLaunch` は今どおり失敗でも呼ぶ(パッケージは実際に開こうとしたもの)。アプリ一覧からの component の起動は対象外(今のまま)。
- **AndroidActivityLauncherTest**: `AndroidActivityLauncher.startRedirect` が組み立てる Intent(action・data・component/package・フラグ、adjacent の有無)を Robolectric の `nextStartedActivity` で確かめる。
