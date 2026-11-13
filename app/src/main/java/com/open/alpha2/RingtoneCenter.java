package com.open.alpha2;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import java.util.Map;

/**
 * System ringtone layer: RingtoneManager lookup + MediaPlayer playback on
 * STREAM_MUSIC.
 */
public final class RingtoneCenter {
    private static final String TAG = "RingtoneCenter";

    private final Context appContext;

    private static final String STOP_CUE_RINGTONE_TITLE = "Proxima";
    private android.net.Uri stopCueUri; // resolved lazily, cached after the first lookup
    private boolean stopCueLookupDone = false;
    // Camera shutter cue (played on the robot's own speaker, not the browser) - see
    // takePhoto()/HttpServer "camera/shutter_sound". "Sirrah" is a built-in Android
    // system ringtone title, matched the same lazy/cached-by-title way as the
    // Proxima stop cue above.
    private static final String SHUTTER_CUE_RINGTONE_TITLE = "Sirrah";
    private android.net.Uri shutterCueUri;
    private boolean shutterCueLookupDone = false;

    // PIR alert cue - "Heaven" is a built-in Android system ringtone title, same
    // approach as STOP_CUE/SHUTTER_CUE (lazy lookup by title, cache the content://
    // Uri). Playback timing: see MainActivity.registerAlpha2PirAlertListener() - the
    // alpha2_pir_state broadcast (RobotEventReceiver.java) plays the cue as soon as
    // triggered=true and stops it as soon as triggered=false (like the sonar purple
    // LED, do not wait for the whole track to finish).
    private static final String PIR_ALERT_RINGTONE_TITLE = "Heaven";
    private android.net.Uri pirAlertUri;
    private boolean pirAlertLookupDone = false;

