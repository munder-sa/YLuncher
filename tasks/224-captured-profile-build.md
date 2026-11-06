最初に `tasks/RULES.md` を読み、従う。

# Task 224: 実操作で収録したプロファイルを入れた比較用ビルド

あなたは実装担当。ユーザー承認済みの「実操作プロファイル」候補(Task221 の続き)。司令塔が実機で、今の手書き12行の baseline-prof.txt(対照 = 既存の 2.3.2-beta4 APK)と、収録したプロファイル(候補 = この Task のビルド)を交互に入れて起動時間を比べる。採否は司令塔が決める。この差分はまだ本採用ではない。

## やること

1. `app/src/main/baseline-prof.txt` の中身を、`build/perf-next/profile-capture/captured.txt`(司令塔が実機から収録した `pm dump-profiles --dump-classes-and-methods` の出力、20,706行)の中身で**丸ごと置き換える**。行の加工・並べ替え・追加・削除はしない(既存12行も残さない)。改行コードは LF。
2. 版は変えない(2.3.2-beta4 / 55 のまま。対照と同じ版で交互に上書きするため)。他のファイルは変えない。
3. 下のコマンドで本体の release を1回ビルドする。成功したら再実行しない。中継(panelhost)はビルドしない。
4. ビルドのログに出た baseline profile / ART profile 関係の警告(解決できない規則など)があれば、件数と代表例を報告する。直そうとしない。

## 作る・変えるファイル
- `app/src/main/baseline-prof.txt`(置き換え)

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:assembleRelease

テスト・ADB・Git は使わない。

## 報告
- 置き換え後の行数と、`captured.txt` との一致の確認方法(例: `cmp` の結果)
- ビルドの結果と時間、出力 APK のパス
- profile 関係の警告の件数と代表例
- 決めたこと
