package com.open.alpha2;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HeadKeyPoller;


/**
 * Head-top +/- pad gesture package: pad event wiring, press/hold volume repeat,
 * two-key full stop.
 *
 * sendevent hitting /dev/input/event0 directly is verified: 0x5a/0x5b volume
 * down/stop, 0x5e full stop (stops the song), 0x5f nothing; see onGestureCode below.
 * Ownership:
 * - MainActivity keeps only: the wiring (construct + start in onCreate,
 *   shutdown() in onDestroy) and the Host implementation (the 0x5e full-stop
 *   key hands off to SpeechCenter; the TTS orchestration has moved there).
 * - The pad LEDs already live in LedCenter; the cue sounds live in
 *   RingtoneCenter; action/music/radio go through the same ActionDirect/
 *   AudioCenter passed in here.
 */
public final class GestureCenter {
    private static final String TAG = "GestureCenter";

    /**
     * Host seam: the 0x5e two-key full-stop key must also stop TTS (the real
     * implementation is in SpeechCenter; MainActivity hands it off).
     */
    public interface Host {
        void stopAllSpeech();
    }

    private final Context appContext;
    private final Handler mainHandler;
    private final LedCenter ledCenter;
    private final RingtoneCenter ringtoneCenter;
    private final ActionDirect actionDirect;
    private final AudioCenter audioCenter;
    private final Host host;

    public GestureCenter(Context context, Handler mainHandler, LedCenter ledCenter,
            RingtoneCenter ringtoneCenter, ActionDirect actionDirect, AudioCenter audioCenter,
            Host host) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.ledCenter = ledCenter;
        this.ringtoneCenter = ringtoneCenter;
        this.actionDirect = actionDirect;
        this.audioCenter = audioCenter;
        this.host = host;
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Shared by onDestroy: stop the repeat + release the tick pool + stop the poller. */
    public void shutdown() {
        stopVolumeRepeat();
        if (tickPool != null) {
            try { tickPool.release(); } catch (Throwable ignore) {}
            tickPool = null;
            tickSoundId = 0;
        }
        headKeyPoller.setListener(null);
        try { headKeyPoller.stop(); } catch (Throwable ignored) {}
    }

