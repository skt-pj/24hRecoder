# Memoket Gem BLE: confirmed traffic vs. interpretations (2026-10-14)
Source: PCS Wiki 04 sections 19-27, official Gem manual https://jp.memoket.ai/pages/instruction-manual, 24hRecoder v0.7.108 real-device logs.

| Step | Observed control | Response | What it proves |
| --- | --- | --- | --- |
| Session | 00/2701/e1/f300/e301/ff68/e5/e8 | matching auth replies | A BLE session exists |
| Pre-transfer 03 | 03 | On Oct 14: 0302 + two old filenames, then 03ff | File inventory ended, **not recording START confirmation** |
| DATA CCCD | 0000 then 0100 | descriptor success | Data notifications OFF then ON; **not Gem STOP** |
| File transfer | 010000 | 010101 + filename; DATA packets | Starts outgoing file data; **not Gem STOP** |
| Metadata | 0200 | size, CRC | A transfer has metadata, not necessarily Gem stopped recording |
| Finalize | 03 | 0301 / 0303 then 03ff | Transfer finalization stage |
| Saved-file ACK | 05+filename | 0501 | Ack, not LED/device-stop proof |
| Idle query | 00 | 0000 | Reported idle communication status |

The vendor manual describes a long physical button press for recording start, one short physical press for stop, then automatic file transfer. No **BLE** remote record-control opcode is documented there. The absence of documentation is not proof none exists.

## Failed v0.7.108
08:00:19 control 03 → 0302 existing files → 03ff; app incorrectly marked RECORDING_ACTIVE.
08:00:24 the 5-second timer called a method named beginOfficialStop, which only toggled DATA CCCD OFF→ON and sent 010000.
08:00:25 ongoing DATA began; at 08:01:29 there was no actual Gem stop, verified finalization, CRC-backed file save or 05 ACK.

## v0.7.109 behavior
This **diagnostic/safety-only** release treats 03ff as a file-inventory end marker, never proof of START; it reports an unsupported remote recording flow, closes BLE, and does not trigger a 5-second timer, a pseudo-STOP CCCD transition, download or file deletion. An 8-second timeout covers missing 03ff. It does not claim to deliver unattended Gem record→stop→transfer; that requires an independently verified remote START/STOP protocol.
