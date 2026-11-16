package es.postureo.lento;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * Lento · pantalla del proyector.
 *
 * Estados:
 *  - "bienvenida": aún no está emparejado. Muestra QR único para emparejar.
 *  - "sinwifi":    ha creado su propia wifi temporal para que el móvil le pase la wifi de casa.
 *  - "conectando": intentando conectarse a la wifi de casa.
 *  - "listo":      emparejado; espera órdenes del móvil (elegir cuento, pausar...).
 *
 * Mando del proyector (solo para pruebas):
 *  - MENÚ (≡): activar/desactivar el modo "sin wifi".
 *  - ATRÁS ×5 seguidas: reinicio de emparejamiento (simula el botón físico de reset).
 */
public class MainActivity extends Activity {

    static final int PUERTO = 8080;
    static final String VERSION = "0.2";

    private WebView web;
    private Dispositivo disp;
    private WifiAyudante wifi;
    private MandoServer servidor;
    private final Handler h = new Handler(Looper.getMainLooper());

    private volatile String modo = "bienvenida";
    private volatile String hsSsid = "", hsClave = "", hsIp = "";
    private volatile String aviso = "";
    private volatile String estadoCuento = "{}";

    private int atras = 0;
    private long primeraAtras = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ocultarBarras();

        disp = new Dispositivo(this);
        wifi = new WifiAyudante(this);
        if (disp.emparejado()) modo = "listo";

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#141a33"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { avisarPantalla(); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Puente(), "Lento");
        setContentView(web);
        web.loadUrl("file:///android_asset/pantalla.html");

        servidor = new MandoServer(this, PUERTO);
        servidor.start();

