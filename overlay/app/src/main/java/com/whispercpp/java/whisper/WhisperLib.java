package com.whispercpp.java.whisper;

public final class WhisperLib {
    static {
        System.loadLibrary("whisper");
    }

    private WhisperLib() {}

    public static native long initContext(String modelPath);
    public static native void freeContext(long contextPtr);
    public static native int fullTranscribe(long contextPtr, int numThreads, float[] audioData, String language);
    public static native int getTextSegmentCount(long contextPtr);
    public static native String getTextSegment(long contextPtr, int index);
    public static native long getTextSegmentT0(long contextPtr, int index);
    public static native long getTextSegmentT1(long contextPtr, int index);
    public static native void setAbortRequested(boolean requested);
    public static native String getSystemInfo();
}
