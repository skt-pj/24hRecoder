package com.sktpj.recorder24h.memoket;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class MemoketTransferStatusTest {
    private static final String FILE = "20261010_224936_2.opus";

    @Test public void ut11AcceptsCaptured00StatusForOnlySelectedFile() {
        byte[] name = FILE.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[name.length + 4];
        payload[1] = 1;
        payload[3] = 2;
        System.arraycopy(name, 0, payload, 4, name.length);
        MemoketTransferStatus.verifyIntermediate00(payload, FILE);
    }

    @Test(expected = IllegalStateException.class)
    public void ut11RejectsOtherFilenameDuring00Status() {
        byte[] name = "20261009_224936_2.opus".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[name.length + 4];
        payload[1] = 1;
        payload[3] = 2;
        System.arraycopy(name, 0, payload, 4, name.length);
        MemoketTransferStatus.verifyIntermediate00(payload, FILE);
    }

    @Test(expected = IllegalStateException.class)
    public void ut11RejectsIncomplete00Status() {
        MemoketTransferStatus.verifyIntermediate00(new byte[]{0, 1}, FILE);
    }

    @Test public void ut11AcceptsCapturedE1Acknowledgment() {
        MemoketTransferStatus.verifyIntermediateE1(new byte[]{(byte)0xe1, 0x3a, 0x02});
    }

    @Test(expected = IllegalStateException.class)
    public void ut11RejectsUnrelatedE1Response() {
        MemoketTransferStatus.verifyIntermediateE1(new byte[]{(byte)0xe1, 0, 0});
    }
}
