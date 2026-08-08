# 24hRecoder

Android 16 / Pixel 10aを初期基準端末とした24時間録音アプリです。

## 現在の実装

- microphone Foreground Serviceによるバックグラウンド録音
- 録音Serviceを`:recorder`専用プロセスに分離
- AudioRecord: mono / 16 kHz / PCM 16bit
- MediaCodec: AAC-LC / 32 kbps
- MediaMuxer: M4A
- 5分セグメント
- `START_STICKY`によるOSプロセス再生成時の復旧
- 起動意思・状態・heartbeatのファイル永続化
- 600 MB音声上限と古い音声のローリング削除
- `AudioRecordingCallback`による入力silenced検出ログ
- 再起動後の自動録音開始は行わず、再開通知のみ表示
- JSON Lines形式のイベントログ
- `whisper.cpp v1.9.1`をAndroid NDKで組み込んだ完全ローカル文字起こし
- 多言語Whisper `base`モデルを使用
- 5分M4AをAndroid MediaCodecでPCMへ復号し、端末内で推論
- WorkManagerで文字起こしを録音処理から分離し、低バッテリー時は延期
- 文字起こし結果をfsyncで永続保存してから元音声を削除

## ローカルWhisperモデル

初回のみアプリ画面から`ggml-base.bin`をダウンロードします。取得元はwhisper.cppが案内しているHugging Faceの`ggerganov/whisper.cpp`モデル配布先です。ダウンロード後はSHA-1を検証します。

モデルは`noBackupFilesDir/whisper/`へ保存し、24hRecoderの「作業データ1GB」論理上限には含めません。ただし端末の実空き容量監視からは除外しません。

文字起こし時に録音音声をOpenAI等の外部文字起こしAPIへ送信しません。モデル取得完了後は文字起こし自体にネットワーク接続は不要です。

## ビルド

Android SDK API 36、JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、NDK 27.0.12077973、CMake 3.22.1を使用します。ネイティブビルド時に`whisper.cpp v1.9.1`のソースを取得してarm64-v8a向けにビルドします。

```bash
gradle :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## 現在のバージョン

- versionName: `0.3.0-debug`
- versionCode: `3`
- minSdk: `29`
- targetSdk: `36`
- ABI: `arm64-v8a`

## 注意

Pixel 10a実機での5分音声の推論時間、24時間録音との同時運用時の電池消費・温度・メモリ使用量は実測が必要です。ローカル文字起こしが失敗しても録音処理を停止させず、元音声は保持します。
