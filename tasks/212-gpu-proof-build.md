最初に tasks/RULES.md を読む。

# Task212 GPUプローブのビルド確認

Task208の続き。あなたは実装担当。作成済み5ファイルをビルドして、コンパイル/プローブの実装ミスだけ直す。663ケース/旧方式/1/255閾値/全画素/文字条件を維持。YLuncher本体には配線しない。既存失敗Shaderテストは触らない。

- 実機の長い検証中に進捗が分かるよう、25ケースごとと最後にInstrumentation.sendStatusでcompleted/pass/fail/maxDelta/lastCaseを数値出力する。最終casesJsonと全結果は維持。ログへ本物のメディア/認証値は出さない。
- 画素差は全ケース続行。もし初期化失敗または同じ描画環境timeoutが連続する場合はfatalとして停止できるが、未実行数を明記し失敗終了する。テストをskip扱いや成功扱いにする変更は不可。
- 新規依存/版/ADB/Git操作なし。下記ビルド1回、実装修正を伴う失敗再実行のみ可。環境の失敗なら報告して止まる。

許可コマンド:
`/home/munde/tools/gradle-run.sh --offline -p tools/renderprobe -Pandroid.useAndroidX=true assembleDebug`

成功後再実行なし。対象5ファイルの差分/決めたこと/未実機確認を報告する。
