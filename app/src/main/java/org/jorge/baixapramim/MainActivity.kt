package org.jorge.baixapramim

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * JORGE BAIXA PRA MIM - versão Android nativa.
 * Usa o yt-dlp e o FFmpeg embutidos no app (biblioteca youtubedl-android),
 * então consegue juntar vídeo e áudio e oferecer todas as qualidades.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var pasteBtn: MaterialButton
    private lateinit var searchBtn: MaterialButton
    private lateinit var titleLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var modeVideo: MaterialButton
    private lateinit var modeAudio: MaterialButton
    private lateinit var qualitySpinner: Spinner
    private lateinit var downloadBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var statusLabel: TextView

    private val processId = "jorge_download"

    private var ready = false
    private var busy = false
    private var cancelled = false
    private var audioMode = false
    private var videoUrl: String? = null
    private var heights: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        searchBtn = findViewById(R.id.searchBtn)
        titleLabel = findViewById(R.id.titleLabel)
        metaLabel = findViewById(R.id.metaLabel)
        modeVideo = findViewById(R.id.modeVideo)
        modeAudio = findViewById(R.id.modeAudio)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        downloadBtn = findViewById(R.id.downloadBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        progress = findViewById(R.id.progress)
        statusLabel = findViewById(R.id.statusLabel)

        pasteBtn.setOnClickListener { onPaste() }
        searchBtn.setOnClickListener { onSearch() }
        downloadBtn.setOnClickListener { onDownload() }
        cancelBtn.setOnClickListener { onCancel() }
        modeVideo.setOnClickListener { setMode(false) }
        modeAudio.setOnClickListener { setMode(true) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onSearch()
                true
            } else false
        }

        setMode(false)
        refreshQuality()
        initLibrary()
    }

    // ---------- Preparação (extrai o Python/yt-dlp/FFmpeg na primeira vez) ----------
    private fun initLibrary() {
        setBusy(true)
        setStatus("Preparando o programa (a primeira vez demora um pouco)…")
        thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                runOnUiThread {
                    setBusy(false)
                    setStatus("Pronto para começar.")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    setStatus("Falha ao preparar o programa: ${t.message}", true)
                }
            }
        }
    }

    // ---------- Interface ----------
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun setStatus(text: String, error: Boolean = false, ok: Boolean = false) {
        statusLabel.text = text
        statusLabel.setTextColor(
            color(
                when {
                    error -> R.color.err
                    ok -> R.color.ok
                    else -> R.color.muted
                }
            )
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        val enabled = !value && ready
        searchBtn.isEnabled = enabled
        pasteBtn.isEnabled = enabled
        modeVideo.isEnabled = !value
        modeAudio.isEnabled = !value
        downloadBtn.isEnabled = enabled && videoUrl != null
        downloadBtn.alpha = if (downloadBtn.isEnabled) 1f else 0.45f
        searchBtn.alpha = if (searchBtn.isEnabled) 1f else 0.45f
        pasteBtn.alpha = if (pasteBtn.isEnabled) 1f else 0.45f
        qualitySpinner.isEnabled = !value && !audioMode && heights.isNotEmpty()
    }

    private fun setMode(audio: Boolean) {
        if (busy && ready) return
        audioMode = audio
        paint(modeVideo, !audio)
        paint(modeAudio, audio)
        refreshQuality()
    }

    private fun paint(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList =
            ColorStateList.valueOf(color(if (selected) R.color.accent else R.color.soft))
        button.setTextColor(color(if (selected) R.color.white else R.color.ink))
    }

    private fun qualityLabel(h: Int): String = when (h) {
        4320 -> "8K (4320p)"
        2160 -> "4K (2160p)"
        1440 -> "2K (1440p)"
        1080 -> "Full HD (1080p)"
        720 -> "HD (720p)"
        else -> "${h}p"
    }

    private fun refreshQuality() {
        val labels: List<String> = when {
            audioMode -> listOf("Melhor qualidade (MP3 192 kbps)")
            heights.isNotEmpty() -> heights.map { qualityLabel(it) }
            videoUrl != null -> listOf("Melhor disponível")
            else -> listOf("Busque um vídeo primeiro")
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        qualitySpinner.adapter = adapter
        qualitySpinner.isEnabled = !busy && !audioMode && heights.isNotEmpty()
    }

    private fun fmtDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun friendlyError(raw: String?): String {
        val clean = (raw ?: "").replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
        val line = clean.lines().lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?: clean.lines().lastOrNull { it.isNotBlank() }
            ?: "Erro desconhecido."
        val msg = line.replace("ERROR: ", "").trim()
        val low = msg.lowercase()
        return when {
            "private video" in low -> "Este vídeo é privado e não pode ser baixado."
            "sign in" in low || "confirm you" in low || "not a bot" in low ->
                "O site pediu login ou verificação para liberar este vídeo."
            "unsupported url" in low -> "Link não suportado. Confira se copiou o endereço completo."
            "drm" in low -> "Este site usa proteção DRM e não pode ser baixado."
            "video unavailable" in low || "has been removed" in low -> "Vídeo indisponível ou removido."
            "unable to resolve host" in low || "urlopen error" in low || "timed out" in low ->
                "Sem conexão com a internet. Verifique sua rede e tente de novo."
            else -> msg.take(220)
        }
    }

    // ---------- Ações ----------
    private fun onPaste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            setStatus("A área de transferência está vazia.", error = true)
            return
        }
        urlInput.setText(text)
        onSearch()
    }

    private fun onSearch() {
        if (busy || !ready) return
        var url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            setStatus("Cole um link primeiro.", error = true)
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
            urlInput.setText(url)
        }

        progress.progress = 0
        setBusy(true)
        setStatus("Buscando informações do vídeo…")
        val finalUrl = url

        thread {
            try {
                val request = YoutubeDLRequest(finalUrl)
                request.addOption("--dump-single-json")
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                val response = YoutubeDL.getInstance().execute(request)

                val text = response.out ?: ""
                val start = text.indexOf('{')
                if (start < 0) throw RuntimeException("Não foi possível ler as informações do vídeo.")
                val json = JSONObject(text.substring(start))

                val title = json.optString("title", "Vídeo sem título")
                val channel = json.optString("uploader", json.optString("channel", ""))
                    .takeIf { it != "null" } ?: ""
                val duration = json.optDouble("duration", 0.0).toInt()

                val found = sortedSetOf<Int>()
                val formats = json.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val vcodec = f.optString("vcodec", "")
                        val h = f.optInt("height", 0)
                        if (h >= 144 && vcodec != "none") found.add(h)
                    }
                }
                val list = found.toList().sortedDescending()

                runOnUiThread {
                    videoUrl = finalUrl
                    heights = list
                    titleLabel.text = title
                    metaLabel.text = listOf(channel, fmtDuration(duration))
                        .filter { it.isNotEmpty() }.joinToString("   ").ifEmpty { " " }
                    refreshQuality()
                    setBusy(false)
                    setStatus("Vídeo encontrado. Escolha o formato e toque em Baixar.")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    setStatus(friendlyError(t.message), error = true)
                }
            }
        }
    }

    private fun onCancel() {
        cancelled = true
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Throwable) {
            // ignora: o processo pode já ter terminado
        }
    }

    private fun onDownload() {
        val url = videoUrl
        if (busy || !ready || url == null) return

        val audio = audioMode
        val height: Int? = if (!audio && heights.isNotEmpty()) {
            heights.getOrNull(qualitySpinner.selectedItemPosition)
        } else null

        cancelled = false
        progress.progress = 0
        setBusy(true)
        cancelBtn.visibility = View.VISIBLE
        setStatus("Iniciando download…")

        thread {
            val dir = File(filesDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-mtime")
                request.addOption("--windows-filenames")
                request.addOption("--concurrent-fragments", "4")
                request.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")

                if (audio) {
                    request.addOption("-f", "bestaudio/best")
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "192K")
                } else {
                    request.addOption("-f", "bv*+ba/b")
                    // Prefere a resolução escolhida e, entre as iguais, formatos compatíveis (MP4/H.264)
                    val res = if (height != null) "res:$height" else "res"
                    request.addOption("-S", "$res,vcodec:h264,ext:mp4:m4a")
                    request.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(request, processId) { percent, _, line ->
                    runOnUiThread {
                        val text = line ?: ""
                        when {
                            text.contains("[Merger]") -> setStatus("Juntando vídeo e áudio…")
                            text.contains("[ExtractAudio]") -> setStatus("Convertendo para MP3…")
                            percent >= 0f -> {
                                progress.progress = (percent * 10).toInt().coerceIn(0, 1000)
                                setStatus("Baixando… ${percent.toInt()}%")
                            }
                        }
                    }
                }

                runOnUiThread { setStatus("Salvando no celular…") }

                val result = dir.listFiles()
                    ?.filter {
                        it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                            !it.name.contains(".temp.")
                    }
                    ?.maxByOrNull { it.length() }
                    ?: throw RuntimeException("O arquivo baixado não foi encontrado.")

                saveToDownloads(result)

                runOnUiThread {
                    progress.progress = 1000
                    setStatus("Download concluído! Veja na pasta Downloads do celular.", ok = true)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progress.progress = 0
                    if (cancelled) setStatus("Download cancelado.")
                    else setStatus(friendlyError(t.message), error = true)
                }
            } finally {
                dir.deleteRecursively()
                runOnUiThread {
                    cancelBtn.visibility = View.GONE
                    setBusy(false)
                }
            }
        }
    }

    /** Copia o arquivo para a pasta pública Downloads/JorgeBaixaPraMim. */
    private fun saveToDownloads(file: File) {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/JorgeBaixaPraMim"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("Não foi possível criar o arquivo em Downloads.")

        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out, 1 shl 16) }
        } ?: throw RuntimeException("Não foi possível gravar o arquivo em Downloads.")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
