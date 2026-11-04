最初に `tasks/RULES.md` を読み、従う。

# Task 184 Music TokenのCookie/URL解析の移植

ユーザー了承済みの組み込み取得画面に必要な純計算だけを実装する。現在の作業場所は隔離worktree `/tmp/yluncher-apple-token-web`、ブランチ `feat-apple-token-web`。主作業場所では別担当がTask 183のSessionを実装中。主作業場所を変更しない。司令塔の権限を引き継がない。

## 新規作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleWebTokenLogic.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleWebTokenLogicTest.kt`

他のファイル/版/依存/UI/Sessionは変更しない。WebView初期化/実通信は行わない。提供ZIPは下記の読み取りコマンドで2ソースだけ読む。アーカイブのスクリプトを実行しない。

## 契約

- `object AppleWebTokenLogic`、元のJava TokenLogicに対応する `findToken(cookieHeader: String?): String?` / `expirationFromSetCookie(header: String?, expectedToken: String?): Long?` / `isAppleHttpsUrl(url: String?): Boolean` / `originLabel(url: String?): String` をKotlinで提供。packageは既存lyrics。
- 元のテストの21の入力/期待値をそのままJUnitに引き継ぐ。UIやコピー機能は移植しない。
- media-user-tokenというCookie名の完全一致、最初の=以降を保持、前後spaceのtrim、空なら候補にしない。+/%/=をdecode/再encodeしない。複数の同名Cookieは元と同じ最初の有効候補を使う。
- 強化: header UTF-8上限64KiB、token UTF-8上限16KiB、制御文字(ISOControl)と不正Unicodeは拒否。制御文字検査はtrim前に行い、先頭末尾の改行等をtrimで隠さない。容量検査は先に文字数で大きすぎる入力を拒否し、巨大な配列を作らない。
- expiryは元の3書式を保持しlenient=false/Locale.US/GMT/full-consumptionで解析。対応する同一tokenに対するExpiresだけを扱う。Max-Ageからnow+期間を作らない。日付が不明/負値ならnull。Cookie期限はメタデータで、サーバー側token有効期限の保証にしない。
- URLは最大8192文字、HTTPS、apple.comまたは厳密な .apple.com サブドメイン、userinfoなし、port未指定/443。scheme/hostの大小は許す。他scheme/他host/不正URL/制御文字はfalse。about:blankはここではfalseとし、将来のActivityで信頼済みpopup初期状態だけ扱う。URLのパス/query/fragmentはoriginLabelへ出さない。
- originLabelは許可URLの `https://` + 小文字host、それ以外は固定 `Apple Music`。不正URL/例外をログ・例外メッセージに含めない。
- アプリで使う公開APIは上の4つだけ。必要な定数/補助はprivate/internal。秘密を保持するdata classや診断ログは追加しない。

## テスト

- `preservesMusicTokenOriginalChecks`: 提供TokenLogicTest.javaの21入力/期待値を維持。独立期待値として日時1809308444000Lをそのまま照合。
- `rejectsControlsMalformedUnicodeAndBounds`: 全ISOControlの境界/内部、UTF8の16KiB境界(ASCII/多byte)、header64KiB境界と超過、不正surrogate、前後trim、URL8192境界。
- `keepsOpaqueValuesAndExactCookieNames`: +/%/==、大小・部分一致名拒否、最初の有効同名、=なし、空/不在。decodeしない。
- `expiryUsesOnlyMatchingCookieAndStrictCompleteDates`: 3書式、余分な文字/不正日付/別token/MaxAgeのみ/負値の拒否、未知はnull。2000年代後半/未来/過去の正常metadata。
- `navigationRejectsLookalikesAndUnsafeOrigins`: 紛らわしいsuffix/prefix、userinfo/ポート/http/intent/javascript/file/data/about/Unicode/不正/制御文字、正当なApple子hostと443、大文字、originLabelにquery/fragment/パスの秘密がない。

実通信なしで合成値のみ。テストの条件/元21ケースを緩めない。

## 使ってよいコマンド

- 状態を変えない読み取りコマンド。
- `python3 -c 'from zipfile import ZipFile; z=ZipFile("/home/munde/.codex/attachments/7d3a9c1a-fe55-4572-ba63-4c88f6aa957f/MusicToken-source.zip"); print(z.read("MusicToken/src/local/musictoken/TokenLogic.java").decode()); print(z.read("MusicToken/test/local/musictoken/TokenLogicTest.java").decode())'`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleWebTokenLogicTest'`

指定テストは変更後1回、失敗修正時だけ再実行。他テスト/assemble/Git更新/ADB/外部アクセスはしない。

## 報告

日本語で変更、元21ケースの対応、追加境界、テスト件数/失敗/エラー/スキップ、実行コマンド/XML、決めたことを報告。WebView実動作は今回の検証対象ではない。

## 実装・司令塔レビュー結果 (2026-11-04)

- 隔離worktree `.codex-bak/20261104-162739/`、終了0、許可2ファイルのみ。指定Gradleを1回実行し5件成功・失敗/エラー/スキップ0。元の21入力/期待値/順序を保ち、追加で制御文字65種、UTF8容量、不正Unicode、期限の完全解析、遷移先/origin表示を検証。司令塔は全文、元ソース、XML、実コマンドを照合。再実行なし。
- allow警告はPython/Gradleの引用符正規化の2件。実コマンドは指定どおりで、PythonはZIP内の指定2ソースを表示しただけ。WebView実機は未確認。
- 決定: trimはASCII spaceのみで元のJava互換(制御文字はtrim前に拒否)。header内の不正文字は全体を拒否。UTF8バイト数は配列なしで計数しCookie分割は遅延処理。超過する同名Cookie候補は飛ばし最初の有効候補を採用。日付parserは毎回生成し引数例外はnull。URL判定/origin表示の共通host解析はprivate。境界は固定期待値と合成値で検証。
