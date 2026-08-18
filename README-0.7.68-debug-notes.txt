0.7.68-debug diagnostic fix

Pixel 10a Drive diagnostics showed daily AI regeneration stayed disabled because realtime-only canonical failures were retained as FAILED + audio present + transcript missing + queue NONE. Realtime-only policy excludes normal nightly canonical transcription, so those failures had no automatic recovery path.

0.7.68 queues only LIVE_CANONICAL_TRANSCRIPT_MISSING and FULL_STREAMING_ASR_FAILED retained-audio orphans into a dedicated postprocess fallback, preserving realtime-only as the normal mode. It also fixes the duplicate daily AI action queue-state check so stale WAITING_DATA/RETRY_WAIT does not block the user after all non-corrupt transcripts are complete.

Debug versionCode: 1068
Debug versionName: 0.7.68-debug
