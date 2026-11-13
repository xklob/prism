package com.caleb.prism;
interface ISpotifyRelay {
    void destroy() = 16777114;
    void start(int sourceUid, IBinder owner) = 1;
    void stop() = 2;
    boolean isRunning() = 3;
}
