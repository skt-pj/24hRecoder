# 24hRecoder

Android 16 / Pixel 10aを初期基準端末とした24時間録音・文字起こしアプリです。

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
- OpenAI Audio Transcriptions APIによる5分セグメント文字起こし
- `gpt-4o-mini-transcribe`を初期文字起こしモデルとして使用
- WorkManagerによるネットワーク待ち・再試行・重複排除
- OpenAI APIキーをAndroid Keystoreで暗号化して端末内保存
- 文字起こしJSONを永続保存してから元音声を削除
- JSON Lines形式のイベントログ

## 文字起こし

アプリ画面でOpenAI APIキーを入力して保存すると、未処理のM4Aと以後確定する5分セグメントを文字起こしキューへ登録します。

文字起こし結果はアプリ専用領域の`files/transcripts/<segmentId>.json`へ保存します。保存成功前に元音声は削除しません。通信失敗、HTTP 429、5xxはWorkManagerで再試行します。401/403などの恒久エラー時は音声を保持します。

APIキーはソースコード・APKへ埋め込みません。入力したキーはAndroid Keystoreで暗号化して保存します。

## ビルド

Android SDK API 36、JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2を使用します。

```bash
gradle :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## 現在未実装

- LLMによる会話履歴化
- 1日のノート生成
- マインドマップ / アクションアイテム生成
- 端末内Whisper
