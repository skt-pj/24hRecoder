# 24hRecoder

Android 16 / Pixel 10aを初期基準端末とした24時間録音アプリです。

## 現在の実装

- 現在のdebug APK: `0.4.11-debug` / versionCode 15
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
- 多言語Whisper `large-v3 Q5`モデルを標準文字起こしに使用
- M4A復号後にDCオフセット除去・ロバスト音量推定・適応ゲイン・スムーズリミッタを適用
- Silero VAD v6.2.0で音声区間を抽出し、非発話区間のWhisperハルシネーションを抑制
- 5分M4AをAndroid MediaCodecでPCMへ復号し、端末内で推論
- WorkManagerで文字起こしを録音処理から分離し、低バッテリー時は延期
- ローカルWhisper推論は同時実行1件に制限し、待機中セグメントを「処理中」と誤表示しない
- debug APKでもwhisper.cpp/ggmlネイティブコードは`-O3`で最適化して推論する
- 前処理前後のRMS/peak/clipping、推定SNR、適用gain、limiter比率をログへ記録
- 文字起こし結果はtemp書き込み・fsync・renameで永続保存
- 旧エンジンの文字起こしは元M4Aが残る場合に現行版で再処理し、成功時だけ結果を置換
- 処理済みの記録でも、元M4Aが残っていれば記録詳細から「この音声を再文字起こし」で強制再実行可能
- 手動再文字起こし中も既存の文字起こし結果を保持し、新結果が正常保存された時だけ置換
- 文字起こし成功後も最近のM4Aを保持し、記録詳細から再生可能
- 容量圧迫時は古い文字起こし済み音声を優先して削除
- Jetpack Compose + Material 3によるUI
- 「ホーム / 記録 / 設定」の3画面構成
- 録音履歴と文字起こし結果を録音時刻順で一覧表示
- 文字起こし本文・segment ID・ファイル名の検索
- 「文字起こし済み / 音声あり / 要確認」のフィルタ
- セグメント詳細で文字起こし全文表示・コピー
- 保持中M4Aの端末内再生
- 録音停止前の確認ダイアログ
- Dynamic Color、edge-to-edge、コンパクト画面のBottom Navigationと広幅画面のNavigation Rail

## 文字起こし品質

0.4.3-debug以降では、24時間録音で頻出する無音・生活音・長い非発話区間をそのままWhisperへ渡さず、Silero VAD v6.2.0が音声と判定した区間だけを推論対象にします。加えて`no_speech_thold`、`suppress_blank`、`suppress_nst`等のデコード制御を使用します。

0.4.5-debugではVADの前に軽量なASR Audio Front-Endを追加します。M4Aを16kHz mono float PCMへ復号した後、DCオフセットを除去し、20msフレームのRMS分布から20パーセンタイルをノイズ床、ノイズ床+9dB以上かつ-50dBFS以上のフレームを発話候補として扱います。十分な発話候補がある場合は60パーセンタイルのRMSを発話レベルとして推定し、約-22dBFSへ近づけるゲインを-6〜+12dBの範囲で適用します。推定speech/noise差が6dB未満なら正のゲインを抑止し、ノイズだけを増幅しにくくします。

ゲイン後のピークは-2.5dBFSを超えた部分だけ滑らかに圧縮し、-1dBFSへ漸近するリミッタを通します。強いノイズ除去やEQは常時適用せず、音声のスペクトルを必要以上に変えない方針です。その後にSilero VADとWhisperを実行します。

VADモデルは`ggml-silero-v6.2.0.bin`を使用し、取得時に885,098 bytesとSHA-256 `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987`を検証します。標準ASRのWhisper large-v3 Q5と同じく`noBackupFilesDir/whisper/`へ保存し、モデル本体は作業データ1GBの論理上限には含めません。

0.4.8-debugでは標準文字起こしをWhisper large-v3 Q5へ変更し、エンジンIDを`whisper.cpp-v1.9.1/large-v3-q5_0+frontend-v1+silero-v6.2.0`とします。旧エンジンで文字起こし済みでも元M4Aが残っているセグメントは現行エンジンの再処理対象になります。既存JSONは新しい文字起こしのtemp書き込み・fsync・renameが成功するまで保持し、失敗時に過去の結果を失わないようにします。元M4Aが既に容量管理で削除済みの場合は再文字起こしできないため、旧結果をそのまま保持します。

0.4.4-debug以降では自動移行とは別に、現在のエンジンですでに処理済みの記録をユーザー操作で強制再実行できます。記録詳細の「この音声を再文字起こし」を押すと確認ダイアログを表示し、同じsegmentIdの保存済みM4Aを現在の前処理 + Whisper + VAD設定で再処理します。Workerは手動再実行フラグがある場合、現在のエンジンで処理済みでもスキップしません。再処理に失敗しても既存の文字起こしJSONは削除せず、新しい結果の永続保存に成功した時だけ置換します。

