package com.open.alpha2;


import android.util.Log;

import java.util.Map;

/**
 * Local semantic-pairing glue: dialogue language to matcher (text detection
 * when not set) + TTS/action execution.
 *
 * FUNCTION real execution (since 2026-09, no longer just mouth movement):
 * stopall = same as the two-key master stop (action + TTS + music/radio, does
 * not stop the mic);
 * volumeup/down = move system volume by one step (via AudioCenter, same as the
 * +/- pad);
 * bton/btoff = toggle Bluetooth (via DeviceStatus);
 * wifion/wifioff = toggle Wi-Fi (via DeviceStatus);
 * reboot/shutdown = no REBOOT permission, only a TTS prompt to act manually;
 * takepicture = high-resolution save (via CameraApi, does not report a path);
 * musicplay/next/prev = first/previous/next local music track (via AudioCenter,
 * cycling in filename sort order).
 * New collaborators are added later through setters (to avoid the MainActivity
 * onCreate construction-order cycle).
 */
public final class SemanticCenter {

    private static final String TAG = "SemanticCenter";

    /** How long to wait after TTS before playing the action. Follows the
     *  original timing decompiled from YouChat RobotActionBusiness.startBusiness()
     *  (TTS first, sleep 200ms, then play the action - the two are separate,
     *  non-synchronous AIDL calls, held apart only by this sleep, and it does not
     *  wait for TTS to actually finish playing). The user has confirmed keeping
     *  YouChat's original approach; it is not changed to wait for TTS to finish
     *  before moving. */
    private static final int SEMANTIC_TTS_TO_ACTION_DELAY_MS = 200;

    private final SemanticMatcherZh semanticMatcherZh;
    private final SemanticMatcherEn semanticMatcherEn;
    private final SemanticMatcherEs semanticMatcherEs;
    private final SemanticMatcherFr semanticMatcherFr;
    private final SemanticMatcherJa semanticMatcherJa;
    private final SemanticMatcherDe semanticMatcherDe;
    private final SemanticMatcherIt semanticMatcherIt;
    private final SemanticMatcherPt semanticMatcherPt;
    private final SemanticMatcherKo semanticMatcherKo;
    private final SemanticMatcherRu semanticMatcherRu;
    private final ActionDirect actionDirect;
    private final TtsCenter ttsCenter;

    /** FUNCTION real-execution collaborators (added later through setters, see
     *  above; when not yet added, the null guard skips the side effect and TTS
     *  runs as normal). */
    private volatile AudioCenter audioCenter;
    private volatile DeviceStatus deviceStatus;
    private volatile CameraApi cameraApi;
    private volatile GestureCenter.Host speechStopHost;

    public void setAudioCenter(AudioCenter c) { audioCenter = c; }
    public void setDeviceStatus(DeviceStatus d) { deviceStatus = d; }
    public void setCameraApi(CameraApi c) { cameraApi = c; }
    public void setSpeechStopHost(GestureCenter.Host h) { speechStopHost = h; }

    /** Dialogue language (zh/en/es/fr/ja/de/it/pt/ko/ru; null = not set): passed
     *  in through setDialogueLang() at the moment vosk/load swaps the model.
     *  Whatever language vosk recognizes maps to that matcher. Anything beyond the
     *  ten (setDialogueLang does not accept them, so they never reach
     *  dialogueLang) falls back to English (that is the rule below: everything
     *  other than "zh".equals goes to English). */
    private volatile String dialogueLang;

    /** Dialogue-language setting (VoskApi passes it in at the moment
     *  vosk/load swaps the model; only accepts zh/en/es/fr/ja/de/it/pt/ko/ru).
     *  TTS is not bound to this - which TTS is used is entirely the user's
     *  decision. Here it only decides which matcher to use, and the locale the
     *  answer is read in follows the primary language (see handleSemanticMatch). */
    public void setDialogueLang(String lang) {
        if ("zh".equals(lang) || "en".equals(lang) || "es".equals(lang) || "fr".equals(lang) || "ja".equals(lang)
                || "de".equals(lang) || "it".equals(lang) || "pt".equals(lang) || "ko".equals(lang) || "ru".equals(lang)) dialogueLang = lang;
    }

