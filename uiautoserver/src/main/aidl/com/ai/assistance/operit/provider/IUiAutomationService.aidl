package com.ai.assistance.operit.provider;

interface IUiAutomationService {
    String getPageInfo(String displayIdStr);
    boolean clickElement(String resourceId, String className, String desc, String boundsStr, String displayIdStr, boolean partialMatch, int index);
    boolean tap(int x, int y, String displayIdStr);
    boolean swipe(int startX, int startY, int endX, int endY, int durationMs, String displayIdStr);
    boolean setInputText(String text);
    boolean pressKey(String keyCodeStr);
    void suicide();
}
