# Memoket Gem v0.7.110 experimental five-second STOP-03 comparison

Sources: PCS Wiki 04 §21 (historical STOP=03 hypothesis), §24 (hypothesis retracted), §26 (v0.7.108 failed; DATA CCCD does not stop recording), §31 (authoritative evidence and sequence diagrams). GitHub branch v0.7.108 as baseline. Official Gem START/STOP packet HEX **not verified**.

This is an **unverified hardware test**, not a successful implementation of the official recording protocol. The required function is one-tap START → 5s → real Gem STOP → file acquisition. Historical 03 in STOP phase is the only candidate used here; no guessed unknown opcodes or command brute-force. The app must not claim 03ff is proof of physical STOP.

## What changed
- Reuses the v0.7.108 START/5-second timer, whose 03ff could also be inventory terminator and is NOT independent START proof.
- **At 5 seconds send CONTROL 03 once** (historical §21 hypothesis, unverified), wait for terminal 03ff (max 10s), then switch DATA CCCD OFF→ON before file request 010000. Do not confuse CCCD confirmation with real Gem STOP.
- Preserve full file-owner window selection, ordered DATA, end-of-stream metadata, size/CRC comparison, finalize 03.
- Save candidate file locally only after full CRC and durable save, **NEVER SEND 05 DELETE ACK** while stop semantics are unknown. Confirmed deletion is not part of this test. A partial file may still be possible; it is not labeled as a verified fully stopped recording.
- UI clearly states candidate STOP and non-deletion; traces record phase STOP_03_HISTORICAL_CANDIDATE_REQUESTED_UNVERIFIED.
- No physical-button confirmation or other user manual intervention in the one-tap flow.

## Acceptance
On Gem hardware, inspect actual red LED physical stop, verify recorded audio duration approximately five seconds and matching file timestamp, check local CRC, and ensure the original file remains on Gem because 05 was never sent. Only if verified update the official protocol ledger; a positive 03ff by itself is insufficient.

## Disclaimer
This is not claimed as official BLE START/STOP parity because the capture_output_appli(1).zip original is not present in the current connected Drive/Project sources; Wiki 31 cannot identify START/STOP bytes. Do not merge it as a proven fix without explicit on-device verification.