    public SemanticCenter(SemanticMatcherZh semanticMatcherZh,
            SemanticMatcherEn semanticMatcherEn, SemanticMatcherEs semanticMatcherEs,
            SemanticMatcherFr semanticMatcherFr, SemanticMatcherJa semanticMatcherJa,
            SemanticMatcherDe semanticMatcherDe, SemanticMatcherIt semanticMatcherIt,
            SemanticMatcherPt semanticMatcherPt, SemanticMatcherKo semanticMatcherKo,
            SemanticMatcherRu semanticMatcherRu,
            ActionDirect actionDirect, TtsCenter ttsCenter) {
        this.semanticMatcherZh = semanticMatcherZh;
        this.semanticMatcherEn = semanticMatcherEn;
        this.semanticMatcherEs = semanticMatcherEs;
        this.semanticMatcherFr = semanticMatcherFr;
        this.semanticMatcherJa = semanticMatcherJa;
        this.semanticMatcherDe = semanticMatcherDe;
        this.semanticMatcherIt = semanticMatcherIt;
        this.semanticMatcherPt = semanticMatcherPt;
        this.semanticMatcherKo = semanticMatcherKo;
        this.semanticMatcherRu = semanticMatcherRu;
        this.actionDirect = actionDirect;
        this.ttsCenter = ttsCenter;
    }

