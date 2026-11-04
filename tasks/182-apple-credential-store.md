最初に `tasks/RULES.md` を読み、従う。

# Task 182 Apple接続情報の暗号化ストア

ユーザーは2026-11-04に[設計](../docs/superpowers/specs/2026-11-04-apple-token-retention-design.md)を了承し、実装開始を承認。工程1のみを担当する。Session/UIへの配線、WebView、依存/版の変更は後工程。実装担当は司令塔の権限を引き継がない。

## 新規作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleCredentialStore.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/AndroidAppleCredentialStore.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleCredentialStoreTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AndroidAppleCredentialStoreTest.kt`

既存ファイルは読み取りのみ。packageは既存lyrics。提供ZIPの実行/展開、実トークン/ADB/実通信/新依存の導入は禁止。テストは合成値だけ。

## 契約

- `enum class AppleCredentialSource { MANUAL, WEB }`。
- 通常のclass `StoredAppleCredential(val token: String, val storefront: String, val source: AppleCredentialSource, val verifiedAtEpochMillis: Long, val cookieExpiresAtEpochMillis: Long? = null)`。toStringは値を一切出さない固定文字列。data classにしない。
- `enum class AppleCredentialFailure { INVALID, STORAGE, KEY, CRYPTO }`。
- `sealed interface AppleCredentialReadResult` に `Missing`、`Ready(val credential: StoredAppleCredential)`、`Failed(val reason: AppleCredentialFailure)`。ReadyのtoStringは秘密を出さない。原因例外を保持しない。
- `interface AppleCredentialStore` に同期の `fun read(): AppleCredentialReadResult`、`fun write(credential: StoredAppleCredential): Boolean`、`fun clear(): Boolean`。呼出し側がIO dispatcherへ移す契約をKDocで明記。constructorではファイル/鍵を作らない。
- `EncryptedAppleCredentialStore` は同じインスタンスのread/write/clear全体を同じロックで直列化。後工程でAppGraphが単一インスタンスを共有する。クロスプロセス利用を追加しない。
- テスト差し込み用のinternal `AppleCredentialFile` (bounded read、atomic write、delete) と `AppleCredentialKeys` (existing/getOrCreate/delete) を設ける。細かなメソッドsignatureは報告する。ファイル不存在とI/O失敗を区別する。keysはjavax.crypto.SecretKeyだけ扱い、encodedで鍵を読み出さない。
- `AndroidAppleCredentialStore(context: Context)` は上記storeへ委譲。テスト用に鍵供給を差し替え可能とし、Android側のファイル処理もRobolectric+一時領域で検証する。公開UIへ秘密のDTOを渡す機能は追加しない。

## 暗号・形式

1. AndroidKeyStoreのaliasは `yluncher.apple_music.credentials.v1`。AES256、PURPOSE_ENCRYPT|DECRYPT、GCM、NoPadding、randomized encryption required、生体認証なし。StrongBox必須/平文フォールバックを追加しない。readでは既存鍵のみを取得し、鍵不在を新規生成で隠さない。write時だけ必要なら鍵を生成する。
2. `AES/GCM/NoPadding`。暗号化のinitにはIVを渡さずproviderが毎回生成した12byte IVを取得、128bit tag。復号はGCMParameterSpec(128, iv)。cipher parametersが期待に反する場合は失敗扱い。乱数固定/IV再利用をしない。
3. 外側形式: ASCII `YLAM` 4byte + version 1の1byte + IV 12byte + ciphertext/tag。AADは最初の5byte。全ファイル上限20KiB、最低サイズとバージョンを検証してから復号。未知版/改ざん/切断/余分データを許さない。
4. 平文payloadはbig endianで token長(Int)+UTF-8 token、storefrontのASCII2byte、source1byte(MANUAL=0/WEB=1)、verifiedAtEpochMillis(Long)、expiry有無1byte(0/1)、有の場合expiry(Long)。末尾の余りは拒否。長さ検査後だけallocationし、UTF-8 decoderは不正置換せずREPORT。tokenは非空/trim後と一致/ISO制御文字なし/UTF-8で16KiB以下、storefrontは[a-z]{2}、時刻は非負であることをread/write両方で検査する。Cookie期限をトークン有効期限とみなさない。
5. 不正入力はwrite=falseで、既存の保存値/鍵を変更しない。通常のファイル/暗号例外は固定分類またはfalseへ変換し、例外メッセージ/原因/平文/暗号文をログや例外に含めない。Error類を握りつぶさない。

## Android保存

