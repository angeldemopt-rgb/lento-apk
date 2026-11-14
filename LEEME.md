# Lento · APK de prueba para proyector

Prototipo: el proyector abre Lento solo y el móvil hace de mando por la wifi de casa.

## Qué hace
- **Proyector:** pantalla de Lento a pantalla completa, con cielo de estrellas y un QR.
- **Móvil:** al escanear el QR se abre el mando (sin instalar nada). Eliges un cuento, y puedes pausar, avanzar, retroceder, terminar y bajar la luz (🌙).
- La voz es la del propio Android del proyector (TextToSpeech, español).
- Arranca sola al encender y puede ser la **pantalla de inicio** (botón Home).
- **Salida secreta:** pulsa ATRÁS 5 veces seguidas para abrir los Ajustes de Android.

## Cómo funciona por dentro
- `MainActivity.java`: muestra `assets/pantalla.html` y le da voz.
- `MandoServer.java`: un pequeño servidor en el puerto **8080** del proyector. El móvil abre `http://IP-del-proyector:8080/`.
- `BootReceiver.java`: abre Lento al encender.
- `assets/cuentos.js`: los cuentos (de prueba). Aquí irían los de verdad.

## Compilar (sin instalar nada en el Mac)
1. Crea un repositorio **privado** en GitHub y sube esta carpeta.
2. GitHub compila solo (pestaña **Actions**, unos 4 minutos).
3. La APK sale en **Releases → «Lento (prueba)» → Lento.apk**.

> Ojo: si el repositorio es privado, Downloader no podrá bajar el archivo con el enlace directo. Bájalo en el Mac y pásalo por USB, o haz público solo ese release.

## Instalar en el proyector
- **USB:** copia `Lento.apk` a un pendrive → enchúfalo al proyector → ábrelo con el gestor de archivos → Instalar.
- **Downloader:** escribe la URL de la APK → Instalar.
- Si pide permiso para «orígenes desconocidos», actívalo solo para esa app.

## Que se abra sola
- Al pulsar Home la primera vez, el proyector puede preguntar qué pantalla de inicio usar → elige **Lento** → «Siempre».
- Si no pregunta, en Ajustes → Apps → Apps predeterminadas → Pantalla de inicio → Lento.
- Si la capa de Einyoumily no deja cambiarla, Lento se abrirá igualmente al encender (si el sistema lo permite) o desde el menú de apps.

## Desinstalar
Ajustes → Apps → Lento → Desinstalar. El proyector vuelve a como estaba. No se toca nada del sistema.