    /** Decide whether an input text should be handled by the Chinese matcher:
     *  any CJK unified ideograph (Hanzi) present means Chinese, none present
     *  means English. Now demoted to a backup: only used when the dialogue
     *  language is not set (the boot default, never set); once set, it follows
     *  the setting (see handleSemanticMatch) and no longer relies on text alone.
     *  A mixed Chinese-English sentence (for example "跳個 dance") is treated as
     *  Chinese because it has Hanzi - an intentional simplification. It does not
     *  pursue perfect language detection, and it is accurate enough for this
     *  use. */
    private static boolean looksChinese(String text) {
        if (text == null) return false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** Take a line of text (what the speech engine really recognized, or typed
     *  input used to test through the speech/semantic_simulate endpoint) and check
     *  it against the 1000 question-form pairs. On a hit, run YouChat's original
     *  "TTS first, then play the action 200ms later" flow. When nothing is found,
     *  do nothing (not an error - the user saying something that is not in those
     *  1000 lines is normal, and staying silent is better than returning an
     *  unrelated reply at random), and return null.
     *
     *  Which matcher to use: when a dialogue language is set, follow the setting
     *  (whatever language vosk recognizes maps to that matcher;
     *  SemanticMatcherZh/En/Es/Fr/Ja/De/It/Pt/Ko/Ru have the same structure with
     *  independent data and do not affect each other). Use only the primary one -
     *  do not try other languages, do not steal, do not fall back. When not set,
     *  use looksChinese() (Hanzi present runs Chinese semantic_zh.json, absent
     *  runs English semantic_en.json; Western/French/Japanese/German/Italian/
     *  Portuguese/Korean/Russian can only enter through the setting).
     *
     *  A skeleton language (empty question-form library / empty fallback) match()
     *  returns null: when the primary matcher is a skeleton, stay silent the
     *  whole way, and do not answer with somebody else's fallback.
     *
     *  The TTS locale follows the primary language (not the input text). The
     *  mouth LED and the action flow are unchanged.
     *
     *  Returning MatchResult (not void) lets the speech/semantic_simulate
     *  endpoint tell the frontend in real time "whether a pair matched". The
     *  publishEvent=false usage no longer publishes an extra time through EventBus
     *  (the frontend sendSpeechChatText() already shows it in real time from the
     *  HTTP response).
     *
     *  TTS/action execution itself still makes its AIDL blocking call on a
     *  separate thread, not directly on the caller's thread (which may be an HTTP
     *  worker thread) - the same safe approach as triggerRandomFillerAction(). */
    public SemanticMatcherZh.MatchResult handleSemanticMatch(final String text,
                                                                         final boolean publishEvent) {
        final boolean textChinese = looksChinese(text);
        // Primary language: when set, follow the setting (zh/en/es/fr/ja/de/it/
        // pt/ko/ru). When not set, use looksChinese (Hanzi present means Chinese,
        // absent means English; Western/French/Japanese/German/Italian/Portuguese/
        // Korean/Russian can only enter through the setting). Use only the primary
        // matcher and do not try other languages: when the primary misses, use the
        // primary's own fallback, never steal or fall back across languages.
        final String lang;
        if (dialogueLang != null) {
            lang = dialogueLang;
        } else {
            lang = textChinese ? "zh" : "en";
        }
        final SemanticMatcherBase mainMatcher;
        final String hitLang;
        if ("ru".equals(lang)) {
            mainMatcher = semanticMatcherRu;
            hitLang = "ru";
        } else if ("ko".equals(lang)) {
            mainMatcher = semanticMatcherKo;
            hitLang = "ko";
        } else if ("pt".equals(lang)) {
            mainMatcher = semanticMatcherPt;
            hitLang = "pt";
        } else if ("it".equals(lang)) {
            mainMatcher = semanticMatcherIt;
            hitLang = "it";
        } else if ("de".equals(lang)) {
            mainMatcher = semanticMatcherDe;
            hitLang = "de";
        } else if ("ja".equals(lang)) {
            mainMatcher = semanticMatcherJa;
            hitLang = "ja";
        } else if ("fr".equals(lang)) {
            mainMatcher = semanticMatcherFr;
            hitLang = "fr";
        } else if ("es".equals(lang)) {
            mainMatcher = semanticMatcherEs;
            hitLang = "es";
        } else if ("en".equals(lang)) {
            mainMatcher = semanticMatcherEn;
            hitLang = "en";
        } else {
            mainMatcher = semanticMatcherZh;
            hitLang = "zh";
        }

        SemanticMatcherBase.MatchResult result = mainMatcher != null ? mainMatcher.match(text) : null;
        if (result == null) {
            return null; // blank input / empty skeleton library - quietly do nothing, not an error
        }
        // The thread inside cannot capture the reassigned result, so use a final
        // shadow.
        final SemanticMatcherBase.MatchResult finalResult = result;
        // Primary language: both the TTS locale and the category random follow it
        // (made final so the thread below can use it).
        final String hitLangFinal = hitLang;
        if (publishEvent) {
            EventBus.get().publish("semantic_match",
                    "{\"question\":\"" + MainActivity.jsonSafe(result.question) + "\","
                            + "\"type\":\"" + MainActivity.jsonSafe(result.type) + "\","
                            + "\"operation\":\"" + MainActivity.jsonSafe(result.operation) + "\","
                            + "\"answer\":\"" + MainActivity.jsonSafe(result.answer) + "\","
                            + "\"actionId\":\"" + MainActivity.jsonSafe(result.actionId) + "\"}");
        }

        // The robot body has no iFlytek/Nuance (no alpha2services), so
        // robot.speech_startTTS() only returns NOT_INIT and stays silent the
        // whole way. Semantic-pair answers now run on the Android built-in TTS
        // (the same engine as the speech/tts engine=android branch), selecting
        // the locale by the answer language. Turning off the mouth LED relies on
        // the Android TTS UtteranceProgressListener (see
        // TtsCenter.initAndroidTts); no auto turn-off is needed.
        final String ttsAnswer = finalResult.answer;
        // The TTS locale follows the primary language (Western/French/Japanese/
        // German/Italian/Portuguese/Korean/Russian answers are read in the
        // matching language, or the accent would sound very wrong).
        final java.util.Locale ttsLocale;
        if ("ru".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("ru");
        } else if ("ko".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("ko");
        } else if ("pt".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("pt");
        } else if ("it".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("it");
        } else if ("de".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("de");
        } else if ("ja".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("ja");
        } else if ("fr".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("fr");
        } else if ("es".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("es");
        } else if ("en".equals(hitLangFinal)) {
            ttsLocale = java.util.Locale.ENGLISH;
        } else {
            ttsLocale = java.util.Locale.SIMPLIFIED_CHINESE;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                // FUNCTION real execution: side effects first (same thread as the
                // TTS/action flow, safe on a background thread; camera takePhoto
                // takes up to 8s and must never move to the main thread).
                // When ttsOverride is not null, use it instead of the JSON static
                // answer (for failure / dynamic song names, following the hit
                // language).
                String ttsOverride = runFunctionSideEffect(finalResult, hitLangFinal);
                // Resolve to the real action id first (category random / __RANDOM__
                // was originally resolved at play time, but now the presence of a
                // sound must be known before playing, so resolve early; when it
                // resolves to null, run with no action).
                String actionId = finalResult.actionId;
                if (actionId != null && actionId.startsWith("__RANDOM_CATEGORY__")) {
                    // The user said a category name (for example "跳舞" /
                    // "Dance for me") but not which specific action - pick one at
                    // random from the matching category in the 202-action list
                    // (for example DANCE_KIDS/YOGA_ANY). Unlike "__RANDOM__" below
                    // (no category limit at all, any of the 202), this is
                    // category-limited random. The Chinese and English matchers
                    // share the same action_category_pools.json, so whichever
                    // instance calls it gets the same result; use the primary
                    // matcher directly.
                    final SemanticMatcherBase hitMatcher = mainMatcher;
                    actionId = hitMatcher != null
                            ? hitMatcher.resolveCategoryRandomActionId(actionId) : null;
                } else if ("__RANDOM__".equals(actionId)) {
                    // Operations of the TFBOY kind have no fixed action in the
                    // factory question forms - reuse the random action pool that
                    // triggerRandomFillerAction() already has (the batch among the
                    // 202 actions whose names start with "隨機短/長", made for this
                    // "move a little to look more alive" effect).
                    actionId = actionDirect.resolveRandomActionId();
                }
                // Sound action: do not speak the full TTS line (to avoid fighting
                // the action's own sound effect for the speaker), just play the
                // action. Note the simulate response still returns the answer text
                // (for the frontend bubble display), it simply makes no sound.
                if (actionId != null && ActionDirect.isSoundAction(actionId)) {
                    Log.i(TAG, "sound action " + actionId + ", suppressing TTS answer");
                    actionDirect.playActionDirect(actionId); // pure-direct: the old AIDL no longer has a service backing it
                    return;
                }
                String speakText = (ttsOverride != null) ? ttsOverride : ttsAnswer;
                if (speakText != null && !speakText.isEmpty()) {
                    LedCenter.startMouthLedForTts();
                    if (!ttsCenter.speakAndroidTts(speakText, ttsLocale)) {
                        LedCenter.stopMouthLedForTts();
                    }
                }
                if (actionId == null) {
                    return; // no action resolved (random pool empty) or this FUNCTION type has no action, end after TTS
                }
                try {
                    Thread.sleep(SEMANTIC_TTS_TO_ACTION_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                actionDirect.playActionDirect(actionId); // pure-direct: the old AIDL no longer has a service backing it
            }
        }, "SemanticMatchAction").start();
        return result;
    }

