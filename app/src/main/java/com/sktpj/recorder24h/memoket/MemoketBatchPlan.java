package com.sktpj.recorder24h.memoket;

import java.util.Collections;
import java.util.List;

/** Only the known post-physical-stop, single-file BLE flow is testable. */
public final class MemoketBatchPlan {
    public static final List<String> FILE_CASES =
            Collections.singletonList("FILE_ONE");
    public static final List<String> STOP_CASES = Collections.emptyList();

    private MemoketBatchPlan() {}

    public static boolean isFileCase(String id) { return FILE_CASES.contains(id); }
    public static boolean isStopCase(String id) { return STOP_CASES.contains(id); }
}
