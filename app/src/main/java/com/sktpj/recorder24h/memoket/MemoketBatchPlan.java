package com.sktpj.recorder24h.memoket;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MemoketBatchPlan {
    public static final List<String> FILE_CASES = Collections.unmodifiableList(Arrays.asList(
            "FILE_LIST", "FILE_SPECIFIC", "FILE_ONE", "FILE_THREE"));

    public static final List<String> STOP_CASES = Collections.unmodifiableList(Arrays.asList(
            "STOP_A", "STOP_B", "STOP_C", "STOP_AB", "STOP_AC", "STOP_BC",
            "STOP_ABC", "STOP_OFFICIAL_TIMING", "STOP_OFF_WAIT_ON",
            "STOP_ON_OFF", "STOP_LIST_DELAY", "STOP_03_REPEAT",
            "STOP_OFF_DISCONNECT", "STOP_DISCONNECT"));

    private MemoketBatchPlan() {}

    public static boolean isFileCase(String id) { return FILE_CASES.contains(id); }
    public static boolean isStopCase(String id) { return STOP_CASES.contains(id); }
}
