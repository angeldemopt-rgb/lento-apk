package es.postureo.lento;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Identidad del aparato y emparejamientos.
 * En producción, el número de serie y la clave vendrán grabados de fábrica;
 * en esta versión de prueba se generan la primera vez y se guardan.
 */
class Dispositivo {

    private static final String LETRAS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RND = new SecureRandom();
    private final SharedPreferences p;

    Dispositivo(Context c) {
        p = c.getSharedPreferences("lento", Context.MODE_PRIVATE);
        if (!p.contains("serial")) {
            p.edit()
                    .putString("serial", "LNT-" + aleatorio(6))
                    .putString("clave", aleatorio(12))
                    .apply();
        }
    }

    String serial() { return p.getString("serial", ""); }
    String clave() { return p.getString("clave", ""); }
    String nombre() { return p.getString("nombre", "Lento"); }
    void nombre(String n) { p.edit().putString("nombre", n).apply(); }

    synchronized Set<String> tokens() {
        return new HashSet<>(p.getStringSet("tokens", new HashSet<String>()));
    }

    boolean emparejado() { return !tokens().isEmpty(); }

    /** Si la clave es la de este aparato, crea un token para ese móvil. */
    synchronized String emparejar(String clave) {
        if (clave == null || !clave.trim().equalsIgnoreCase(clave())) return null;
        String t = aleatorioHex(24);
        Set<String> ts = tokens();
        ts.add(t);
        p.edit().putStringSet("tokens", ts).apply();
        return t;
    }

    boolean tokenValido(String t) {
        return t != null && !t.isEmpty() && tokens().contains(t);
    }

    synchronized void olvidarTodo() {
        p.edit().remove("tokens").remove("nombre").apply();
    }

    static String aleatorio(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(LETRAS.charAt(RND.nextInt(LETRAS.length())));
        return sb.toString();
    }

    static String aleatorioHex(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(Integer.toHexString(RND.nextInt(16)));
        return sb.toString();
    }
}
