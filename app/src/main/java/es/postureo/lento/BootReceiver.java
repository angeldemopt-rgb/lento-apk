package es.postureo.lento;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Abre Lento cuando el proyector termina de encenderse. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
    }
}
