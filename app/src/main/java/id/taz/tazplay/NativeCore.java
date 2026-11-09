package id.taz.tazplay;

/** Jembatan JNI ke frontend libretro native (tazcore.c). */
public class NativeCore {
    static {
        System.loadLibrary("tazcore");
    }

    public static native boolean init(String corePath, String sysDir, String saveDir);
    public static native boolean loadGame(String romPath);
    public static native void setSurface(android.view.Surface surface);
    public static native void start();
    public static native void stop();
    public static native void setButtons(int mask);
    public static native void setAspectMode(int mode);
    public static native int getFrameCount();
    public static native boolean saveState(String path);
    public static native boolean loadState(String path);
    public static native void setSmooth(boolean on);
    public static native void setAudioEnabled(boolean on);
    public static native void setAudioMode(int mode);
    public static native void setPadType(int type);
    public static native void setAxes(int lx, int ly, int rx, int ry);
    public static native void destroy();
}