文字起こし完了ログには`inputRms`、`inputPeak`、`inputClippedFraction`、`audioRms`、`audioPeak`、`clippedFraction`、`dcOffset`、`estimatedNoiseRms`、`estimatedSpeechRms`、`snrProxyDb`、`appliedGainDb`、`activeFrameFraction`、`limitedSampleFraction`、`boostSuppressedForLowSnr`、`preprocessMs`を記録します。保持中M4Aを実際に再生した音とこれらの値を合わせ、録音入力・前処理・Whisper認識を切り分けます。

### 0.4.8: large-v3 Q5既定化とVAD診断時刻修正

実録音の比較でbase/smallよりlarge-v3 Q5の日本語出力が相対的にまともだったため、速度より内容を優先し、通常の確定文字起こしもlarge-v3 Q5を使用します。0.4.7の比較ログでSilero VADの独立診断時刻が100倍になっていた不具合も修正しました。whisper.cpp v1.9.1のVAD segment APIはcentisecond単位を返すため、ms変換は`* 10`です。修正後は5分音源のVAD区間とWhisper出力時刻を同じms timebaseで直接比較できます。

同じ実録音では、正しいtimebaseに直すとVADは約16.59〜73.84秒に6区間を検出し、base/small/medium Q5/large-v3 Q5は最終VAD付近まで出力する一方、Kotoba-Whisper v2.0 Q5は約21.62秒で出力が止まることが分かります。Kotobaの長音声経路は比較診断対象として残し、標準モデルにはしません。

### 0.4.9: 時刻区切り会話ログと文字起こし状態のクリーンリセット

0.4.9-debugでは通常文字起こしJSONにWhisperデコーダの`startMs`/`endMs`/区間本文を保存し、記録詳細の「会話ログ」を録音時刻（HH:mm:ss）ごとのカードに分けて表示します。5秒以上の無音・非出力ギャップは区間間に「空き」として表示し、コピー結果にも時刻を含めます。

また0.4.9への初回起動時に、過去の`files/transcripts/`と`files/model_comparisons/`を一度だけ削除し、保持中の`.m4a`音声は削除せず全件をREADYへ戻します。既存の文字起こし・比較WorkManagerはキャンセルし、reset generationを更新するため、リセット前から実行中だったWorkerが終了後に古い結果や異常な状態を書き戻すことを防ぎます。large-v3 Q5 + ASR Audio Front-End + Silero VADが準備済みなら、保持音声はREPLACE方針でクリーンに再登録します。

### 0.4.10: 手動再文字起こしの状態可視化

0.4.9ではQUEUEDをUI読込時にREADYへ変換していたため、記録詳細から「この音声を再文字起こし」を押しても状態表示が変わらず、WorkManagerへ登録されたか判断できなかった。0.4.10ではQUEUEDとreasonをそのままUIへ渡し、WorkManagerへenqueueした直後にjournalへQUEUEDを書き込む。手動要求は`MANUAL_RETRANSCRIPTION_WORK_ENQUEUED`、実推論枠待ちは`MANUAL_RETRANSCRIPTION_SLOT_WAIT`、実処理中は`MANUAL_RETRANSCRIBING`として区別する。

手動再文字起こしは自動処理と同じ`transcribe:<segmentId>` unique workをREPLACEで使用し、同じ音声について自動Workと手動Workが二重に走ることを避ける。詳細画面は1秒ごとに履歴状態を再読込し、「登録済み・処理枠待ち」「large-v3 Q5で処理中」「再試行待ち」を本文の上に明示する。

### 0.4.11: 文字起こしキュー一覧

0.4.11-debugでは「ホーム / キュー / 記録 / 設定」の4画面構成とし、現在の文字起こしWorkを専用のキュー画面で確認する。実行中・待機中・再試行待ち・失敗/要確認・キュー外音声を区分して表示し、各カードは録音時刻、追加元、状態更新時刻、状態理由を表示する。カード全体をタップすると同じsegmentIdの記録詳細（音声再生・会話ログ）へ遷移する。

待機中/再試行待ちはキュー画面から明示的に外すことができ、WorkManagerの`transcribe:<segmentId>`をcancelして、元音声と既存文字起こしは保持する。キュー外/失敗音声は同じ画面から再追加できる。手動/自動は別状態として扱わず追加元の補助情報とし、QUEUEDは`WORK_ENQUEUED`（WorkManager登録済み・Worker未開始）と`SLOT_WAIT`（Worker起動済み・Whisper排他枠待ち）を分けて説明する。

## 文字起こしキュー

WorkManagerは複数の`TranscriptionWorker`を同時に開始する場合がありますが、whisper.cppの実推論は1件ずつ実行します。0.4.1以前は各WorkerがWhisperの排他ロックを取得する前に`TRANSCRIBING`を書き込んでいたため、実際には順番待ちのセグメントまで全件「文字起こし中」と表示されていました。

0.4.2-debug以降はWorkerが排他ロック待ちの間は内部状態`QUEUED`とし、UIでは通常の「待機中」として表示します。ロックを取得した1件だけを`TRANSCRIBING`へ遷移させます。ログには`LOCAL_TRANSCRIPTION_QUEUED`、`LOCAL_TRANSCRIPTION_STARTED`、`queueWaitMs`、`decodeMs`、`preprocessMs`、`inferenceMs`を記録します。