    /** Side effects for a FUNCTION op (called from a background thread).
     *  Returning null = use the JSON static answer and TTS as normal; returning
     *  non-null = instead speak this line (failure prompt / dynamic song name,
     *  following the lang hit language). Non-FUNCTION or unknown op always
     *  returns null. reboot/shutdown deliberately have no side effect (no REBOOT
     *  permission; the answer itself is already a manual prompt). */
    private String runFunctionSideEffect(SemanticMatcherBase.MatchResult r, String lang) {
        if (r == null || !"FUNCTION".equals(r.type) || r.operation == null) return null;
        String op = r.operation;
        try {
            if ("volumeup".equals(op)) {
                if (audioCenter != null) audioCenter.adjustSystemVolume(true);
                return null;
            }
            if ("volumedown".equals(op)) {
                if (audioCenter != null) audioCenter.adjustSystemVolume(false);
                return null;
            }
            if ("stopall".equals(op)) {
                // Same as the two-key master stop: cut off each path first, then
                // let the outer TTS play the "all stopped" answer (it does not
                // cancel itself, because stop runs before speak).
                try { actionDirect.stopActionWithRecovery(); } catch (Exception e) {
                    Log.w(TAG, "stopall: stopAction failed", e);
                }
                try {
                    if (speechStopHost != null) speechStopHost.stopAllSpeech();
                    else ttsCenter.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stopall: stopSpeech failed", e);
                }
                try {
                    if (audioCenter != null) {
                        audioCenter.stopLocalMusicPlayback();
                        audioCenter.stopRadioPlayback();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stopall: stopMusic failed", e);
                }
                return null;
            }
            if ("bton".equals(op) || "btoff".equals(op)) {
                boolean wantOn = "bton".equals(op);
                if (deviceStatus == null) return null;
                boolean ok = deviceStatus.setBluetoothEnabled(wantOn);
                if (ok) return null;
                return functionText(lang, wantOn ? "btOnFail" : "btOffFail", null);
            }
            if ("wifion".equals(op) || "wifioff".equals(op)) {
                boolean wantOn = "wifion".equals(op);
                if (deviceStatus == null) return null;
                boolean ok = deviceStatus.setWifiEnabled(wantOn);
                if (ok) return null;
                return functionText(lang, wantOn ? "wifiOnFail" : "wifiOffFail", null);
            }
            if ("takepicture".equals(op)) {
                if (cameraApi == null) return functionText(lang, "camNotReady", null);
                try {
                    HttpServer.ApiResponse resp = cameraApi.takePhotoSave(
                            new java.util.HashMap<String, String>());
                    String body = resp != null ? resp.body : "";
                    if (body != null && body.contains("\"ok\":true")) return null; // success: speak the JSON static "taken" answer
                    return functionText(lang, "camFail", null);
                } catch (Exception e) {
                    Log.w(TAG, "takepicture failed", e);
                    return functionText(lang, "camFail", null);
                }
            }
            if ("musicplay".equals(op) || "musicnext".equals(op) || "musicprev".equals(op)) {
                if (audioCenter == null) return functionText(lang, "musicNotReady", null);
                String name;
                if ("musicnext".equals(op)) name = audioCenter.playNextLocalMusic();
                else if ("musicprev".equals(op)) name = audioCenter.playPrevLocalMusic();
                else name = audioCenter.playFirstLocalMusic();
                if (name == null) return functionText(lang, "musicEmpty", null);
                String base = name;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                return functionText(lang, "musicNow", base);
            }
        } catch (Exception e) {
            Log.w(TAG, "runFunctionSideEffect op=" + op + " failed", e);
        }
        return null;
    }

    /** Multilingual FUNCTION failure / dynamic prompts (keys as above; the name
     *  for musicNow is the song name with its extension removed). Unknown
     *  languages fall back to English. */
    private static String functionText(String lang, String key, String name) {
        if ("es".equals(lang)) {
            if ("btOnFail".equals(key)) return "Lo siento, no pude activar el Bluetooth.";
            if ("btOffFail".equals(key)) return "Lo siento, no pude desactivar el Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Lo siento, no pude activar la red Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Lo siento, no pude desactivar la red Wi-Fi.";
            if ("camNotReady".equals(key)) return "Lo siento, la cámara no está lista.";
            if ("camFail".equals(key)) return "Lo siento, no pude tomar la foto.";
            if ("musicNotReady".equals(key)) return "Lo siento, la música no está lista.";
            if ("musicEmpty".equals(key)) return "No hay canciones en la carpeta de música.";
            if ("musicNow".equals(key)) return "De acuerdo, reproduciendo " + name + ".";
        } else if ("fr".equals(lang)) {
            if ("btOnFail".equals(key)) return "Désolé, impossible d'activer le Bluetooth.";
            if ("btOffFail".equals(key)) return "Désolé, impossible de désactiver le Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Désolé, impossible d'activer le Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Désolé, impossible de désactiver le Wi-Fi.";
            if ("camNotReady".equals(key)) return "Désolé, l'appareil photo n'est pas prêt.";
            if ("camFail".equals(key)) return "Désolé, la photo a échoué.";
            if ("musicNotReady".equals(key)) return "Désolé, la musique n'est pas prête.";
            if ("musicEmpty".equals(key)) return "Il n'y a aucune chanson dans le dossier de musique.";
            if ("musicNow".equals(key)) return "D'accord, je joue " + name + ".";
        } else if ("ja".equals(lang)) {
            if ("btOnFail".equals(key)) return "すみません、Bluetoothをオンにできませんでした。";
            if ("btOffFail".equals(key)) return "すみません、Bluetoothをオフにできませんでした。";
            if ("wifiOnFail".equals(key)) return "すみません、Wi-Fiをオンにできませんでした。";
            if ("wifiOffFail".equals(key)) return "すみません、Wi-Fiをオフにできませんでした。";
            if ("camNotReady".equals(key)) return "すみません、カメラの準備ができていません。";
            if ("camFail".equals(key)) return "すみません、撮影に失敗しました。";
            if ("musicNotReady".equals(key)) return "すみません、音楽の準備ができていません。";
            if ("musicEmpty".equals(key)) return "音楽フォルダに曲がありません。";
            if ("musicNow".equals(key)) return "わかりました、「" + name + "」を再生します。";
        } else if ("de".equals(lang)) {
            if ("btOnFail".equals(key)) return "Leider konnte ich Bluetooth nicht einschalten.";
            if ("btOffFail".equals(key)) return "Leider konnte ich Bluetooth nicht ausschalten.";
            if ("wifiOnFail".equals(key)) return "Leider konnte ich WLAN nicht einschalten.";
            if ("wifiOffFail".equals(key)) return "Leider konnte ich WLAN nicht ausschalten.";
            if ("camNotReady".equals(key)) return "Leider ist die Kamera nicht bereit.";
            if ("camFail".equals(key)) return "Leider hat das Foto nicht geklappt.";
            if ("musicNotReady".equals(key)) return "Leider ist die Musik nicht bereit.";
            if ("musicEmpty".equals(key)) return "Im Musikordner sind keine Lieder.";
            if ("musicNow".equals(key)) return "Verstanden, ich spiele jetzt " + name + ".";
        } else if ("it".equals(lang)) {
            if ("btOnFail".equals(key)) return "Mi dispiace, non riesco ad attivare il Bluetooth.";
            if ("btOffFail".equals(key)) return "Mi dispiace, non riesco a disattivare il Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Mi dispiace, non riesco ad attivare il Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Mi dispiace, non riesco a disattivare il Wi-Fi.";
            if ("camNotReady".equals(key)) return "Mi dispiace, la fotocamera non è pronta.";
            if ("camFail".equals(key)) return "Mi dispiace, la foto non è riuscita.";
            if ("musicNotReady".equals(key)) return "Mi dispiace, la musica non è pronta.";
            if ("musicEmpty".equals(key)) return "Non ci sono canzoni nella cartella della musica.";
            if ("musicNow".equals(key)) return "Va bene, riproduco " + name + ".";
        } else if ("pt".equals(lang)) {
            if ("btOnFail".equals(key)) return "Desculpe, não consegui ativar o Bluetooth.";
            if ("btOffFail".equals(key)) return "Desculpe, não consegui desativar o Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Desculpe, não consegui ativar o Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Desculpe, não consegui desativar o Wi-Fi.";
            if ("camNotReady".equals(key)) return "Desculpe, a câmera não está pronta.";
            if ("camFail".equals(key)) return "Desculpe, não consegui tirar a foto.";
            if ("musicNotReady".equals(key)) return "Desculpe, a música não está pronta.";
            if ("musicEmpty".equals(key)) return "Não há músicas na pasta de música.";
            if ("musicNow".equals(key)) return "Está bem, tocando " + name + ".";
        } else if ("ko".equals(lang)) {
            if ("btOnFail".equals(key)) return "죄송합니다, 블루투스를 켤 수 없습니다.";
            if ("btOffFail".equals(key)) return "죄송합니다, 블루투스를 끌 수 없습니다.";
            if ("wifiOnFail".equals(key)) return "죄송합니다, 와이파이를 켤 수 없습니다.";
            if ("wifiOffFail".equals(key)) return "죄송합니다, 와이파이를 끌 수 없습니다.";
            if ("camNotReady".equals(key)) return "죄송합니다, 카메라가 준비되지 않았습니다.";
            if ("camFail".equals(key)) return "죄송합니다, 사진 촬영에 실패했습니다.";
            if ("musicNotReady".equals(key)) return "죄송합니다, 음악이 준비되지 않았습니다.";
            if ("musicEmpty".equals(key)) return "음악 폴더에 노래가 없습니다.";
            if ("musicNow".equals(key)) return "알겠습니다, " + name + " 재생합니다.";
        } else if ("ru".equals(lang)) {
            if ("btOnFail".equals(key)) return "Извините, не получается включить Bluetooth.";
            if ("btOffFail".equals(key)) return "Извините, не получается выключить Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Извините, не получается включить Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Извините, не получается выключить Wi-Fi.";
            if ("camNotReady".equals(key)) return "Извините, камера не готова.";
            if ("camFail".equals(key)) return "Извините, не получилось сделать снимок.";
            if ("musicNotReady".equals(key)) return "Извините, музыка не готова.";
            if ("musicEmpty".equals(key)) return "В папке с музыкой нет песен.";
            if ("musicNow".equals(key)) return "Хорошо, сейчас играет " + name + ".";
        } else if ("zh".equals(lang)) {
            if ("btOnFail".equals(key)) return "抱歉，無法打開藍牙。";
            if ("btOffFail".equals(key)) return "抱歉，無法關閉藍牙。";
            if ("wifiOnFail".equals(key)) return "抱歉，無法打開無線網路。";
            if ("wifiOffFail".equals(key)) return "抱歉，無法關閉無線網路。";
            if ("camNotReady".equals(key)) return "抱歉，相機尚未準備就緒。";
            if ("camFail".equals(key)) return "抱歉，照片拍攝失敗。";
            if ("musicNotReady".equals(key)) return "抱歉，音樂尚未準備就緒。";
            if ("musicEmpty".equals(key)) return "音樂資料夾中沒有歌曲。";
            if ("musicNow".equals(key)) return "好的，正在播放" + name + "。";
        }
        // en + fallback
        if ("btOnFail".equals(key)) return "Sorry, I couldn't turn on Bluetooth.";
        if ("btOffFail".equals(key)) return "Sorry, I couldn't turn off Bluetooth.";
        if ("wifiOnFail".equals(key)) return "Sorry, I couldn't turn on Wi-Fi.";
        if ("wifiOffFail".equals(key)) return "Sorry, I couldn't turn off Wi-Fi.";
        if ("camNotReady".equals(key)) return "Sorry, the camera isn't ready.";
        if ("camFail".equals(key)) return "Sorry, I couldn't take the picture.";
        if ("musicNotReady".equals(key)) return "Sorry, the music player isn't ready.";
        if ("musicEmpty".equals(key)) return "There are no songs in the music folder.";
        if ("musicNow".equals(key)) return "Okay, now playing " + name + ".";
        return null;
    }

    // "Treat typing as if the user said this line" - take the input text
    // directly as the result the speech engine has already finished
    // recognizing, and send it to handleSemanticMatch() for question-form
    // pairing against the question-form library (only the primary-language copy
    // is used; when a dialogue language is set, follow the setting, otherwise
    // auto-decide by whether the input has Hanzi - see
    // handleSemanticMatch). On a hit, immediately run YouChat's original
    // "TTS-200ms-action" flow.
    // Different from speech/inject: this goes through no robot-body AIDL at all
    // (it does not rely on the "uncertain whether it really triggers
    // recognition" path of speech_startRecognized()/onSpeech()). It is purely
    // local JSON pairing + a direct call to robot.speech_startTTS()/
    // robot.action_PlayActionName(), so no speechReady gate is needed - only
    // that the robot itself has finished initRobot() (done at the very start of
    // onCreate()).
    // The response tells the frontend in real time whether a pair matched
    // (matched/question/type/operation/answer/actionId). No need to wait for a
    // WebSocket event - convenient for the dialogue UI to show the pairing
    // result directly instead of constantly waiting on EventBus.
    //
    // When match() cannot find a question form, it returns a "cannot
    // understand" fallback response (no longer null), so the matched:false
    // branch is only reached by edge cases such as "the input is a blank
    // string".
    public HttpServer.ApiResponse semanticSimulateResponse(Map<String, String> query) {
        String simText = ApiValidator.require(query, "text");
        SemanticMatcherZh.MatchResult simResult = handleSemanticMatch(simText, false);
        if (simResult == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":true,\"matched\":false,\"fallback\":false,\"input\":\"" + MainActivity.jsonSafe(simText) + "\"}");
        }
        // fallback:true = cannot understand, random answer (line pool + random
        // action; TTS/action still run).
        // matched stays true (following the old semantics: if there is anything
        // to answer, it counts as matched). The frontend uses this flag for the
        // short-line silence window (see app-log.js); it does not affect the
        // dialogue display.
        boolean isFallback = !simResult.matched;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"matched\":true,"
                + "\"fallback\":" + (isFallback ? "true" : "false") + ","
                + "\"input\":\"" + MainActivity.jsonSafe(simText) + "\","
                + "\"question\":\"" + MainActivity.jsonSafe(simResult.question) + "\","
                + "\"type\":\"" + MainActivity.jsonSafe(simResult.type) + "\","
                + "\"operation\":\"" + MainActivity.jsonSafe(simResult.operation) + "\","
                + "\"answer\":\"" + MainActivity.jsonSafe(simResult.answer) + "\","
                + "\"actionId\":\"" + MainActivity.jsonSafe(simResult.actionId) + "\"}");
    }
}