- `File(context.noBackupFilesDir, "apple-music/credentials.v1")`。通常のfilesDir/config/SharedPreferencesへ書かない。Android Keystore初期化はread/write/clearで必要になるまで遅延する。
- android.util.AtomicFileのstartWrite/finishWrite/failWriteを使う。同期とスレッド排他を維持し、readFullyによる無制限読取を使わず20KiB+1の境界で拒否する。不存在のFileNotFoundを全I/O失敗と混同せず、存在して読めない場合はSTORAGE。
- finishWrite後にbounded readで今回の暗号文と一致することを照合してから成功を返す。失敗を保存成功と扱わない。検証のために平文をディスクへ出さない。
- clearはファイル本体とAtomicFileの付随ファイルを削除し、当aliasの鍵も削除する。他の鍵/ファイルを消さない。ファイル削除失敗でも鍵削除を試し、両方が消えた場合のみtrue。解除のUI完了や世代管理は後工程。鍵が消えてもファイルが残る場合はfalse、次回readは復元成功にならない。
- 読込み失敗時に破損ファイルを勝手に上書きせず、再認証後のwrite/明示clearで処理できるようにする。
- 同じインスタンスのclear後に古いwriteが完了しないことはストア直列化で検証。新しい呼び出しの世代判定は後工程でSession側に置く。

## 必須テスト

クラスは上記2つ。ケース名は以下を維持し、必要な追加ケースは可。期待条件を緩めない。

AppleCredentialStoreTest:
- `storesEncryptedAndRestoresMetadata`: 実JCE AES256鍵、store再生成で全metadata復元、暗号文に合成token/plaintextが含まれない、source/expiry有無。
- `usesDistinctProviderGeneratedIvs`: 同じ値を繰返し保存しIV/暗号文が異なる。両方復号できる。
- `rejectsTamperedTruncatedUnknownAndOversizedFiles`: header/version/IV/cipher/tag改ざん、切断、末尾追加、20KiB超過、malformed payload/UTF8/長さ/enum/時刻。合成fixtureは独立に暗号化して契約を検証。
- `validatesInputsBeforeChangingStorage`: token空/trim/制御文字/UTF8上限と境界/region/時刻。無効入力で既存値/鍵を変えない。
- `handlesMissingKeysAndStorageFailuresWithoutLeaking`: 鍵なしでreadが生成しない、別鍵/失敗、fileread/write/keysエラーに合成secretを含ませても結果/toString/logへ出ない。平文へfallbackしない。
- `serializesWritesAndClear`: ラッチによる実スレッド順序でwrite中clearを待たせ、最後のclear後はMissing、file/keys非並行。タイムアウトを設けフリーズさせない。
- `clearAttemptsBothResourcesAndReportsFailure`: file削除/鍵削除の片側失敗でももう片方実施、false、成功は両方消去。

AndroidAppleCredentialStoreTest (Robolectric、鍵だけfake):
- `storesOnlyInNoBackupDirectory`: Android backendを通した保存/再生成読取り、通常設定領域は未変更、constructorでファイル/鍵生成なし。
- `atomicFilesAreBoundedAndRemovedOnClear`: 原子的保存→再読込み、サイズ境界/読み取り失敗、clearで本体/付随ファイル削除、失敗時false。利用するAtomicFile挙動に必要な根拠は公式資料と照合。
- `androidKeyParametersMatchContract`: key生成のspec builderをテスト可能なinternal関数として検査し、alias/256bit/purpose/GCM/NoPadding/randomization/userauthfalseを確認。JVMのfakeをGT8DのKeystore実証と扱わない。

## 使ってよいコマンド

- 状態を変更しない読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleCredentialStoreTest' --tests 'com.gt8d.launcher.media.lyrics.AndroidAppleCredentialStoreTest'`

変更後1回、失敗修正時だけ再実行。既存テスト/assembleを追加実行しない。git更新/ADB/外部通信は禁止。

## 一次資料 (司令塔が確認済み)

- https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec (AES/GCMの例)
- https://developer.android.com/reference/android/util/AtomicFile (独自スレッド排他、start/finish/fail)
- https://developer.android.com/identity/data/autobackup (noBackupFilesDirは除外)

## 報告

変更、件数/失敗/エラー/スキップ、実行コマンド/XML、形式/同期/非出力の根拠、AndroidKeystoreの実機は未検証、決めたことを日本語で報告する。自己申告のみで完了にせず司令塔が差分/XMLと照合する。

## 実装・司令塔レビュー結果 (2026-11-04)

- `.codex-bak/20261104-160719/`、終了0、許可4ファイルのみ。指定コマンドは初回12件中1失敗、AES以外の鍵を実装側で明示拒否して2回目に12件成功(9+3)、失敗/エラー/スキップ0。期待値変更なし。司令塔はソース/テスト全文、実行ログ、XMLを照合し、再実行していない。
- allow警告1件は既知の引用符正規化の差。events.jsonlの実コマンドは指示書どおり引用符付き。実通信と実機Keystoreは未検証、Sessionへの配線は次工程。
- 決定: Encrypted store/file backendはinternal。file APIはreadBounded(Int):ByteArray? / writeAtomically(ByteArray):Boolean / delete():Boolean、keys APIはexisting():SecretKey? / getOrCreate():SecretKey / delete():Boolean。Androidの差込み口はinternal constructor(Context, AppleCredentialKeys)。specはinternal appleCredentialKeySpec()。最小ファイル50byte、形式=INVALID/I/O=STORAGE/鍵取得=KEY/暗号=CRYPTO。write時の不正Unicodeも拒否し一時平文バッファはゼロ化。fd.syncと保存後照合、不存在はNIO属性で判定。テストはRobolectric+実JCE、ラッチ/実スレッドの待機には上限あり。
