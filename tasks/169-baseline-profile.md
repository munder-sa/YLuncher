最初に tasks/RULES.md を読み、従う。

# Task 169: profileinstaller と手書きのベースラインプロファイル(v2.2.0 負荷軽減)

計画 `docs/superpowers/plans/2026-11-04-perf.md` の Task 169 を、次の形に絞って行う(司令塔の決定、2026-11-04)。ユーザーは依存の追加を承認済み。

## 決定(計画からの変更)
- WSL の Gradle から実機(GT8D)に adb で届かないため、macrobenchmark のモジュールと baselineprofile のプラグインは**入れない**(実機での自動生成はしない)。
- 入れるのは **`androidx.profileinstaller:profileinstaller`** の1つだけ(Compose などのライブラリに同梱のプロファイルを端末で効かせる)。版は、今の Compose BOM(2025.01.00)・AGP(8.7.3)・minSdk 35 で使える安定版を選ぶ(選んだ版と理由を報告)。Gradle の取得はネットワークが要る(codex-run の NETWORK=1 で可)。
- アプリ自身のよく通る部分は、**手書きのベースラインプロファイル** `app/src/main/baseline-prof.txt` で足す。対象(クラス・メソッドの規則は ART のプロファイルの形式。ワイルドカード可): ホームとパネルの表示(`com/gt8d/launcher/ui/home/**`、`ui/panel/**`、`ui/layout/**`、`ui/theme/**`)、歌詞(`media/lyrics/**`、`ui/home/Lyric*`)、再生情報(`media/**`)、`AppGraph`、`apps/AppCatalog*`。起動の流れの `LauncherApp`・`HomeActivity`・`PanelActivity`。規則は `HSPL` を基本にする。形式の例と根拠(公式文書の URL を書くだけでよい)を報告する。
- R8 の後でもプロファイルが効くこと(AGP は baseline-prof.txt を R8 の名前の付け替えに合わせて書き換える)。リリース版のビルドで、APK の中に `assets/dexopt/baseline.prof` があることを確かめる(`unzip -l` で中身の一覧を見る)。

## 変える・作るファイル(これ以外は変えない)
- `gradle/libs.versions.toml`、`app/build.gradle.kts`(依存の追加だけ)
- 作る: `app/src/main/baseline-prof.txt`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:assembleRelease`
- `unzip -l app/build/outputs/apk/release/app-release.apk`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.config.*'`

## 報告
- 選んだ版と理由、APK に `assets/dexopt/baseline.prof`(と `baseline.profm`)があるか、ビルドの結果、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- profileinstaller は手元のキャッシュにある **1.3.1** を使う。許可されたコマンド(`--offline` なし)のままでよい。Gradle は依存をローカルのキャッシュから解決する(codex-run の NETWORK=1 は Gradle のデーモンのためのもので、許可されている)。もしキャッシュに無くて取得に失敗したら、そのエラーを報告して止まる。
- 参考: 今の(profileinstaller 無しの)リリース版の APK にも、ライブラリ同梱の `assets/dexopt/baseline.prof`(7484 バイト)と `baseline.profm` が既に入っている。手書きの `baseline-prof.txt` を足したあと、このファイルが大きくなる(アプリの規則が入る)ことを確かめる。
