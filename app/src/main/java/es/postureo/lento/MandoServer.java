package es.postureo.lento;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Servidor mínimo dentro del proyector (sin librerías).
 *   GET  /            -> mando.html (lo que ve el móvil)
 *   GET  /cuentos.js  -> lista de cuentos
 *   GET  /estado      -> qué está sonando ahora (JSON)
 *   POST /orden       -> el móvil manda una orden (JSON)
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
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            String cabecera = leerCabecera(in);
            if (cabecera == null) { s.close(); return; }
            String[] lineas = cabecera.split("\r\n");
            String[] primera = lineas[0].split(" ");
            if (primera.length < 2) { s.close(); return; }
            String metodo = primera[0];
            String ruta = primera[1].split("\\?")[0];

            int largo = 0;
            for (String l : lineas) {
                if (l.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try { largo = Integer.parseInt(l.substring(15).trim()); } catch (Exception ignored) { }
                }
            }
            if (largo > 10_000) largo = 10_000;

            OutputStream out = s.getOutputStream();
            if (metodo.equals("POST") && ruta.equals("/orden")) {
                byte[] cuerpo = new byte[largo];
                int leido = 0;
                while (leido < largo) {
                    int n = in.read(cuerpo, leido, largo - leido);
                    if (n < 0) break;
                    leido += n;
                }
                act.recibirOrden(new String(cuerpo, 0, leido, StandardCharsets.UTF_8));
                responder(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } else if (metodo.equals("GET") && ruta.equals("/estado")) {
                responder(out, 200, "application/json", act.estado().getBytes(StandardCharsets.UTF_8));
            } else if (metodo.equals("GET") && (ruta.equals("/") || ruta.equals("/mando"))) {
                responder(out, 200, "text/html; charset=utf-8", asset("mando.html"));
            } else if (metodo.equals("GET") && ruta.equals("/cuentos.js")) {
                responder(out, 200, "application/javascript; charset=utf-8", asset("cuentos.js"));
            } else {
                responder(out, 404, "text/plain", "No encontrado".getBytes(StandardCharsets.UTF_8));
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
            else estado = 0;
            if (estado == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private byte[] asset(String nombre) throws Exception {
        AssetManager am = act.getAssets();
        try (InputStream i = am.open(nombre)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void responder(OutputStream out, int codigo, String tipo, byte[] cuerpo) throws Exception {
        String txt = codigo == 200 ? "OK" : "Not Found";
        String h = "HTTP/1.1 " + codigo + " " + txt + "\r\n"
                + "Content-Type: " + tipo + "\r\n"
                + "Content-Length: " + cuerpo.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(cuerpo);
        out.flush();
    }
}
