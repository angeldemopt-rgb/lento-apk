package es.postureo.lento;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Todo lo de wifi:
 *  - wifi propia temporal (para configurar sin internet),
 *  - buscar redes,
 *  - conectarse a la wifi de casa.
 * En el aparato final esto lo hará la app con permisos de sistema;
 * aquí usamos lo que Android deja a una app normal (puede no funcionar en todos los aparatos).
 */
class WifiAyudante {

    interface Respuesta {
        void ok(String ssid, String clave, String ip);
        void error(String motivo);
    }

    private final WifiManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Object reserva; // LocalOnlyHotspotReservation (API 26+)

    WifiAyudante(Context c) {
        wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    boolean hotspotEncendido() { return reserva != null; }

    void encenderHotspot(final Respuesta r) {
        if (Build.VERSION.SDK_INT < 26) { r.error("este Android es demasiado antiguo para crear su propia wifi"); return; }
        if (wm == null) { r.error("este aparato no tiene wifi"); return; }
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reserva = res;
                    String ssid = "", clave = "";
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            SoftApConfiguration c = res.getSoftApConfiguration();
                            ssid = c.getSsid();
                            clave = c.getPassphrase();
                        } else {
                            WifiConfiguration c = res.getWifiConfiguration();
                            ssid = c.SSID;
                            clave = c.preSharedKey;
                        }
                    } catch (Exception ignored) { }
                    final String s = limpiar(ssid), k = limpiar(clave);
                    // La IP de la wifi propia tarda un poco en aparecer
                    h.postDelayed(() -> r.ok(s, k, ipHotspot()), 2000);
                }
                @Override
                public void onFailed(int reason) {
                    reserva = null;
                    String m = reason == 1 ? "no hay canal libre" : reason == 2 ? "modo no compatible (la wifi del aparato no lo permite)"
                            : reason == 3 ? "lo impide otra app o la wifi compartida" : "error " + reason;
                    r.error(m);
                }
                @Override
                public void onStopped() { reserva = null; }
            }, h);
        } catch (SecurityException e) {
            r.error("falta el permiso de ubicación (o la ubicación está desactivada)");
        } catch (Throwable e) {
            r.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void apagarHotspot() {
        if (reserva != null && Build.VERSION.SDK_INT >= 26) {
            try { ((WifiManager.LocalOnlyHotspotReservation) reserva).close(); } catch (Exception ignored) { }
        }
        reserva = null;
    }

    /** Redes wifi cercanas, sin repetir, ordenadas por señal. */
    JSONArray buscarRedes() {
        JSONArray out = new JSONArray();
        if (wm == null) return out;
        try {
            wm.startScan();
            List<ScanResult> rs = wm.getScanResults();
            Map<String, Integer> mejor = new HashMap<>();
            Map<String, Boolean> abierta = new HashMap<>();
            for (ScanResult s : rs) {
                if (s.SSID == null || s.SSID.isEmpty()) continue;
                Integer prev = mejor.get(s.SSID);
                if (prev == null || s.level > prev) {
                    mejor.put(s.SSID, s.level);
                    String cap = s.capabilities == null ? "" : s.capabilities;
                    abierta.put(s.SSID, !(cap.contains("WPA") || cap.contains("WEP") || cap.contains("SAE")));
                }
            }
            List<Map.Entry<String, Integer>> l = new ArrayList<>(mejor.entrySet());
            Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<String, Integer> e : l) {
                JSONObject o = new JSONObject();
                o.put("ssid", e.getKey());
                o.put("senal", WifiManager.calculateSignalLevel(e.getValue(), 4));
                o.put("abierta", abierta.get(e.getKey()));
                out.put(o);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Conecta a la wifi de casa (API clásica: funciona porque la app apunta a Android 9). */
    @SuppressWarnings("deprecation")
    boolean conectar(String ssid, String clave) {
        if (wm == null || ssid == null || ssid.isEmpty()) return false;
        try {
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + ssid + "\"";
            if (clave == null || clave.isEmpty()) {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                c.preSharedKey = "\"" + clave + "\"";
            }
            int id = wm.addNetwork(c);
            if (id < 0) return false;
            wm.disconnect();
            boolean ok = wm.enableNetwork(id, true);
            wm.reconnect();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    String redActual() {
        try {
            String s = wm.getConnectionInfo().getSSID();
            s = limpiar(s);
            return "<unknown ssid>".equals(s) ? "" : s;
        } catch (Exception e) { return ""; }
    }

    private static String limpiar(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    /** IP del aparato en la wifi de casa (o por cable). */
    static String ipLocal() {
        String otra = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (n.startsWith("wlan0") || n.startsWith("eth")) return a.getHostAddress();
                    if (!esHotspot(n)) otra = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return otra;
    }

    /** IP del aparato dentro de su propia wifi temporal. */
    static String ipHotspot() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !esHotspot(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "192.168.43.1";
    }

    private static boolean esHotspot(String n) {
        return n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.equals("wlan1");
    }
}
