最初に tasks/RULES.md を読み、従う。

# Task 167: アプリ情報を裏で取って共有、キャッシュの失効を絞る(v2.2.0 負荷軽減)

計画 `docs/superpowers/plans/2026-11-04-perf.md` の **Task 167** の Files・Interfaces・Step をそのまま実施する。設計書 `docs/superpowers/specs/2026-11-04-perf-design.md`(末尾の「ユーザーの決定」を含む)。v1.7.1・v2.0 の作り(描画の層で動かす、組み立て直し・再レイアウトを起こさない、measure・layout で状態に書き込まない、リリース版は画面をユーザー補助に「パネル」1つ)を壊さない。既存テストは弱めない(仕様の変更に伴う期待値の調整は報告する)。

## 変える・作るファイル
- `tasks/167-app-catalog-state.allow` に書いたものだけ。足りなければ止めて報告する。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `tasks/167-app-catalog-state.allow` の command に書いたものだけ(一字一句そのまま)。

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- `PanelEnvironmentTest.kt`(271 行付近)など、「package のイベントを送らずに、復帰だけで一覧・アイコンが更新される」ことを求める既存テストは、**非表示の間に package のイベント(ADDED/REMOVED/CHANGED など)を送ってから復帰する**形に直してよい。一覧・ラベル・アイコンの検証の中身は保つ。同じ理由で食い違うテストが他にもあれば同様に直してよい(すべて報告する)。

## 追記2(codex の質問への回答)
- `app/src/main/java/com/gt8d/launcher/settings/ListsScreen.kt`(141 行付近)も、共有の所有者(AppCatalogState)と分けた revision へ**移す**。互換経路は残さない。関連テストは無いので、必要なら `app/src/test/java/com/gt8d/launcher/settings/ListsScreenTest.kt` を新しく作ってよい(検証の中身は保つ。報告する)。テストのコマンドに settings を足した。

## 追記3(codex の質問への回答。追記2 を取り消す)
- **設定画面(`app/src/main/java/com/gt8d/launcher/settings/` の中。`ListsScreen.kt`・`SettingsScreen.kt` など)は今回の対象外**。移さず、今の取り方のまま残す。そのため `AppCatalog` の今の公開の呼び方(同期の取得・キャッシュ)は互換のために残す(ホーム・パネルの表示経路だけを新しい共有の所有者へ移す)。設定画面は開く回数が少なく、今回の目的(ホーム・パネルの引っかかりを減らす)に効かないため。
- `settings/` のファイルとテストは変えない。設定画面のテストが今どおり通ることを、下のコマンド(settings を含む)で確かめる。
- これ以外にも許可外のファイルに移行の対象が見つかったら、**表示経路(ホーム・パネル)に関わらないものは対象外**として先へ進み、報告に書く(止まらない)。表示経路に関わるものだけ止まって報告する。

## 追記4(まとめての許可。以後この理由では止まらない)
- この Task の設計(package の受け取りは共有の所有者が非表示の間も続け、`close` で解除/画面側は無線などの受け取りだけを画面の寿命で解除/アプリ一覧と無線の revision の分離/一覧・アイコンの裏での取得とそのタイミング)と食い違う**既存テストは、確かめる中身(解除されること・更新されること・表示される値)を保ったまま、新しい仕様に合わせて直してよい**。例: `PanelEnvironmentTest.environment_dispose_unregistersObserversAndReceivers` は「画面側の無線の受け取りは破棄で解除」「共有の所有者の package の受け取りは続き、`close` で解除」をそれぞれ確かめる形にする。
- 直した既存テストは、名前と変更の内容を**すべて**報告する。設計そのものに反する変更(例: 復帰のたびの全取得に戻す)が要る場合だけ止まる。
