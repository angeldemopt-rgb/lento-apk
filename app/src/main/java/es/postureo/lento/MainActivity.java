package es.postureo.lento;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * Pantalla de Lento en el proyector.
 * - Muestra assets/pantalla.html a pantalla completa.
 * - Arranca un pequeño servidor (MandoServer) para que el móvil haga de mando.
 * - Lee los cuentos con la voz del sistema (TextToSpeech).
 */
public class MainActivity extends Activity {

    public static final int PUERTO = 8080;

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsListo = false;
    private MandoServer servidor;
    private volatile String estadoJson = "{}";

    // Salida secreta: pulsar ATRÁS 5 veces en 3 segundos abre los Ajustes de Android
    private int pulsacionesAtras = 0;
    private long primeraAtras = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ocultarBarras();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#141a33"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Puente(), "Lento");
        setContentView(web);
        web.loadUrl("file:///android_asset/pantalla.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "ES"));
                if (r < 0) tts.setLanguage(new Locale("es"));
                tts.setSpeechRate(0.85f);
                tts.setPitch(1.05f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { js("window.onHablaFin && window.onHablaFin()"); }
                    @Override public void onError(String id) { js("window.onHablaFin && window.onHablaFin()"); }
                });
                ttsListo = true;
            }
        });

        servidor = new MandoServer(this, PUERTO);
        servidor.start();
    }

    /** Llamado por el servidor cuando el móvil manda una orden. */
    void recibirOrden(String cuerpo) {
        try {
            JSONObject o = new JSONObject(cuerpo); // valida que es JSON antes de pasarlo a la página
            js("window.onOrden && window.onOrden(" + o.toString() + ")");
        } catch (Exception ignored) { }
    }

    String estado() { return estadoJson; }

    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /** Lo que la página puede pedirle a Android. */
    class Puente {
        @JavascriptInterface public void hablar(String texto) {
            if (!ttsListo) { js("window.onHablaFin && window.onHablaFin()"); return; }
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "lento");
        }
        @JavascriptInterface public void callar() { if (ttsListo) tts.stop(); }
        @JavascriptInterface public boolean hayVoz() { return ttsListo; }
        @JavascriptInterface public String direccion() {
            String ip = ipLocal();
            return ip == null ? "" : "http://" + ip + ":" + PUERTO + "/";
        }
        @JavascriptInterface public void setEstado(String json) { estadoJson = json; }
    }

    static String ipLocal() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long ahora = System.currentTimeMillis();
            if (ahora - primeraAtras > 3000) { primeraAtras = ahora; pulsacionesAtras = 0; }
            pulsacionesAtras++;
            if (pulsacionesAtras >= 5) {
                pulsacionesAtras = 0;
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
            return true; // ATRÁS normal no saca de Lento
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ocultarBarras();
    }

    private void ocultarBarras() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onDestroy() {
        if (servidor != null) servidor.parar();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
