package com.radioterapia.ai.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Gravação de PDFs em **pen-drive conectado por cabo OTG**.
 *
 * Por que não "tablet como pen-drive": o Android abandonou o USB Mass Storage
 * no 4.4 e hoje só expõe MTP/PTP, que impressoras não leem. O caminho que
 * funciona é o inverso — o tablet como *host* USB, com o pen-drive plugado via
 * adaptador OTG. É isso que esta classe faz.
 *
 * Detecção em duas frentes, porque nenhuma sozinha é confiável:
 *  • [temDispositivoUsb] — o `UsbManager` enxerga o adaptador/dispositivo assim
 *    que ele é plugado, mesmo antes do sistema montar o sistema de arquivos.
 *  • [temVolumeMontado] — o `StorageManager` confirma que existe um volume
 *    REMOVÍVEL e MONTADO, que é o que de fato permite gravar.
 *
 * A escrita usa SAF (`DocumentFile`), mesmo mecanismo já usado no backup: é o
 * único caminho suportado em Android moderno para mídia removível, e o
 * `treeUri` é persistido para que a segunda impressão não peça a pasta de novo.
 */
class PenDriveHelper(private val context: Context) {

    /** Estado do pen-drive, para a interface saber o que mostrar. */
    enum class Estado {
        SEM_CABO,        // nada plugado
        MONTANDO,        // dispositivo visto, volume ainda não montado
        PRONTO,          // volume removível montado — dá para gravar
        PASTA_PENDENTE   // montado, mas o usuário ainda não escolheu a pasta
    }

    // ---------------- detecção ----------------

    /** true se há qualquer dispositivo USB plugado (adaptador OTG conta). */
    fun temDispositivoUsb(): Boolean = try {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.isNotEmpty()
    } catch (_: Exception) { false }

    /** Volumes removíveis e montados (o pen-drive de fato pronto para gravar). */
    fun volumesRemoviveis(): List<StorageVolume> = try {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.storageVolumes.filter {
            it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED
        }
    } catch (_: Exception) { emptyList() }

    fun temVolumeMontado(): Boolean = volumesRemoviveis().isNotEmpty()

    /** Estado consolidado para a interface. */
    fun estado(): Estado = when {
        temVolumeMontado() ->
            if (pastaSalva() != null) Estado.PRONTO else Estado.PASTA_PENDENTE
        temDispositivoUsb() -> Estado.MONTANDO
        else -> Estado.SEM_CABO
    }