手動再文字起こしは通常の自動処理とは別のunique work名で登録しますが、実Whisper推論は同じ排他区間を使うため同時実行数は1件のままです。手動再実行は`MANUAL_RETRANSCRIPTION_ENQUEUED`、`MANUAL_RETRANSCRIPTION_QUEUED`、`MANUAL_RETRANSCRIPTION_STARTED`、`MANUAL_RETRANSCRIPTION_SAVED`等のイベントで追跡できます。

配布成果物はdebug APKですが、ローカルWhisperは性能が必要な実処理です。CMakeのDebug構成でもwhisper.cpp/ggml/JNIには`-O3`を追加し、デバッグ可能性を残したまま推論コードを最適化します。5分音声の実際の推論時間はPixel 10a実機で測定して継続可否を判断します。

## 記録閲覧

「記録」画面は`files/metadata/segments.jsonl`、`files/transcripts/*.json`、現在残っている音声ファイルをsegmentIdで統合して表示します。表示順は文字起こし処理日時ではなく録音開始時刻を優先します。

文字起こし成功後もM4Aは即時削除せず、最近の録音を詳細画面から再生できるよう保持します。音声領域が600MBを超える、作業データ上限に近づく、または端末空き容量が不足した場合は、文字起こし結果が永続保存済みの古いM4Aから優先して削除します。それでも不足する場合に限り、最古の未文字起こし音声を削除してデータ欠損ログを残します。

録音プロセスがjournal末尾へ追記している最中でもUIが落ちないよう、不完全な最終JSONL行は読み飛ばします。履歴読み込みはUIスレッドや`:recorder`プロセスでは行いません。

## ローカルWhisperモデル

標準Whisper ASRには`ggml-large-v3-q5_0.bin`、発話検出には`ggml-silero-v6.2.0.bin`を使用します。large-v3 Q5は1,081,140,203 bytesとSHA-256 `d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1`を検証します。base/small/medium Q5/Kotoba Q5は比較用として引き続き利用できます。

モデルは`noBackupFilesDir/whisper/`へ保存し、24hRecoderの「作業データ1GB」論理上限には含めません。ただし端末の実空き容量監視からは除外しません。

文字起こし時に録音音声をOpenAI等の外部文字起こしAPIへ送信しません。モデル取得完了後は文字起こし自体にネットワーク接続は不要です。

## APK署名・バージョン管理

debug APKのapplicationIdは`com.sktpj.recorder24h.debug`で固定します。`versionCode`はリリースごとに必ず単調増加させます。

0.4.0以前はGitHub Actionsの一時debug keystoreに依存していたため、CI runごとに署名鍵が変わり、Android上で上書き更新できませんでした。0.4.1-debug以降はGradleからdebug自動署名を外し、GitHub ActionsでAOSPの固定testkeyを使用して署名します。これにより0.4.1-debug以降のCI成果物同士は同じ証明書で更新できます。

このtestkeyは公開された開発・テスト用鍵であり、production releaseには絶対に使用しません。正式配布時は専用の非公開release鍵へ切り替えます。

既に0.4.0以前のランダムdebug鍵でインストール済みのAPKは、対応する秘密鍵が残っていないため0.4.1-debugへ直接更新できません。一度だけアンインストールして0.4.1-debug以降を新規インストールする必要があります。そのアンインストールではアプリ内部データが削除されるため、必要な録音・文字起こしデータがある場合は事前退避が必要です。

## ビルド

Android SDK API 36、JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.3.21、Compose BOM 2026.06.00、NDK 27.0.12077973、CMake 3.22.1を使用します。ネイティブビルド時に`whisper.cpp v1.9.1`のソースを取得してarm64-v8a向けにビルドします。

```bash
gradle :app:assembleDebug
```

ローカルの`assembleDebug`出力は署名前APKです。配布用debug APKはGitHub Actionsで固定testkeyを適用し、`app/build/outputs/apk/debug/app-debug.apk`としてアップロードします。

## 現在のバージョン

- versionName: `0.4.5-debug`
- versionCode: `9`
- minSdk: `29`
- targetSdk: `36`
- ABI: `arm64-v8a`

## 注意

Pixel 10a実機での前処理ゲイン分布、VAD検出品質、5分音声の推論時間、24時間録音との同時運用時の電池消費・温度・メモリ使用量は実測が必要です。ローカル文字起こしが失敗しても録音処理を停止させず、元音声と既存の確定済み文字起こしは可能な限り保持します。

前処理 + VAD導入後も、実際のM4Aを再生して会話が明瞭に聞こえるのにbaseモデルの文字起こし精度が不足する場合は、より大きいモデルへの変更をPixel 10aの処理時間・熱・バックログと合わせて評価します。

履歴画面は現段階ではJSONLとファイルを読み合わせる実装です。長期間運用して履歴件数が大きくなった場合は、起動・検索性能を実測し、必要ならインデックス付きDBへ移行します。
