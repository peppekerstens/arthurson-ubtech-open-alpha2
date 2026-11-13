package com.open.alpha2;

import android.content.Context;
import android.content.Intent;

/**
 * 2026-09: Decoupled from Alpha2OpenSdk - replaces Alpha2RobotApi from
 * sdk-module/ubtechalpha2robot. The robot body has no
 * com.ubtechinc.alpha2services at all, so every binder call is doomed to
 * fail. This is therefore a permanent no-op facade: each method returns an
 * honest failure value immediately ({@link UbxErrorCode.API_ERROR_CODE#API_ERROR_NOT_INIT},
 * {@code false}, {@code null}). Listeners are never called back, and nothing
 * blocks or throws. Behavior is identical to before (with every util null);
 * the only difference is that the whole SDK module is no longer needed.
 *
 * <p>Exceptions (no binder involved, worked originally, original semantics kept):
 * <ul>
 *   <li>{@link #requestRobotUUID()} - still sends the broadcast (nobody
 *       receives it, purely for backward compatibility; the real value read
 *       goes through the {@code queryChestRobotUuid()} direct path).</li>
 *   <li>{@link #speech_SetMIC(boolean)} - still returns {@code true} (same as
 *       the SDK when util is null; does nothing).</li>
 *   <li>{@link #releaseApi()} - no-op.</li>
 * </ul>
 *
 * <p>Everything that really touches hardware goes through
 * {@code HardwareDirectManager} (chest/head serial direct drive),
 * {@code DirectLedController} (JNI), and the Android native APIs.
 */
public final class RobotStub {
    private final Context mContext;

    public RobotStub(Context context) {
        this.mContext = context.getApplicationContext();
    }

    /** Grammar-build callback (leftover interface from the binder era; the
     *  listener is never called). */
    public interface IAlpha2SpeechGrammarInitListener {
        void speechGrammarInitCallback(String grammarId, int errorCode);
    }

    /** Grammar-result callback (same as above). */
    public interface IAlpha2SpeechGrammarListener {
        void onSpeechGrammarResult(int type, String result);
        void onSpeechGrammarError(int errorCode);
    }

    // Removed 2026-09: action_getActionList / action_PlayActionName /
    // action_StopAction (zero callers; actions always go through UbxPlayer
    // direct drive, see actionListDirect()).
    // -- Speech / TTS (all dead: no speech service) ----------------------------

    public UbxErrorCode.API_ERROR_CODE speech_startTTS(String language, String text, String strVoiceName) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_setRecognizedLanguage(String strLanguage) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_initGrammar(String strGrammar,
            IAlpha2SpeechGrammarInitListener listener) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_startGrammar(IAlpha2SpeechGrammarListener listener) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_stopGrammar() {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    // -- Mic (false success: returns true like the SDK when util is null, does
    // nothing) -------------------------------------------------------------
    // Note: returning true does not mean the mic was grabbed. Callers (the
    // MicCenter lock-holding pipeline) must not use this return value to
    // decide ownership. Treat it only as "notified (nobody receives it)"; read
    // the real mic state from our own AudioRecord.
    public boolean speech_SetMIC(boolean isWake) {
        return true;
    }

    // -- No binder: original semantics kept --------------------------------

    public void requestRobotUUID() {
        mContext.sendBroadcast(new Intent("com.ubtechinc.robot_uuid.request"));
    }

    public void releaseApi() {
    }
}