    /**
     * Escuta plugar/desplugar e avisa a tela para atualizar. O sistema envia
     * os eventos de USB e também os de mídia montada — precisamos dos dois:
     * o primeiro é imediato, o segundo é quem confirma que dá para gravar.
     */
    fun registrarObservador(aoMudar: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { aoMudar() }
        }
        val filtroUsb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val filtroMidia = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filtroUsb, Context.RECEIVER_NOT_EXPORTED)
                context.registerReceiver(receiver, filtroMidia, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filtroUsb)
                context.registerReceiver(receiver, filtroMidia)
            }
        } catch (_: Exception) { }
        return receiver
    }

    fun desregistrar(receiver: BroadcastReceiver?) {
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) { }
    }

    // ---------------- pasta escolhida (SAF) ----------------

    private val prefs by lazy {
        context.getSharedPreferences("photoid_pendrive", Context.MODE_PRIVATE)
    }

    /** Uri da pasta do pen-drive escolhida antes, se ainda válida. */
    fun pastaSalva(): Uri? {
        val s = prefs.getString(KEY_TREE, null) ?: return null
        return try {
            val uri = Uri.parse(s)
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null && doc.exists() && doc.canWrite()) uri else null
        } catch (_: Exception) { null }
    }

    fun salvarPasta(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun esquecerPasta() { prefs.edit().remove(KEY_TREE).apply() }

    /**
     * Intent que abre o seletor JÁ no pen-drive quando o Android permite
     * (API 29+ expõe isso pelo próprio volume); nas versões antigas abre o
     * seletor genérico, e o usuário navega até o dispositivo.
     */
    fun intentEscolherPasta(): Intent {
        val vol = volumesRemoviveis().firstOrNull()
        if (Build.VERSION.SDK_INT >= 29 && vol != null) {
            try { return vol.createOpenDocumentTreeIntent() } catch (_: Exception) { }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    // ---------------- sessões de impressão ----------------

    /**
     * Organização em pastas dentro de [SUBPASTA]. O prefixo numérico existe por
     * um motivo prático: o navegador da impressora ordena alfabeticamente, e
     * assim a pasta do paciente atual aparece SEMPRE em primeiro, sem rolagem.
     *
     *   PHOTOID/
     *     1_PACIENTE_ATUAL/     ficha avulsa recém-impressa
     *     2_LOTE_INDIVIDUAIS/   um PDF por paciente do lote
     *     3_LOTE_AGRUPADO/      todos os pacientes num arquivo só
     *     9_ANTIGOS/            sessões anteriores
     */
    enum class Pasta(val dir: String) {
        PACIENTE_ATUAL("1_PACIENTE_ATUAL"),
        LOTE_INDIVIDUAIS("2_LOTE_INDIVIDUAIS"),
        LOTE_AGRUPADO("3_LOTE_AGRUPADO"),
        ANTIGOS("9_ANTIGOS")
    }

    /** Pastas da sessão corrente (tudo menos o arquivo histórico). */
    private val pastasDaSessao = listOf(
        Pasta.PACIENTE_ATUAL, Pasta.LOTE_INDIVIDUAIS, Pasta.LOTE_AGRUPADO)

    private fun raiz(): DocumentFile? {
        val uri = pastaSalva() ?: return null
        val base = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (!base.exists() || !base.canWrite()) return null
        return base.findFile(SUBPASTA)?.takeIf { it.isDirectory }
            ?: base.createDirectory(SUBPASTA)
    }

    private fun pastaDe(raiz: DocumentFile, p: Pasta, criar: Boolean = true): DocumentFile? =
        raiz.findFile(p.dir)?.takeIf { it.isDirectory }
            ?: if (criar) raiz.createDirectory(p.dir) else null

    /**
     * true se a sessão atual já tem algum PDF — é o que decide se vale perguntar
     * "adicionar à sessão atual ou abrir uma nova?". Com o pen-drive vazio a
     * pergunta seria ruído e a gravação segue direto.
     */
    fun temSessaoAtiva(): Boolean = try {
        val r = raiz()
        r != null && pastasDaSessao.any { p ->
            pastaDe(r, p, criar = false)?.listFiles()?.any {
                it.isFile && it.name?.endsWith(".pdf", true) == true
            } == true
        }
    } catch (_: Exception) { false }

    /** Quantos PDFs há na sessão atual (para mostrar na pergunta). */
    fun pdfsNaSessao(): Int {
        val r = raiz() ?: return 0
        return try {
            pastasDaSessao.sumOf { p ->
                pastaDe(r, p, criar = false)?.listFiles()
                    ?.count { it.isFile && it.name?.endsWith(".pdf", true) == true } ?: 0
            }
        } catch (_: Exception) { 0 }
    }

    /**
     * Abre uma sessão nova: move todo PDF das pastas correntes para ANTIGOS e
     * remove as pastas correntes. Assim a impressora encontra apenas o material
     * do momento, e nada do que já foi impresso se perde.
     */
    /**
     * Move para 9_ANTIGOS o que já existe NA PASTA que vai receber material, e
     * remove a pasta. É o que substitui a antiga pergunta "sessão atual ou
     * nova?": arquivar é automático e sempre seguro, porque nada é apagado —
     * cada arquivo leva carimbo próprio no histórico.
     */
    private fun arquivarPasta(raiz: DocumentFile, p: Pasta): Int {
        var movidos = 0
        try {
            val dir = pastaDe(raiz, p, criar = false) ?: return 0
            val antigos = pastaDe(raiz, Pasta.ANTIGOS) ?: return 0
            for (arq in dir.listFiles()) {
                if (!arq.isFile) continue
                val nome = arq.name ?: continue
                if (!nome.endsWith(".pdf", true)) { arq.delete(); continue }
                val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(java.util.Date())
                val origem = p.dir.substringAfter('_')
                val base = nome.removeSuffix(".pdf").removeSuffix(".PDF")
                if (copiarDocumento(arq, antigos, "${carimbo}_${origem}_$base.pdf")) movidos++
                arq.delete()
            }
            dir.delete()
        } catch (_: Exception) { }
        return movidos
    }

    fun iniciarNovaSessao(): Resultado {
        val r = raiz() ?: return Resultado(false, "Pen-drive indisponível.")
        val antigos = pastaDe(r, Pasta.ANTIGOS)
            ?: return Resultado(false, "Não foi possível criar a pasta de antigos.")
        return try {
        var movidos = 0
        for (p in pastasDaSessao) {
            val dir = pastaDe(r, p, criar = false) ?: continue
            for (arq in dir.listFiles()) {
                if (!arq.isFile) continue
                val nome = arq.name ?: continue
                if (!nome.endsWith(".pdf", true)) { arq.delete(); continue }
                // Homônimos e resimulações do mesmo paciente colidiriam no
                // histórico. Como esta pasta é só rotina de impressão (e não
                // base de dados), cada arquivo leva um carimbo próprio e NADA é
                // sobrescrito — o operador sempre encontra o que já imprimiu.
                val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(java.util.Date())
                val origem = p.dir.substringAfter('_')
                val base = nome.removeSuffix(".pdf").removeSuffix(".PDF")
                if (copiarDocumento(arq, antigos, "${carimbo}_${origem}_$base.pdf")) movidos++
                arq.delete()
            }
            dir.delete()
        }
        Resultado(true, "Sessão nova aberta ($movidos arquivo(s) arquivado(s)).")
        } catch (e: Exception) {
            Resultado(false, e.message ?: "Falha ao abrir nova sessão.")
        }
    }

    /** Copia um DocumentFile para outra pasta, com nome novo. */
    private fun copiarDocumento(origem: DocumentFile, destinoDir: DocumentFile,
                                nome: String): Boolean = try {
        destinoDir.findFile(nome)?.delete()
        val novo = destinoDir.createFile("application/pdf", nome)
        if (novo == null) false else {
            context.contentResolver.openInputStream(origem.uri).use { entrada ->
                context.contentResolver.openOutputStream(novo.uri).use { saida ->
                    if (entrada != null && saida != null) { entrada.copyTo(saida); true }
                    else false
                }
            } ?: false
        }
    } catch (_: Exception) { false }

    // ---------------- gravação ----------------

    data class Resultado(val sucesso: Boolean, val mensagem: String, val caminho: String = "")

    /** Grava um PDF numa pasta da sessão. */
    private fun gravarEm(pasta: Pasta, pdf: File, nomeArquivo: String? = null): Resultado {
        val r = raiz() ?: return Resultado(false, "Pasta do pen-drive não escolhida ou removida.")
        return try {
            val dir = pastaDe(r, pasta)
                ?: return Resultado(false, "Não foi possível criar ${pasta.dir}.")
            val nome = nomeArquivo ?: pdf.name
            dir.findFile(nome)?.delete()
            // MIME pela extensão. Era fixo em "application/pdf": com a exportação
            // da simulação em ZIP passando por aqui, o pen-drive receberia um
            // .zip declarado como PDF e o computador da clínica se recusaria a
            // abri-lo, ou abriria no leitor errado.
            val mime = when (nome.substringAfterLast('.', "").lowercase()) {
                "zip" -> "application/zip"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/pdf"
            }
            val destino = dir.createFile(mime, nome)
                ?: return Resultado(false, "Pen-drive não aceitou criar o arquivo.")
            context.contentResolver.openOutputStream(destino.uri).use { saida ->
                if (saida == null) return Resultado(false, "Pen-drive não aceitou a gravação.")
                pdf.inputStream().use { it.copyTo(saida) }
            }
            Resultado(true, "Gravado em ${pasta.dir}.", "$SUBPASTA/${pasta.dir}/$nome")
        } catch (e: Exception) {
            Resultado(false, e.message ?: "Falha ao gravar no pen-drive.")
        }
    }

    /** Ficha avulsa do paciente que está na sala. */
    fun gravarPacienteAtual(pdf: File): Resultado {
        val r = raiz() ?: return Resultado(false, "Pasta do pen-drive não escolhida ou removida.")
        arquivarPasta(r, Pasta.PACIENTE_ATUAL)   // ficha anterior vai para o histórico
        return gravarEm(Pasta.PACIENTE_ATUAL, pdf)
    }

    /**
     * LOTE — grava os DOIS formatos de uma vez, porque não custa nada e evita
     * uma decisão no tablet: quem opera a impressora escolhe lá se abre o
     * arquivo único ou vai paciente a paciente.
     *
     *  • 2_LOTE_INDIVIDUAIS: uma cópia por paciente, com nome legível.
     *  • 3_LOTE_AGRUPADO: um PDF nativo remontado pelo PdfBuilder.
     *
     * As duas pastas são arquivadas automaticamente antes de receber o novo
     * material.
     */
    fun gravarLote(
        pdfs: List<File>,
        nomes: List<String>,
        itens: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote>
    ): Resultado {
        if (pdfs.isEmpty() && itens.isEmpty())
            return Resultado(false, "Nenhum paciente selecionado.")
        val r = raiz() ?: return Resultado(false, "Pasta do pen-drive não escolhida ou removida.")
        arquivarPasta(r, Pasta.LOTE_INDIVIDUAIS)
        arquivarPasta(r, Pasta.LOTE_AGRUPADO)

        // ---- Individuais ----
        var ok = 0
        pdfs.forEachIndexed { i, f ->
            val nome = nomes.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { bruto ->
                val limpo = com.radioterapia.ai.util.StorageLocal
                    .removerAcentosMaiusculas(bruto).replace(" ", "_")
                val hora = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US)
                    .format(java.util.Date(System.currentTimeMillis() + i * 1000L))
                "${limpo}_$hora.pdf"
            }
            if (gravarEm(Pasta.LOTE_INDIVIDUAIS, f, nome).sucesso) ok++
        }

        // ---- Agrupado (remontado, PDF nativo) ----
        var nomeAgrupado: String? = null
        if (itens.isNotEmpty()) {
            var temporario: File? = null
            try {
                val dir = pastaDe(r, Pasta.LOTE_AGRUPADO)
                if (dir != null) {
                    val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                        java.util.Locale.US).format(java.util.Date())
                    val nome = "LOTE_$carimbo.pdf"
                    temporario = File(context.cacheDir, nome)
                    val gerado = com.radioterapia.ai.pdf.PdfBuilder.gerarLoteAgrupado(
                        context, itens, temporario)
                    if (gerado != null) {
                        dir.findFile(nome)?.delete()
                        val destino = dir.createFile("application/pdf", nome)
                        if (destino != null) {
                            context.contentResolver.openOutputStream(destino.uri).use { saida ->
                                if (saida != null) {
                                    gerado.inputStream().use { it.copyTo(saida) }
                                    nomeAgrupado = nome
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally { try { temporario?.delete() } catch (_: Exception) { } }
        }

        if (ok == 0 && nomeAgrupado == null)
            return Resultado(false, "Nenhum arquivo pôde ser gravado no pen-drive.")
        val partes = mutableListOf<String>()
        if (ok > 0) partes.add("$ok individual(is)")
        nomeAgrupado?.let { partes.add("1 agrupado ($it)") }
        return Resultado(true, "Gravado no pen-drive: " + partes.joinToString(" + ") + ".")
    }

    companion object {
        private const val KEY_TREE = "pendrive_tree_uri"
        /** Subpasta criada no pen-drive — nome curto para o teclado da impressora. */
        const val SUBPASTA = "PHOTOID"
    }
}
