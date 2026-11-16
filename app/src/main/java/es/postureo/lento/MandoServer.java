package es.postureo.lento;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

/**
 * Servidor mínimo dentro del proyector (sin librerías).
 *
 * Páginas:
 *   GET  /              -> app del móvil (mando.html)
 *   GET  /cuentos.js    -> lista de cuentos
 *
 * API (JSON):
 *   GET  /api/info                    -> datos públicos del aparato
 *   POST /api/emparejar {clave}        -> {token}
 *   GET  /api/estado        [token]    -> qué está sonando + info
 *   POST /api/orden         [token]    -> elegir cuento, pausar, luz...
 *   GET  /api/redes   [token o clave]  -> wifis cercanas
 *   POST /api/wifi    [token o clave]  -> {ssid, clave}: conectarse a la wifi de casa
 *   POST /api/nombre        [token]    -> {nombre}
 *   POST /api/olvidar       [token]    -> desemparejar todos los móviles
 *
 * El token va en la cabecera "X-Lento-Token". La clave (del QR) en "X-Lento-Clave".
 */
class MandoServer extends Thread {

    private final MainActivity act;
    private final int puerto;
    private volatile boolean activo = true;
    private ServerSocket ss;

    MandoServer(MainActivity act, int puerto) {
        this.act = act;
        this.puerto = puerto;
        setDaemon(true);
    }

    void parar() {
        activo = false;
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket(puerto);
            while (activo) {
                final Socket s = ss.accept();
                new Thread(() -> atender(s)).start();
            }
        } catch (Exception ignored) { }
    }

    private void atender(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            String cab = leerCabecera(in);
            if (cab == null) { s.close(); return; }
            String[] lineas = cab.split("\r\n");
            String[] primera = lineas[0].split(" ");
            if (primera.length < 2) { s.close(); return; }
            String metodo = primera[0];
            String ruta = primera[1].split("\\?")[0];

            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lineas.length; i++) {
                int p = lineas[i].indexOf(':');
                if (p > 0) h.put(lineas[i].substring(0, p).trim().toLowerCase(Locale.ROOT), lineas[i].substring(p + 1).trim());
            }
            int largo = 0;
            try { largo = Integer.parseInt(h.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
            if (largo > 10_000) largo = 10_000;
            byte[] cuerpo = new byte[Math.max(0, largo)];
            int leido = 0;
            while (leido < largo) {
                int n = in.read(cuerpo, leido, largo - leido);
                if (n < 0) break;
                leido += n;
            }
            JSONObject body;
            try { body = leido > 0 ? new JSONObject(new String(cuerpo, 0, leido, StandardCharsets.UTF_8)) : new JSONObject(); }
            catch (Exception e) { body = new JSONObject(); }

            String token = h.get("x-lento-token");
            String clave = h.get("x-lento-clave");
            boolean conToken = act.tokenValido(token);
            boolean conClave = act.claveValida(clave);

            OutputStream out = s.getOutputStream();

            if (metodo.equals("GET") && (ruta.equals("/") || ruta.equals("/mando") || ruta.equals("/index.html"))) {
                enviar(out, 200, "text/html; charset=utf-8", asset("mando.html"));
            } else if (metodo.equals("GET") && ruta.equals("/cuentos.js")) {
                enviar(out, 200, "application/javascript; charset=utf-8", asset("cuentos.js"));
            } else if (metodo.equals("GET") && ruta.equals("/api/info")) {
                json(out, 200, act.infoPublica());
            } else if (metodo.equals("POST") && ruta.equals("/api/emparejar")) {
                String t = act.emparejar(body.optString("clave"));
                if (t == null) json(out, 403, "{\"error\":\"clave incorrecta\"}");
                else {
                    JSONObject r = new JSONObject(act.infoPublica());
                    r.put("token", t);
                    json(out, 200, r.toString());
                }
            } else if (ruta.startsWith("/api/") && !(conToken || ((ruta.equals("/api/redes") || ruta.equals("/api/wifi")) && conClave))) {
                json(out, 401, "{\"error\":\"no emparejado\"}");
            } else if (metodo.equals("GET") && ruta.equals("/api/estado")) {
                JSONObject r = new JSONObject();
                r.put("cuento", new JSONObject(act.estadoCuento()));
                r.put("info", new JSONObject(act.infoPublica()));
                json(out, 200, r.toString());
            } else if (metodo.equals("POST") && ruta.equals("/api/orden")) {
                act.orden(body);
                json(out, 200, "{\"ok\":true}");
            } else if (metodo.equals("GET") && ruta.equals("/api/redes")) {
                JSONObject r = new JSONObject();
                r.put("redes", act.redes());
                json(out, 200, r.toString());
            } else if (metodo.equals("POST") && ruta.equals("/api/wifi")) {
                String ssid = body.optString("ssid", "");
                if (ssid.isEmpty()) json(out, 400, "{\"error\":\"falta la red\"}");
                else {
                    json(out, 200, "{\"ok\":true}");
                    act.configurarWifi(ssid, body.optString("clave", ""));
                }
            } else if (metodo.equals("POST") && ruta.equals("/api/nombre")) {
                act.cambiarNombre(body.optString("nombre"));
                json(out, 200, "{\"ok\":true}");
            } else if (metodo.equals("POST") && ruta.equals("/api/olvidar")) {
                json(out, 200, "{\"ok\":true}");
                act.reiniciar();
            } else {
                enviar(out, 404, "text/plain; charset=utf-8", "No encontrado".getBytes(StandardCharsets.UTF_8));
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String leerCabecera(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, estado = 0;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (b.size() > 16_000) return null;
            if ((estado == 0 || estado == 2) && c == '\r') estado++;
            else if ((estado == 1 || estado == 3) && c == '\n') estado++;
            else estado = (c == '\r') ? 1 : 0;
            if (estado == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private byte[] asset(String nombre) throws Exception {
        try (InputStream i = act.getAssets().open(nombre)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void json(OutputStream out, int codigo, String s) throws Exception {
        enviar(out, codigo, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
    }

    private static void enviar(OutputStream out, int codigo, String tipo, byte[] cuerpo) throws Exception {
        String txt = codigo == 200 ? "OK" : codigo == 401 ? "Unauthorized" : codigo == 403 ? "Forbidden" : codigo == 400 ? "Bad Request" : "Not Found";
        String cab = "HTTP/1.1 " + codigo + " " + txt + "\r\n"
                + "Content-Type: " + tipo + "\r\n"
                + "Content-Length: " + cuerpo.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(cab.getBytes(StandardCharsets.UTF_8));
        out.write(cuerpo);
        out.flush();
    }
}
