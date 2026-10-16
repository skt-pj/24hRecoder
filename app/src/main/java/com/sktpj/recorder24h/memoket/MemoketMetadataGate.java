package com.sktpj.recorder24h.memoket;
public final class MemoketMetadataGate {
  public static final long QUIET_DATA_MS=2000L;
  private MemoketMetadataGate() {}
  public static boolean mayRequest(long now,long lastData,long lastProbe,boolean hasData,boolean pending) {
    return hasData && !pending && now>=lastData && now-lastData>=QUIET_DATA_MS && now>=lastProbe && now-lastProbe>=1000L;
  }
}
