# v0.7.111: Restore Gem file-completion ACK (05)

PCS Wiki 04 §31 explicitly defines the transfer completion sequence:
01 file acquisition → DATA → 02 metadata/CRC → 03 finalize/03ff →
**complete CRC and durable local save** → 05 + exact target filename →
0501 → 00 → 0000.

v0.7.110 deviated by deliberately setting `sendCompletionAck=false`, disabling the final `05` altogether even after full file save. That was an unrequested regression. In v0.7.111, the original v0.7.108 ACK-capable transfer implementation is restored:
- Never ACK before verified sequence, size, CRC, 03 finalize, matching filename/fileId and successful disk persistence.
- ACK only the one selected file in the timestamp/filename window; abort if an older/different file is announced.
- Require 0501, then query 00 and require 0000 before marking transfer complete.
- Do not claim that 0501 itself proves physical flash deletion.

**Separate unresolved issue**: Gem remote recording START/STOP BLE HEX is not established in Wiki §31. v0.7.110's historical STOP=03 hypothesis stays UNVERIFIED. v0.7.111 restores the required post-save completion ACK; it does not establish or prove that the Gem physically stopped after five seconds. Hardware testing is required. No manual button or diagnostic-only substitution is added.

GitHub branch: agent/memoket-restore-post-save-delete-0.7.111. Debug version 0.7.111, code 1111, common stable signing via CI.