    public RingtoneCenter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Plays the "Proxima" system ringtone as the "stop" cue, on STREAM_MUSIC so its
     * loudness tracks the same media volume that +/- control - not the notification/
     * ring volume a plain Ringtone.play() would follow instead.
     *
     * Ringtone/RingtoneManager.getRingtone() always plays on the ringtone's own stream
     * type (TYPE_NOTIFICATION -> STREAM_NOTIFICATION), which can't be overridden - so
     * this resolves "Proxima" to a content:// Uri via RingtoneManager (matching by
     * title, since that's the only stable way to name a specific built-in system sound),
     * cached after the first lookup, and plays that Uri through a plain MediaPlayer with
     * setAudioStreamType(STREAM_MUSIC) instead, which does follow the stream we set.
     */
    public void playStopCue() {
        if (!stopCueLookupDone) {
            stopCueUri = findRingtoneByTitle(STOP_CUE_RINGTONE_TITLE);
            stopCueLookupDone = true;
            if (stopCueUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + STOP_CUE_RINGTONE_TITLE
                        + "\" - stop cue will be skipped");
            }
        }
        playRingtoneUri(stopCueUri);
    }

    /**
     * Plays the "Sirrah" system ringtone as the camera shutter cue, out of the robot's
     * own speaker (this Activity runs on the robot's onboard Android system, not the
     * phone/browser controlling it - see robotpanel README) rather than synthesizing a
     * sound in the browser. Same lazy-lookup-by-title-then-cache approach as
     * playStopCue()/STOP_CUE_RINGTONE_TITLE above - title is the only stable way to
     * name a specific built-in system sound across devices/Android versions.
     */
    public void playShutterCue() {
        if (!shutterCueLookupDone) {
            shutterCueUri = findRingtoneByTitle(SHUTTER_CUE_RINGTONE_TITLE);
            shutterCueLookupDone = true;
            if (shutterCueUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + SHUTTER_CUE_RINGTONE_TITLE
                        + "\" - shutter cue will be skipped");
            }
        }
        playRingtoneUri(shutterCueUri);
    }

    /** Plays the "Heaven" system ringtone as the PIR trigger alert - same lazy
     *  lookup-by-title-then-cache approach as playStopCue()/playShutterCue() (see
     *  playStopCue()'s javadoc for why title lookup + STREAM_MUSIC via playRingtoneUri()
     *  instead of a plain Ringtone.play()). */
    public void playPirAlertCue() {
        if (!pirAlertLookupDone) {
            pirAlertUri = findRingtoneByTitle(PIR_ALERT_RINGTONE_TITLE);
            pirAlertLookupDone = true;
            if (pirAlertUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + PIR_ALERT_RINGTONE_TITLE
                        + "\" - PIR alert cue will be skipped");
            }
        }
        playRingtoneUri(pirAlertUri);
    }

    // Shared player: keep the MediaPlayer that is playing now. Stop the old one
    // before starting a new one.
    private android.media.MediaPlayer currentRingtonePlayer;

    /** Shared playback: STREAM_MUSIC (see playStopCue()'s javadoc for why not a plain
     *  Ringtone.play()). Stops/releases whatever ringtone was previously playing before
     *  starting the new one, and keeps a reference so audio/ringtones/stop (or the next
     *  call to this method) can interrupt it early instead of only ever letting it run
     *  to completion. No-ops silently if uri is null (title lookup found nothing on this
     *  device). */
    public synchronized void playRingtoneUri(android.net.Uri uri) {
        stopRingtonePlaybackLocked();
        if (uri == null) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(appContext, uri);
            player.setOnPreparedListener(android.media.MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                synchronized (RingtoneCenter.this) {
                    releaseDonePlayerLocked(mp);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (RingtoneCenter.this) {
                    releaseDonePlayerLocked(mp);
                }
                return true;
            });
            currentRingtonePlayer = player;
            player.prepareAsync(); // don't block the main thread; starts once ready
        } catch (Exception e) {
            Log.w(TAG, "Failed to play ringtone cue " + uri, e);
        }
    }

    /** Stops whatever ringtone/notification-sound MediaPlayer is currently playing (if
     *  any) and releases it. Safe to call when nothing is playing - simply no-ops.
     *  Must hold the same lock as playRingtoneUri() so a stop() can never race a
     *  concurrent start(); callers already inside a `synchronized(this)` block (i.e.
     *  playRingtoneUri() itself) should call the *Locked variant instead of re-entering. */
    public synchronized void stopRingtonePlayback() {
        stopRingtonePlaybackLocked();
    }

    /** Shared by the GamepadCenter ring toggle: is a ringtone playing now (the
     *  player exists; the completion and error listeners clear it). */
    public synchronized boolean isRingtonePlaying() {
        try {
            return currentRingtonePlayer != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void stopRingtonePlaybackLocked() {
        MediaPlayerUtil.stopRelease(currentRingtonePlayer);
        currentRingtonePlayer = null;
    }

    /** Shared by OnCompletion/OnError: release the player that finished or errored,
     *  and clear the current reference (the two listeners were identical word for
     *  word before; a bare release - a completed or errored player does not allow
     *  stop()). */
    private void releaseDonePlayerLocked(android.media.MediaPlayer mp) {
        mp.release();
        if (currentRingtonePlayer == mp) {
            currentRingtonePlayer = null;
        }
    }

    // RingtoneManager.getCursor() returns the *same* underlying cursor on every
    // call. Do not close() it yourself - its lifetime follows the RingtoneManager
    // instance. The RingtoneManager(Activity) constructor binds to the activity
    // lifecycle automatically (this code uses RingtoneManager(Context), which does
    // not). Keep the RingtoneManager instance and do not discard it after use.
    // Otherwise nobody releases the underlying cursor and it leaks (observed in
    // logcat: CursorWindowAllocationException, Open Cursors grew to 991 - this bug
    // caused it). Fix: key by rmType (TYPE_RINGTONE / TYPE_NOTIFICATION), cache
    // those two RingtoneManager instances, create each once for the whole app
    // lifetime, and reuse the cached instance on every later call
    // (RingtoneManager.getCursor() requeries itself internally; no manual refresh
    // needed).
    private final java.util.Map<Integer, android.media.RingtoneManager> ringtoneManagerCache = new java.util.HashMap<>();

    private synchronized android.media.RingtoneManager getCachedRingtoneManager(int rmType) {
        android.media.RingtoneManager cached = ringtoneManagerCache.get(rmType);
        if (cached != null) return cached;
        android.media.RingtoneManager manager = new android.media.RingtoneManager(appContext);
        manager.setType(rmType);
        ringtoneManagerCache.put(rmType, manager);
        return manager;
    }

    /** Scans every ringtone RingtoneManager knows about (notifications + ringtones)
     *  for one whose title matches exactly (case-insensitive), returning its Uri, or
     *  null if none match. Title is the only stable way to name a specific built-in
     *  system sound - resource IDs/file paths vary by OEM and Android version. */
    private android.net.Uri findRingtoneByTitle(String title) {
        return findRingtoneByTitle(title, android.media.RingtoneManager.TYPE_ALL);
    }

    /** Same as findRingtoneByTitle(String) but restricted to a single RingtoneManager
     *  type (TYPE_RINGTONE / TYPE_NOTIFICATION) - used by "audio/ringtones/play_by_title"
     *  so a phone-ringtone lookup can never accidentally match a notification sound (or
     *  vice versa) that happens to share the same title. Uses getCachedRingtoneManager()
     *  (see its javadoc) instead of `new RingtoneManager(this)` per call - the previous
     *  per-call instantiation leaked a Cursor every time this ran, since nothing ever
     *  released it (Android's RingtoneManager has no close()/release() of its own to call). */
    private android.net.Uri findRingtoneByTitle(String title, int rmType) {
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.database.Cursor cursor = manager.getCursor();
        int position = 0;
        while (cursor.moveToNext()) {
            String candidateTitle = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
            if (title.equalsIgnoreCase(candidateTitle)) {
                // getRingtoneUri() takes the cursor POSITION (0-based row index within
                // this RingtoneManager's result set), not a raw content-provider id -
                // Cursor has no getUri(); this is the correct API for it.
                return manager.getRingtoneUri(position);
            }
            position++;
        }
        return null;
    }

    // -- HTTP endpoints (called from handleApi; parameter validation still goes
    // through ApiValidator) ----------

    // -- System ringtones/notification sounds: exposes every ringtone Android
    // knows about (via RingtoneManager, same mechanism findRingtoneByTitle()
    // above already uses to look up "Proxima"/"Sirrah" by name) as a numbered
    // list, so the Blockly page can offer a dropdown without hardcoding titles
    // that vary by OEM/Android version. "list" returns titles+type; "play"
    // takes the numbered index back and plays it through the same STREAM_MUSIC
    // MediaPlayer path as playRingtoneUri() (so it follows the media volume
    // slider, not the separate ringer/notification volume). -------------------
    public HttpServer.ApiResponse ringtonesList(Map<String, String> query) {
        String type = ApiValidator.optionalRingtoneType(query);
        int rmType = "notification".equals(type)
                ? android.media.RingtoneManager.TYPE_NOTIFICATION
                : android.media.RingtoneManager.TYPE_RINGTONE;
        // Use getCachedRingtoneManager() (prevents cursor leaks).
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.database.Cursor cursor = manager.getCursor();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"type\":\"" + MainActivity.jsonSafe(type) + "\",\"sounds\":[");
        int position = 0;
        boolean first = true;
        while (cursor.moveToNext()) {
            String title = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"index\":").append(position).append(",\"title\":\"")
                    .append(MainActivity.jsonSafe(title == null ? "" : title)).append("\"}");
            position++;
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse ringtonesPlay(Map<String, String> query) {
        String type = ApiValidator.optionalRingtoneType(query);
        int index = ApiValidator.requireInt(query, "index");
        int rmType = "notification".equals(type)
                ? android.media.RingtoneManager.TYPE_NOTIFICATION
                : android.media.RingtoneManager.TYPE_RINGTONE;
        // Same as above: use the cached manager.
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.net.Uri uri;
        try {
            uri = manager.getRingtoneUri(index);
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"invalid index\"}");
        }
        if (uri == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"sound not found\"}");
        }
        playRingtoneUri(uri);
        return HttpServer.ApiResponse.okTrue();
    }

    // Look up the ringtone by title, not by the numbered index from
    // audio/ringtones/list (cursor position is not consistent across devices).
    // The Blockly page embeds a static title list (captured once on the device
    // with adb content query; see blockly-ringtone-data.js next to
    // blockly-actions-data.js). The chosen title goes straight to this API,
    // reusing the proven title-to-Uri lookup in findRingtoneByTitle(), already
    // used by playStopCue()/playShutterCue(). Index ordering is a non-issue.
    public HttpServer.ApiResponse ringtonesPlayByTitle(Map<String, String> query) {
        String type = ApiValidator.optionalRingtoneType(query);
        String title = ApiValidator.require(query, "title");
        int rmType = "notification".equals(type)
                ? android.media.RingtoneManager.TYPE_NOTIFICATION
                : android.media.RingtoneManager.TYPE_RINGTONE;
        android.net.Uri uri = findRingtoneByTitle(title, rmType);
        if (uri == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"sound not found\"}");
        }
        playRingtoneUri(uri);
        return HttpServer.ApiResponse.okTrue();
    }

    // Stop the system ringtone or notification sound that is playing now (the one
    // started by the play / play_by_title endpoints), for the "Stop playback"
    // button in Blockly "Example 5".
    public HttpServer.ApiResponse ringtonesStop() {
        stopRingtonePlayback();
        return HttpServer.ApiResponse.okTrue();
    }

    /** Shared by GamepadCenter mapping: play a system ringtone by title (same path
     *  as play_by_title: title to Uri, STREAM_MUSIC; returns false when not
     *  found). Safe to call from any thread. */
    public synchronized boolean playRingtoneTitleNow(String title) {
        return playRingtoneNow(null, title);
    }

    /** Shared by GamepadCenter split-table ringtones: type phone or notification
     *  searches that table only; null or other searches all tables (legacy ring:
     *  compatible). Returns false when not found. Safe to call from any thread. */
    public synchronized boolean playRingtoneNow(String type, String title) {
        if (title == null || title.trim().isEmpty()) return false;
        int rmType;
        if ("phone".equals(type)) {
            rmType = android.media.RingtoneManager.TYPE_RINGTONE;
        } else if ("notification".equals(type)) {
            rmType = android.media.RingtoneManager.TYPE_NOTIFICATION;
        } else {
            rmType = android.media.RingtoneManager.TYPE_ALL;
        }
        android.net.Uri uri;
        try {
            uri = findRingtoneByTitle(title.trim(), rmType);
        } catch (Exception e) {
            Log.w(TAG, "playRingtoneNow lookup failed", e);
            return false;
        }
        if (uri == null) return false;
        playRingtoneUri(uri);
        return true;
    }

    /** Shared by GamepadCenter mapping: play the default system phone ringtone
     *  (TYPE_RINGTONE default; returns false when none). Safe to call from any
     *  thread. */
    public synchronized boolean playDefaultPhoneRingtoneNow() {
        try {
            android.net.Uri uri = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_RINGTONE);
            if (uri == null) return false;
            playRingtoneUri(uri);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "playDefaultPhoneRingtoneNow failed", e);
            return false;
        }
    }

    /** Shared by GamepadCenter ring_random: play a random system ringtone
     *  (TYPE_RINGTONE table; returns false when there is no sound). Safe to call
     *  from any thread. */
    public synchronized boolean playRandomRingtoneNow() {
        try {
            android.media.RingtoneManager manager =
                    getCachedRingtoneManager(android.media.RingtoneManager.TYPE_RINGTONE);
            android.database.Cursor cursor = manager.getCursor();
            int count = 0;
            try {
                count = cursor.getCount();
            } catch (Throwable ignored) {
            }
            if (count <= 0) return false;
            android.net.Uri uri;
            try {
                uri = manager.getRingtoneUri(new java.util.Random().nextInt(count));
            } catch (Exception e) {
                return false;
            }
            if (uri == null) return false;
            playRingtoneUri(uri);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "playRandomRingtoneNow failed", t);
            return false;
        }
    }
}

