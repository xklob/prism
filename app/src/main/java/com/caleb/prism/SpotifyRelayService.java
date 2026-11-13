package com.caleb.prism;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.media.*;
import android.os.Looper;
import java.lang.reflect.*;

/** Optional shell-authorized, media-only reroute for the explicitly selected Spotify UID. */
public final class SpotifyRelayService extends ISpotifyRelay.Stub {
    private volatile boolean running;
    private volatile long lastReadNs;
    private AudioRecord input;
    private AudioTrack playback;
    private Object activePolicy;
    private android.os.IBinder owner;
    private android.os.IBinder.DeathRecipient death;
    public SpotifyRelayService() {}
    public SpotifyRelayService(Context ignored) {}
    @Override public void destroy() { stop(); System.exit(0); }
    @Override public boolean isRunning() { return running && System.nanoTime() - lastReadNs < 5_000_000_000L; }
    @Override public synchronized void stop() {
        long identity = android.os.Binder.clearCallingIdentity();
        try {
        running = false;
        if (owner != null && death != null) try { owner.unlinkToDeath(death, 0); } catch (Exception ignored) {}
        owner = null; death = null;
        if (input != null) { try { input.stop(); } catch (Exception ignored) {} input.release(); input = null; }
        if (playback != null) { try { playback.stop(); } catch (Exception ignored) {} playback.release(); playback = null; }
        if (activePolicy != null) {
            try {
                Class<?> type = Class.forName("android.media.audiopolicy.AudioPolicy");
                Method unregister = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", type);
                unregister.setAccessible(true); unregister.invoke(null, activePolicy);
            } catch (Exception ignored) {}
            activePolicy = null;
        }
        } finally { android.os.Binder.restoreCallingIdentity(identity); }
    }
    @Override public synchronized void start(int sourceUid, android.os.IBinder client) {
        int callerUid = android.os.Binder.getCallingUid();
        if (sourceUid < 10000 || sourceUid == callerUid || sourceUid / 100000 != callerUid / 100000 || client == null)
            throw new IllegalArgumentException("Invalid media source");
        stop();
        long identity = android.os.Binder.clearCallingIdentity();
        try {
            open(sourceUid);
            owner = client; death = this::stop; owner.linkToDeath(death, 0);
        } catch (Exception error) { stop(); throw new IllegalStateException("Spotify relay could not start", error); }
        finally { android.os.Binder.restoreCallingIdentity(identity); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, values);
    }
    private static Context context() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell = new android.content.ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @android.annotation.TargetApi(31)
            @Override public android.content.AttributionSource getAttributionSource() {
                return new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
        Application app = Instrumentation.newApplication(Application.class, shell);
        Field initial = activityThread.getDeclaredField("mInitialApplication");
        initial.setAccessible(true);
        initial.set(thread, app);
        return shell;
    }
    private void open(int targetUid) throws Exception {
        Context context = context();
        AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Object rules = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder").getConstructor().newInstance();
        call(rules, "setTargetMixRole", new Class[]{int.class}, ruleType.getField("MIX_ROLE_PLAYERS").getInt(null));
        call(rules, "addMixRule", new Class[]{int.class,Object.class},
            ruleType.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null),
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
        call(rules, "addMixRule", new Class[]{int.class,Object.class},
            ruleType.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(targetUid));
        Object rule = call(rules, "build", new Class[]{});
        Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
        Object mixBuilder = Class.forName("android.media.audiopolicy.AudioMix$Builder").getConstructor(ruleType).newInstance(rule);
        call(mixBuilder, "setFormat", new Class[]{AudioFormat.class}, format);
        call(mixBuilder, "setRouteFlags", new Class[]{int.class}, mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
        Object mix = call(mixBuilder, "build", new Class[]{});
        Class<?> policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy$Builder").getConstructor(Context.class).newInstance(context);
        call(policyBuilder, "addMix", new Class[]{mixType}, mix);
        Object policy = call(policyBuilder, "build", new Class[]{});
        Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyType);
        register.setAccessible(true);
        if (((Integer)register.invoke(null, policy)) != 0) throw new IllegalStateException("Audio policy rejected");
        activePolicy = policy;
        input = (AudioRecord)call(policy, "createAudioRecordSink", new Class[]{mixType}, mix);
        if (input == null || input.getState() != AudioRecord.STATE_INITIALIZED || input.getSampleRate() != 48000 || input.getChannelCount() != 2)
            throw new IllegalStateException("No 48 kHz stereo audio sink");
        int buffer = Math.max(7680, AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
        playback = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
        playback.play(); input.startRecording(); lastReadNs = System.nanoTime(); running = true;
        final AudioRecord record = input; final AudioTrack output = playback;
        Thread pump = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            short[] samples = new short[1920];
            try {
                while (running) {
                    int read = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    if (read < 0) throw new IllegalStateException("Relay input ended: " + read);
                    if (read == 0) { Thread.sleep(8); continue; }
                    lastReadNs = System.nanoTime();
                    int offset = 0;
                    while (offset < read && running) {
                        int wrote = output.write(samples, offset, read-offset, AudioTrack.WRITE_BLOCKING);
                        if (wrote <= 0) throw new IllegalStateException("Relay playback ended");
                        offset += wrote;
                    }
                }
            } catch (Exception error) { if (running) android.util.Log.w("PrismSpotifyRelay", "Audio relay ended", error); }
            finally { synchronized (SpotifyRelayService.this) { if (input == record) stop(); } }
        }, "prism Spotify relay");
        pump.setDaemon(true); pump.start();
    }
}
