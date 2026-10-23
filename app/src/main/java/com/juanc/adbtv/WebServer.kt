package com.juanc.adbtv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminal web en la red local. Todas las rutas /api/ exigen el PIN que se muestra en el TV
 * (cabecera X-Token, o parámetro "t" en las descargas GET). Tras 10 intentos fallidos una IP
 * queda bloqueada 60 s.
 */
class WebServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" || uri == "/index.html" -> asset("index.html", "text/html; charset=utf-8")
                uri.startsWith("/api/") -> api(session)
                else -> text(Response.Status.NOT_FOUND, "No encontrado")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: e.toString()))
        }
    }

    private fun api(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "?"
        val blocked = failures[ip]?.let { (n, t) -> n >= 10 && System.currentTimeMillis() - t < 60_000 } == true
        if (blocked) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "Demasiados intentos, espera 60 s"))

        val token = session.headers["x-token"] ?: session.parameters["t"]?.firstOrNull()
        if (token != Prefs.pin(ctx)) {
            val prev = failures[ip]?.first ?: 0
            failures[ip] = (prev + 1) to System.currentTimeMillis()
            AppLog.i("PIN incorrecto desde $ip")
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "PIN incorrecto"))
        }
        failures.remove(ip)

        val files = HashMap<String, String>()
        if (session.method == Method.POST) session.parseBody(files)
        fun param(name: String) = session.parameters[name]?.firstOrNull()?.trim().orEmpty()

        return when (session.uri) {
            "/api/status" -> json(Response.Status.OK, status())
            "/api/exec" -> {
                val cmd = param("cmd")
                if (cmd.isEmpty()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Comando vacío"))
                val timeout = param("timeout").toLongOrNull()?.coerceIn(1, 600) ?: 30
                AppLog.i("[$ip] \$ $cmd")
                result(AdbBridge.exec(ctx, cmd, timeout))
            }
            "/api/install" -> {
                // NanoHTTPD borra sus temporales al responder: los copiamos primero
                val uploads = files.keys.filter { it.startsWith("apk") }.sorted().mapIndexed { i, key ->
                    File(ctx.cacheDir, "upload_$i.apk").also { File(files.getValue(key)).copyTo(it, overwrite = true) }
                }
                if (uploads.isEmpty()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Falta el archivo"))
                // .apks / .xapk / .zip: se descomprimen y se instalan todas las partes
                val bundleDir = File(ctx.cacheDir, "bundle")
                var apks = uploads
                val notes = StringBuilder()
                if (uploads.size == 1 && ApkBundle.isBundle(uploads[0])) {
                    val ex = ApkBundle.extract(uploads[0], bundleDir)
                    if (ex.apks.isEmpty()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "El paquete no contiene APKs"))
                    apks = ex.apks
                    notes.append("Paquete con ${ex.apks.size} APK")
                    ex.obbs.forEach { (f, dest) ->
                        AdbBridge.exec(ctx, "mkdir -p ${FileManager.q(dest.substringBeforeLast('/'))}", 10)
                        AdbBridge.client(ctx)?.push(f, dest, 438, System.currentTimeMillis())
                    }
                    if (ex.obbs.isNotEmpty()) notes.append(" y ${ex.obbs.size} OBB copiados")
                    notes.append("\n")
                }
                AppLog.i("[$ip] instalando ${apks.size} APK (${apks.sumOf { it.length() } / 1024} KB)…")
                val r = AdbBridge.install(ctx, apks, downgrade = param("downgrade") == "1")
                uploads.forEach { it.delete() }
                bundleDir.deleteRecursively()
                AppLog.i(r.output.lineSequence().first())
                result(r.copy(output = notes.toString() + r.output))
            }
            "/api/apps" -> newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", AppManager.list(ctx).toString()
            )
            "/api/app" -> result(AppManager.action(ctx, param("pkg"), param("action"), force = param("force") == "1"))
            "/api/icon" -> {
                val png = AppManager.icon(ctx, param("pkg")) ?: return text(Response.Status.NOT_FOUND, "")
                newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
                    .apply { addHeader("Cache-Control", "max-age=3600") }
            }
            "/api/apk" -> {
                val pkg = param("pkg")
                val apks = try {
                    if (AppManager.isValidPackage(pkg)) ApkBundle.installedApks(ctx, pkg).filter { it.canRead() } else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                if (apks.isEmpty()) return text(Response.Status.NOT_FOUND, "APK no disponible")
                AppLog.i("[$ip] descargando APK de $pkg (${apks.size} partes)")
                if (apks.size == 1) {
                    newFixedLengthResponse(
                        Response.Status.OK, "application/vnd.android.package-archive", FileInputStream(apks[0]), apks[0].length()
                    ).apply { addHeader("Content-Disposition", "attachment; filename=\"$pkg.apk\"") }
                } else {
                    // App dividida: un .apks con base + splits, instalable desde la pestaña Apps
                    newChunkedResponse(Response.Status.OK, "application/zip", ApkBundle.zipStream(apks))
                        .apply { addHeader("Content-Disposition", "attachment; filename=\"$pkg.apks\"") }
                }
            }
            // ---- API estable para integraciones (Home Assistant, scripts…) ----
            "/api/info" -> {
                val screen = AppManager.screen(ctx)
                val fg = screen.foreground
                val label = fg?.let { p -> AppManager.launchables(ctx).firstOrNull { it.first == p }?.second }
                json(Response.Status.OK, DeviceInfo.json(ctx).put("foreground", fg).put("foregroundLabel", label)
                    .put("screenOn", screen.screenOn).put("adbConnected", AdbBridge.isConnected)
                    .put("mode", AdbBridge.mode(ctx)))
            }
            "/api/launchable" -> {
                val arr = org.json.JSONArray()
                AppManager.launchables(ctx).forEach { (p, l) -> arr.put(JSONObject().put("pkg", p).put("label", l)) }
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", arr.toString())
            }
            "/api/launch" -> {
                val app = AppManager.find(ctx, param("app"))
                    ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "No encuentro la app '${param("app")}'"))
                // dry=1: solo resuelve el nombre, sin abrir nada
                if (param("dry") == "1") return json(Response.Status.OK, JSONObject().put("pkg", app.first).put("label", app.second))
                val r = AppManager.action(ctx, app.first, "launch", false)
                AppLog.i("[$ip] abrir ${app.second}")
                json(Response.Status.OK, JSONObject().put("pkg", app.first).put("label", app.second)
                    .put("exit", r.exitCode).put("output", r.output))
            }
            "/api/key" -> {
                val key = param("key").uppercase().removePrefix("KEYCODE_")
                if (!Regex("^[A-Z0-9_]{1,40}$").matches(key)) {
                    return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Tecla no válida"))
                }
                val times = param("times").toIntOrNull()?.coerceIn(1, 50) ?: 1
                if (AdbBridge.client(ctx) == null) return result(NativeControl.key(ctx, key, times))
                result(AdbBridge.exec(ctx, (1..times).joinToString("; ") { "input keyevent KEYCODE_$key" }, 30))
            }
            "/api/fs/places" ->newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", FileManager.places(ctx).toString()
            )
            "/api/fs/list" -> json(Response.Status.OK, FileManager.list(ctx, param("path").ifEmpty { "/sdcard" }))
            "/api/fs/get" -> {
                val path = FileManager.normalize(param("path"))
                val name = path.substringAfterLast('/')
                if (AdbBridge.exec(ctx, "[ -f ${FileManager.q(path)} ] && [ -r ${FileManager.q(path)} ]", 10).exitCode != 0) {
                    return text(Response.Status.NOT_FOUND, "No existe o no se puede leer: $path")
                }
                val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                AppLog.i("[$ip] descargando $path")
                newChunkedResponse(Response.Status.OK, mime, FileManager.open(ctx, path)).apply {
                    val disposition = if (param("inline") == "1") "inline" else "attachment"
                    val ascii = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    val utf8 = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    addHeader("Content-Disposition", "$disposition; filename=\"$ascii\"; filename*=UTF-8''$utf8")
                }
            }
            "/api/fs/upload" -> {
                val dir = FileManager.normalize(param("dir"))
                val keys = files.keys.filter { it.startsWith("file") }.sorted()
                if (keys.isEmpty()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "No hay archivos"))
                // NanoHTTPD corta el filename en el primer apóstrofo y no decodifica UTF-8, así que
                // la web envía cada nombre aparte (name000…) codificado con encodeURIComponent
                val names = keys.map { k ->
                    param("name" + k.removePrefix("file")).takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
                        ?: session.parameters[k]?.firstOrNull().orEmpty()
                }
                keys.zip(names).forEach { (k, n) -> FileManager.upload(ctx, dir, n, File(files.getValue(k))) }
                AppLog.i("[$ip] subidos ${keys.size} archivo(s) a $dir")
                result(ExecResult("Subidos ${keys.size} archivo(s) a $dir\n", 0, "adb"))
            }
            "/api/fs/op" -> {
                val p = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
                result(FileManager.op(ctx, param("op"), p))
            }
            else -> text(Response.Status.NOT_FOUND, "No encontrado")
        }
    }

    private fun status() = JSONObject()
        .put("mode", AdbBridge.mode(ctx))
        .put("adbBlocked", AdbBridge.blockedBySystem)
        .put("permissions", Permissions.json(ctx))
        .put("setupCommand", "adb shell \"${Permissions.setupCommand(ctx)}\"")
        .put("adbConnected", AdbBridge.isConnected)
        .put("adbPortOpen", AdbBridge.isAdbPortOpen())
        .put("ip", NetUtils.localIp())
        .put("model", DeviceInfo.modelName())
        .put("android", android.os.Build.VERSION.RELEASE)

    private fun result(r: ExecResult) = json(
        Response.Status.OK, JSONObject().put("output", r.output).put("exit", r.exitCode).put("via", r.via)
    )

    private fun asset(name: String, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, mime, ctx.assets.open(name).bufferedReader().readText())

    private fun text(status: Response.Status, body: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun json(status: Response.Status, body: JSONObject) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
}
