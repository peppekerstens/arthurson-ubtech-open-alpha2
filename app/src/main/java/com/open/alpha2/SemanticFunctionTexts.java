package com.open.alpha2;

/**
 * The FUNCTION reply texts of the semantic match (the spoken text after a
 * function fails - bluetooth, wifi, camera - or plays a local song).
 *
 * Pure Java so the text pick is host-testable (alpha2-brain#95, step 3):
 * with the English voice path the spoken text is English; the Chinese texts
 * stay for the Chinese dialogue language. The table is the old
 * SemanticCenter.functionText() body, moved unchanged (same spoken text,
 * same English fallback for a dialogue language that is not in the table;
 * musicNow uses a %s placeholder instead of concatenating the name).
 */
public final class SemanticFunctionTexts {
    /** The reply keys (the operation failures and the now-playing song). */
    public static final String[] KEYS = {
            "btOnFail", "btOffFail", "wifiOnFail", "wifiOffFail",
            "camNotReady", "camFail", "musicNotReady", "musicEmpty", "musicNow"
    };
    // One row per language, the English row first (it is also the fallback
    // for a dialogue language that is not in the table). The musicNow cell
    // carries a %s for the song name (the caller strips the file suffix).
    private static final String[][] TABLE = {
        {"en", "Sorry, I couldn't turn on Bluetooth.", "Sorry, I couldn't turn off Bluetooth.", "Sorry, I couldn't turn on Wi-Fi.", "Sorry, I couldn't turn off Wi-Fi.", "Sorry, the camera isn't ready.", "Sorry, I couldn't take the picture.", "Sorry, the music player isn't ready.", "There are no songs in the music folder.", "Okay, now playing %s."},
        {"es", "Lo siento, no pude activar el Bluetooth.", "Lo siento, no pude desactivar el Bluetooth.", "Lo siento, no pude activar la red Wi-Fi.", "Lo siento, no pude desactivar la red Wi-Fi.", "Lo siento, la cámara no está lista.", "Lo siento, no pude tomar la foto.", "Lo siento, la música no está lista.", "No hay canciones en la carpeta de música.", "De acuerdo, reproduciendo %s."},
        {"fr", "Désolé, impossible d'activer le Bluetooth.", "Désolé, impossible de désactiver le Bluetooth.", "Désolé, impossible d'activer le Wi-Fi.", "Désolé, impossible de désactiver le Wi-Fi.", "Désolé, l'appareil photo n'est pas prêt.", "Désolé, la photo a échoué.", "Désolé, la musique n'est pas prête.", "Il n'y a aucune chanson dans le dossier de musique.", "D'accord, je joue %s."},
        {"ja", "すみません、Bluetoothをオンにできませんでした。", "すみません、Bluetoothをオフにできませんでした。", "すみません、Wi-Fiをオンにできませんでした。", "すみません、Wi-Fiをオフにできませんでした。", "すみません、カメラの準備ができていません。", "すみません、撮影に失敗しました。", "すみません、音楽の準備ができていません。", "音楽フォルダに曲がありません。", "わかりました、「%s」を再生します。"},
        {"de", "Leider konnte ich Bluetooth nicht einschalten.", "Leider konnte ich Bluetooth nicht ausschalten.", "Leider konnte ich WLAN nicht einschalten.", "Leider konnte ich WLAN nicht ausschalten.", "Leider ist die Kamera nicht bereit.", "Leider hat das Foto nicht geklappt.", "Leider ist die Musik nicht bereit.", "Im Musikordner sind keine Lieder.", "Verstanden, ich spiele jetzt %s."},
        {"it", "Mi dispiace, non riesco ad attivare il Bluetooth.", "Mi dispiace, non riesco a disattivare il Bluetooth.", "Mi dispiace, non riesco ad attivare il Wi-Fi.", "Mi dispiace, non riesco a disattivare il Wi-Fi.", "Mi dispiace, la fotocamera non è pronta.", "Mi dispiace, la foto non è riuscita.", "Mi dispiace, la musica non è pronta.", "Non ci sono canzoni nella cartella della musica.", "Va bene, riproduco %s."},
        {"pt", "Desculpe, não consegui ativar o Bluetooth.", "Desculpe, não consegui desativar o Bluetooth.", "Desculpe, não consegui ativar o Wi-Fi.", "Desculpe, não consegui desativar o Wi-Fi.", "Desculpe, a câmera não está pronta.", "Desculpe, não consegui tirar a foto.", "Desculpe, a música não está pronta.", "Não há músicas na pasta de música.", "Está bem, tocando %s."},
        {"ko", "죄송합니다, 블루투스를 켤 수 없습니다.", "죄송합니다, 블루투스를 끌 수 없습니다.", "죄송합니다, 와이파이를 켤 수 없습니다.", "죄송합니다, 와이파이를 끌 수 없습니다.", "죄송합니다, 카메라가 준비되지 않았습니다.", "죄송합니다, 사진 촬영에 실패했습니다.", "죄송합니다, 음악이 준비되지 않았습니다.", "음악 폴더에 노래가 없습니다.", "알겠습니다, %s 재생합니다."},
        {"ru", "Извините, не получается включить Bluetooth.", "Извините, не получается выключить Bluetooth.", "Извините, не получается включить Wi-Fi.", "Извините, не получается выключить Wi-Fi.", "Извините, камера не готова.", "Извините, не получилось сделать снимок.", "Извините, музыка не готова.", "В папке с музыкой нет песен.", "Хорошо, сейчас играет %s."},
        {"zh", "抱歉，無法打開藍牙。", "抱歉，無法關閉藍牙。", "抱歉，無法打開無線網路。", "抱歉，無法關閉無線網路。", "抱歉，相機尚未準備就緒。", "抱歉，照片拍攝失敗。", "抱歉，音樂尚未準備就緒。", "音樂資料夾中沒有歌曲。", "好的，正在播放%s。"}
    };

    private SemanticFunctionTexts() {}

    /** The reply text for the dialogue language: null for an unknown key. */
    public static String text(String lang, String key, String name) {
        int ki = keyIndex(key);
        if (ki < 0) return null;
        String t = row(lang)[ki + 1];
        if (t.indexOf("%s") >= 0 && name != null) t = t.replace("%s", name);
        return t;
    }

    /** The column of a key in KEYS, -1 when it is not one of them. */
    private static int keyIndex(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    /** The row of the dialogue language, the English row when it is not in the table. */
    private static String[] row(String lang) {
        for (String[] r : TABLE) {
            if (r[0].equals(lang)) return r;
        }
        return TABLE[0];
    }
}