        // La IP puede cambiar (al conectarse a otra wifi): refrescamos cada 5 s
        h.postDelayed(new Runnable() {
            @Override public void run() { avisarPantalla(); h.postDelayed(this, 5000); }
        }, 5000);
    }

    // ---------- Información del aparato ----------

    String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("serial", disp.serial());
            o.put("clave", disp.clave());
            o.put("nombre", disp.nombre());
            o.put("version", VERSION);
            o.put("modo", modo);
            o.put("emparejado", disp.emparejado());
            o.put("moviles", disp.tokens().size());
            String ip = WifiAyudante.ipLocal();
            o.put("ip", ip == null ? "" : ip);
            o.put("puerto", PUERTO);
            o.put("red", wifi.redActual());
            o.put("aviso", aviso);
            JSONObject hs = new JSONObject();
            hs.put("ssid", hsSsid);
            hs.put("clave", hsClave);
            hs.put("ip", hsIp);
            o.put("hotspot", hs);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Info pública (sin la clave) para quien pregunte desde la red. */
    String infoPublica() {
        try {
            JSONObject o = new JSONObject(info());
            o.remove("clave");
            JSONObject hs = o.optJSONObject("hotspot");
            if (hs != null) hs.remove("clave");
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    void avisarPantalla() {
        js("window.onDispositivo && window.onDispositivo(" + info() + ")");
    }

    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---------- Llamadas desde el móvil (vía MandoServer) ----------

    String emparejar(String clave) {
        String t = disp.emparejar(clave);
        if (t != null) {
            if (!"sinwifi".equals(modo)) modo = "listo";
            aviso = "";
            js("window.onEmparejado && window.onEmparejado()");
            avisarPantalla();
        }
        return t;
    }

    boolean tokenValido(String t) { return disp.tokenValido(t); }

    boolean claveValida(String c) { return c != null && c.equalsIgnoreCase(disp.clave()); }

    void orden(JSONObject o) {
        js("window.onOrden && window.onOrden(" + o.toString() + ")");
    }

    String estadoCuento() { return estadoCuento; }

    org.json.JSONArray redes() { return wifi.buscarRedes(); }

    /** El móvil nos pasa la wifi de casa. Respondemos primero y luego cambiamos de red. */
    void configurarWifi(final String ssid, final String clave) {
        modo = "conectando";
        aviso = ssid;
        avisarPantalla();
        h.postDelayed(() -> {
            wifi.apagarHotspot();
            hsSsid = hsClave = hsIp = "";
            boolean ok = wifi.conectar(ssid, clave);
            if (!ok) {
                modo = disp.emparejado() ? "listo" : "bienvenida";
                aviso = "No he podido conectarme a «" + ssid + "». Revisa la contraseña e inténtalo de nuevo.";
                avisarPantalla();
                return;
            }
            // Esperamos a tener IP en la nueva red
            esperarRed(ssid, 0);
        }, 1500);
    }

    private void esperarRed(final String ssid, final int intento) {
        String ip = WifiAyudante.ipLocal();
        if (ip != null && ssid.equals(wifi.redActual())) {
            modo = disp.emparejado() ? "listo" : "bienvenida";
            aviso = "";
            avisarPantalla();
            js("window.onWifiConectada && window.onWifiConectada()");
            return;
        }
        if (intento > 20) {
            modo = disp.emparejado() ? "listo" : "bienvenida";
            aviso = "No he podido conectarme a «" + ssid + "». Revisa la contraseña e inténtalo de nuevo.";
            avisarPantalla();
            return;
        }
        h.postDelayed(() -> esperarRed(ssid, intento + 1), 1500);
    }

    void cambiarNombre(String n) {
        if (n != null && !n.trim().isEmpty() && n.length() <= 30) disp.nombre(n.trim());
        avisarPantalla();
    }

    /** Reinicio de emparejamiento: olvida móviles y vuelve a la bienvenida. */
    void reiniciar() {
        disp.olvidarTodo();
        wifi.apagarHotspot();
        hsSsid = hsClave = hsIp = "";
        modo = "bienvenida";
        aviso = "";
        js("window.onOrden && window.onOrden({cuento:null,pag:0,sonando:false,luz:false})");
        avisarPantalla();
    }

    // ---------- Modo sin wifi (wifi propia temporal) ----------

    private void modoSinWifi() {
        if (wifi.hotspotEncendido()) {
            wifi.apagarHotspot();
            hsSsid = hsClave = hsIp = "";
            modo = disp.emparejado() ? "listo" : "bienvenida";
            aviso = "";
            avisarPantalla();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 7);
                return; // seguimos en onRequestPermissionsResult
            }
        }
        aviso = "Creando la wifi de Lento…";
        avisarPantalla();
        wifi.encenderHotspot(new WifiAyudante.Respuesta() {
            @Override public void ok(String ssid, String clave, String ip) {
                hsSsid = ssid; hsClave = clave; hsIp = ip;
                modo = "sinwifi"; aviso = "";
                avisarPantalla();
            }
            @Override public void error(String motivo) {
                aviso = "Este aparato no puede crear su propia wifi: " + motivo + ". Usa el modo «misma wifi».";
                avisarPantalla();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) modoSinWifi();
            else { aviso = "Sin permiso de ubicación no puedo crear la wifi de Lento."; avisarPantalla(); }
        }
    }

    // ---------- Puente con la página ----------

    class Puente {
        @JavascriptInterface public String info() { return MainActivity.this.info(); }
        @JavascriptInterface public void setEstado(String json) { estadoCuento = json; }
        @JavascriptInterface public void sinWifi() { runOnUiThread(MainActivity.this::modoSinWifi); }
    }

    // ---------- Mando del proyector ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long ahora = System.currentTimeMillis();
            if (ahora - primeraAtras > 3000) { primeraAtras = ahora; atras = 0; }
            atras++;
            if (atras >= 5) { atras = 0; reiniciar(); }
            else if (atras >= 2) js("window.onAtras && window.onAtras(" + atras + ")");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!disp.emparejado() || wifi.hotspotEncendido()) modoSinWifi();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            js("window.onOk && window.onOk()");
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) ocultarBarras();
    }

    private void ocultarBarras() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (servidor != null) servidor.parar();
        wifi.apagarHotspot();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