    private final HeadKeyPoller headKeyPoller = new HeadKeyPoller();
    private Runnable volumeRepeater;
    private AudioManager audioManager;
    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;
    // Repeat generation + cap: a release (0x5b/0x5d) is the only switch that stops the repeat -
    // if it is lost (the native state sticks with no release report / a leftover chain from an old
    // instance / stop colliding with a running tick), the chain runs forever and only a reboot
    // stops it. Generation: each start/stop bumps it by 1, and an old runnable that no longer
    // matches exits. Cap: one tick every 300ms, 20 ticks = a 6-second ceiling (a normal hold is
    // released well before 6 seconds, so it is unnoticeable; if you hold a full 6 seconds, stop
    // and press again). Both run on the main thread only (start/stop/run all post via mainHandler).
    private int volumeRepeatGen = 0;
    private static final int VOLUME_REPEAT_MAX_TICKS = 20;
    // Per-tick volume sound: the system Effect_Tick.ogg (the same sound as the hardware volume keys),
    // played through SoundPool (low latency; no need to open a full MediaPlayer for an 80ms blip).
    // It plays at a ratio of the current STREAM_MUSIC volume - a lower volume makes the tick quieter,
    // silence makes it silent, matching the system behavior.
    // FLAG_PLAY_SOUND is only a fallback for the window before the tick is loaded (the first press may miss it).
    private android.media.SoundPool tickPool;
    private int tickSoundId = 0;
    private static final String TICK_PATH = "/system/media/audio/ui/Effect_Tick.ogg";
    // When the native poller is alive, the hold/release semantics are real (press->hold->release):
    // a press below runs one step + the repeat, a release stops it - exactly right. The Java poll
    // fallback is a click model (a press is a full down+up with no release sound): a tap still runs
    // one step, and hold/repeat is impossible (the driver reports nothing).
    // No double-click window: testing the buttons alternately would misfire the squat action. The two-key full
    // stop runs the panel button / the XiaoZhi button; do not assume whether a synthetic 0x5e arrives.
    // HeadKeyPoller reads /dev/input/event0 directly.
    public void start() {
        // HeadKeyPoller is wired directly through the Listener, not via the EventBus "gesture" event.
        // head_key/head_key_native still forward to the EventBus, for the WebSocket log.
        headKeyPoller.setListener(new HeadKeyPoller.Listener() {
            @Override public void onGesture(int eventCode) {
                // 0x5a-0x5d (volume + LEDs) run directly on the callback thread - every
                // operation is a volatile write / a post to a single thread / a binder call,
                // thread-safe, so it does not queue on the main thread behind congestion; the heavy
                // 0x5e full stop (action + TTS + music playback) stays on main.
                // 0x5f only turns the LEDs off; it runs directly.
                if (eventCode == 0x5e) mainHandler.post(() -> onGestureCode(eventCode));
                else onGestureCode(eventCode);
            }
            @Override public void onHeadKeyNative(int code) {
                EventBus.get().publish("head_key_native", "{\"code\":" + code + "}");
            }
        });
        try { headKeyPoller.start(); } catch (Throwable t) { Log.w(TAG, "headKeyPoller start failed", t); }
        ensureTickLoaded();
    }
    /**
     * Reacts to the head touch-pad "gestures" broadcast via {@code come.ubt.alpha2.gesture}.
     *
     * These are NOT documented in the SDK (docs/sensors-and-events.md only lists the raw
     * `come.ubt.alpha2.gesture` action/extra name, not what values it carries) - the values
     * below were captured from a real robot's WebSocket event log:
     *
     *   "-" pad pressed  -> 23041 (0x5a01)      "-" pad released -> 23297 (0x5b01)
     *   "+" pad pressed  -> 23553 (0x5c01)      "+" pad released -> 23809 (0x5d01)
     *   both pressed     -> 24065 (0x5e01, high byte 94 decimal)   both released -> 24321 (0x5f01)
     *
     * Every value's low byte is 0x01; the high byte (0x5a-0x5f, 90-95) is a distinct,
     * sequential event code for each of the 6 press/release combinations - i.e. this
     * extra carries a compound (eventCode << 8 | 0x01) value here, not the plain
     * "direction" the field name suggests. Mapped to: "-"/"+" press-and-hold repeats
     * volume down/up every VOLUME_REPEAT_INTERVAL_MS until release; pressing both (high
     * byte 94, decimal) triggers a full stop-everything (action/speech/local music/
     * radio - see stopAllViaPads()/onGestureCode()'s 0x5e case), matching the
     * XiaoZhi panel's "stop all" button; releasing both does nothing extra.
     */
    private void onGestureCode(int code) {
        switch (code) {
            case 0x5a: // "-" pressed: run one step first, then schedule the repeat;
                // while the native poller is alive the release stops it (a normal hold); in the
                // poll-fallback click model the release arrives 8ms later and stops the repeat,
                // so only this one step runs.
                ledCenter.setPadMinusHeld(true);
                ledCenter.padLedUpdate();
                stepVolume(false);
                startVolumeRepeat(false);
                break;
            case 0x5b: // "-" released
                ledCenter.setPadMinusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5c: // "+" pressed: same as above
                ledCenter.setPadPlusHeld(true);
                ledCenter.padLedUpdate();
                stepVolume(true);
                startVolumeRepeat(true);
                break;
            case 0x5d: // "+" released
                ledCenter.setPadPlusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5e: // both pressed (raw gesture code 94, decimal) - full stop:
                       // matches the "stop all" button on the XiaoZhi panel
                       // (xiaozhiStopAll(), see app-xiaozhi.js), stopping the action /
                       // XiaoZhi speech / local music / radio in one go.
                       // (In the click model this case relies only on the synthetic 0x5e + the raw
                       // both-pressed state; it has never been observed in testing and will not fire
                       // on its own; the double-click window was removed, see above.)
                stopAllViaPads();
                break;
            case 0x5f: // both released: nothing further to do
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
                break;
            default:
                // Unknown gesture code - not one of the 6 confirmed above; ignore.
                break;
        }
    }
    /** Two-key full stop (reaches here only via the synthetic 0x5e + the raw both-pressed state; duplicate 0x5e is blocked by HeadKeyPoller). */
    private void stopAllViaPads() {
        ledCenter.setPadMinusHeld(true);
        ledCenter.setPadPlusHeld(true);
        ledCenter.padLedUpdate();
        stopVolumeRepeat(); // in case one pad was already held down
        ringtoneCenter.playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
        // pure-direct: one-key full stop (abort the action + crouch-stand to return to position,
        // including the head-double-pad trigger), same semantics as HTTP action/stop.
        actionDirect.stopActionWithRecovery();
        host.stopAllSpeech();
        audioCenter.stopLocalMusicPlayback();
        audioCenter.stopRadioPlayback();
        // If no 0x5f arrives, this failsafe turns the LEDs off; but if a new press happens in
        // the meantime (the generation moved), stand down - the key someone is holding must
        // not go out (the epoch is in LedCenter).
        final int epoch = ledCenter.getPadHeldEpoch();
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (ledCenter.getPadHeldEpoch() != epoch) return;
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
            }
        }, 1500);
    }

    /** Pref key of the early key feedback switch (alpha2-brain#53). Default false. */
    public static final String PREF_KEY_EARLY_FEEDBACK = "key_early_feedback_enabled";

    /** True when the tick plays before the volume binder call. Default false. */
    private boolean isEarlyFeedbackEnabled() {
        try {
            return appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY_EARLY_FEEDBACK, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** One volume step for one key press: the same step as before alpha2-brain#53, only the
     *  order of tick and binder call follows the early feedback switch. */
    private void stepVolume(final boolean up) {
        if (audioManager == null) return;
        VolumeStepSequence.run(isEarlyFeedbackEnabled(), tickReady(),
                new VolumeStepSequence.Actions() {
                    @Override public void tick() {
                        playTick();
                    }
                    @Override public void adjustVolume(boolean playSoundFlag) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI
                                        | (playSoundFlag ? AudioManager.FLAG_PLAY_SOUND : 0));
                    }
                    @Override public void meter() {
                        ledCenter.showVolumeMeter();
                    }
                });
    }

    /** SoundPool preload (done once in start(); the load is async, so the first presses may be silent if they miss the load). */
    private void ensureTickLoaded() {
        if (tickPool != null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                tickPool = new android.media.SoundPool.Builder()
                        .setMaxStreams(1)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
            } else {
                tickPool = new android.media.SoundPool(1, AudioManager.STREAM_MUSIC, 0);
            }
            tickSoundId = tickPool.load(TICK_PATH, 1);
        } catch (Throwable t) {
            Log.w(TAG, "tick load failed", t);
            tickPool = null;
            tickSoundId = 0;
        }
    }

    private boolean tickReady() {
        return tickPool != null && tickSoundId != 0;
    }

    /** Tick once at a ratio of the current volume; silent if the tick is not loaded yet or the stream is muted (no throw). */
    private void playTick() {
        try {
            if (!tickReady() || audioManager == null) return;
            int level = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (level <= 0 || max <= 0) return;
            float v = Math.max(0f, Math.min(1f, (float) level / max));
            tickPool.play(tickSoundId, v, v, 1, 0, 1.0f);
        } catch (Throwable ignore) {
        }
    }
    /**
     * Starts (or restarts) a repeating volume step every VOLUME_REPEAT_INTERVAL_MS,
     * simulating press-and-hold behaviour on top of AudioManager's single-step API.
     *
     * click model: the first step runs in stepVolume() (see 0x5a/0x5c);
     * this only schedules the later repeat (the release arrives 8ms later and
     * usually stops it at once; it stays for the off chance a firmware really
     * reports a hold).
     *
     * FLAG_PLAY_SOUND is only a fallback for the window before the tick is loaded
     * (see stepVolume); at the top or bottom of the range Android goes silent by
     * itself (no move, no sound), so nothing extra is needed.
     */
    private void startVolumeRepeat(boolean up) {
        stopVolumeRepeat(); // Stop the old chain first (bump the generation to kill the old runnable + clear the field)
        final int gen = volumeRepeatGen; // Adopt the current generation (no extra bump, so a start does not jump two levels)
        final int[] ticks = new int[1];
        volumeRepeater = new Runnable() {
            @Override
            public void run() {
                if (gen != volumeRepeatGen) return; // Stale generation (already stopped / superseded by a newer chain)
                if (++ticks[0] > VOLUME_REPEAT_MAX_TICKS) { stopVolumeRepeat(); return; }
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI | (tickReady() ? 0 : AudioManager.FLAG_PLAY_SOUND));
                    playTick();
                    ledCenter.showVolumeMeter();
                }
                if (gen != volumeRepeatGen) return; // A stop arrived while running: do not reschedule
                mainHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(volumeRepeater, VOLUME_REPEAT_INTERVAL_MS);
    }
    private void stopVolumeRepeat() {
        volumeRepeatGen++; // Bump the generation first: a queued or running old runnable exits at its next check
        if (volumeRepeater != null) {
            mainHandler.removeCallbacks(volumeRepeater);
            volumeRepeater = null;
        }
    }
}



