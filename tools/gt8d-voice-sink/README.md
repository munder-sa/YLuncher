# GT8D 音声キー抑止 v0.1.0

Android標準の音声検索だけを受け取り、画面・マイク・音声認識を起動せず終了する独立アプリ。
GT8D純正サービスの `GPTLive → ChatGPT` 呼び出しは変更しない。YLuncher本体も変更しない。

## 有効にする

1. APKをインストールし、アプリ一覧から「GT8D 音声キー抑止」を開く。
2. 「通常時の受け口を選ぶ」から、同名アプリを選択し「常時」を押す。
3. 「ロック中の受け口を選ぶ」でも同じ設定をする。
4. 両方が「抑止アプリ」と表示されることを確認する。

音声検索を受けるActivityと、設定を見るActivityは別々。
受け口は `Theme.NoDisplay` と `finish()` で画面を出さずに終了する。
受信したアクションの回数と最終受信時刻だけを、アプリ内に記録する。
Intentの入力文字・音声・認証情報・その他のextrasは読み取らない。
アプリはマイク・ネットワーク・root権限・常駐サービスを使わない。

## 解除する

設定画面の「このアプリの既定指定を解除」で、自分の既定指定だけを解除する。
解除後はGoogleとこのアプリの選択画面に戻る。Googleを選んで「常時」にすればGoogle側へ戻せる。
このアプリをアンインストールする方法もある。

Googleアプリや `HandsFreeActivity` の無効化、既定アシスタントの変更はしていない。

## 対象となる呼び出し

- `android.speech.action.WEB_SEARCH` — 通常時の標準音声キー経路。
- `android.speech.action.VOICE_SEARCH_HANDS_FREE` — ロック中の標準音声キー経路。

**この2つのアクションを使う他アプリの音声検索も受け流す。** 車のボタンだけに限定するものではない。
Google/Geminiの別の起動経路は抑止しない。

GT8Dの実機では、Android標準の音声キーが上記のIntentを送ることを、`services.jar` の処理とログで確認した。
既定アシスタントを「なし」にする方法とは異なる経路。
Googleの受け口だけを消す方法は、標準側に起動先不在時の例外処理が見つからなかったため採用しない。
Googleを有効なまま残し、Androidの通常の選択画面で本アプリを既定にする。

**長押しでGT8D純正サービスがGPTLiveを2回呼ぶ問題は残る。** 本アプリは標準側の起動競合だけを抑止する。

## ビルド

Windows、Android SDK platform 35 / build-tools 36.0.0、Android Studio付属JBRを使う。
外部依存の追加・Gradleは不要。

```powershell
& .\tools\gt8d-voice-sink\build.ps1
```

成果物: `build/GT8D-Voice-Key-Sink-0.1.0.apk`。
`-AndroidSdk`、`-SigningKeyDirectory`でSDKと開発署名鍵の保存先を指定できる。
鍵はリポジトリ外のCodex用書き込み可能フォルダーに保持する。APK更新時は同じ鍵を使用する。

## 検証 2026-11-08

実機: GT8D 5G / Android 15 / `SC162-V06.000.002`、USB ADBシリアル `12345678`。

1. `build.ps1` — ビルド成功、APK v3署名検証成功。
2. `adb install -r .../GT8D-Voice-Key-Sink-0.1.0.apk` — Success。
3. `aapt2 dump permissions ...apk` — 使用許可の宣言なし。
4. `verify_device.py <adb.exe>` — 通常音声検索・ロック中の経路・対象外アクションの3場面で、計数と元画面への復帰を確認。アシスタント設定も変更なし。
5. `configure_device.py <adb.exe>` — Androidの選択画面で通常時・ロック中とも既定指定し、`resolve-activity`で本アプリへの解決を確認。
6. `verify_keys.py <adb.exe>` — 下記の実音声キー検証が成功。
7. `verify_reset.py <adb.exe>` — アプリ内の解除ボタンで2アクションとも選択画面へ戻ることを確認し、再び本アプリを既定に指定。

| ADB操作 | 標準側で受け流した回数 | Google/Gemini起動要求 | 純正側GPTLive呼び出し | ChatGPT呼び出し |
|---|---:|---:|---:|---|
| `input keyevent KEYCODE_VOICE_ASSIST` | 1 | 0 | 1 | 確認 |
| `input keyevent --longpress KEYCODE_VOICE_ASSIST` | 1 | 0 | 2 | 確認 |

Googleアプリは有効。`assistant` と `voice_interaction_service` の設定も元のGoogleのまま。
画面は `build/installed-screen.png`、機能検証結果は `build/explicit-tests.json` と `build/key-tests.json`。
設定画面の見た目を確認済み。最終状態は両アクションとも本アプリが既定で、抑止有効。
APK SHA-256: `42F181D904D68F7169FA00A298FCDEA2311988A7A45C1DC4A46D4366980725D3`。
実車ボタン、実際に端末をロックした状態、再起動後・Googleアプリ更新後の保持は未確認。

初回の明示呼び出し検証は、未作成カウンターファイルに対する `adb exec-out` の戻り値が0になることで読取に失敗。
「ファイルなし」の標準出力も判定するよう検証コードを修正して再検証した。合格条件は変えていない。

## 決めたこと

- `tools/gt8d-voice-sink` の独立アプリ。パッケージは `com.gt8d.voicesink`、表示名は「GT8D 音声キー抑止」、開発版0.1.0 / 1。
- Android 15用としてmin/target SDK 35。Java標準画面で実装し、外部依存を追加しない。
- 上記2アクションだけを既定の受け口で受ける。キーの横取り、GPTLive起動、Googleパッケージの無効化はしない。
- 受け口は無表示ですぐ終了。設定画面は別Activityとしてアプリ一覧から開く。
- 音声・Intentの入力内容は読まず、アクション別の受信回数と最終時刻だけを非公開SharedPreferencesに保存する。
- 既定指定はAndroidの通常の選択画面で行い、解除は自分の既定指定だけに限定する。
- root・音声・ネットワーク権限・常駐機能を追加しない。
- 開発署名鍵はリポジトリ外に保持。Git操作・外部公開はしない。
- 実機は通常時・ロック中の両方で受け流し有効にして引き渡す。長押し時の純正側重複呼び出しは本件の対象外とする。
