package com.radioterapia.ai.treatment

import android.content.Context
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.security.CredentialStore
import java.io.File
import java.text.Normalizer
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Localiza e baixa fotos de simulações de um paciente para o módulo Tratamento.
 *
 * Estratégia:
 *  1. Tenta listar do servidor SMB (todos os destinos ativos, primário primeiro)
 *  2. Se servidor falhar, lê do tablet local (se a simulação foi feita aqui)
 *  3. Cache temporário em filesDir/treatment_cache/<prontuario>/
 *
 * Identifica simulações pelo nome da pasta. Cada paciente pode ter:
 *   - "[NOME] POSICIONAMENTO/" (1ª simulação)
 *   - "[NOME] POSICIONAMENTO NOVA SIMULACAO 1/" (2ª simulação)
 *   - "[NOME] POSICIONAMENTO NOVA SIMULACAO 2/" (3ª simulação)
 */
class TreatmentPhotoFetcher(private val context: Context) {

    private val config = AppConfig(context)
    private val credentials = CredentialStore(context)
    private val cacheDir = File(context.filesDir, "treatment_cache").apply { mkdirs() }

    data class FotoInfo(
        val arquivoLocal: File,
        val nomeOriginal: String,
        val tipo: TipoFoto,
        val timestamp: Long
    )

    enum class TipoFoto { ROSTO, ETIQUETA, POSICIONAMENTO, ACESSORIOS, DOCUMENTO, DESCONHECIDO }

    data class Simulacao(
        val nomePaciente: String,
        val nomePastaCompleto: String,
        val numeroSimulacao: Int,            // 1 = primeira; 2+ = nova simulação N-1
        val timestampPrincipal: Long,        // timestamp da foto mais recente
        val fotos: List<FotoInfo>,
        val origem: Origem,
        val arquivoPdfLocal: File? = null    // PDF da folha (local), se disponível
    ) {
        val rosto: FotoInfo? get() = fotos.firstOrNull { it.tipo == TipoFoto.ROSTO }
        val etiqueta: FotoInfo? get() = fotos.firstOrNull { it.tipo == TipoFoto.ETIQUETA }
        val acessorios: FotoInfo? get() = fotos.firstOrNull { it.tipo == TipoFoto.ACESSORIOS }
        val acessoriosLista: List<FotoInfo> get() = fotos.filter { it.tipo == TipoFoto.ACESSORIOS }.sortedBy { it.timestamp }
        val posicionamentos: List<FotoInfo> get() = fotos.filter { it.tipo == TipoFoto.POSICIONAMENTO }.sortedBy { it.timestamp }
        /** Impressos escaneados (laudos/anotações). Ficam FORA do PDF. */
        val documentos: List<FotoInfo> get() = fotos.filter { it.tipo == TipoFoto.DOCUMENTO }.sortedBy { it.timestamp }
    }

    enum class Origem { SERVIDOR, LOCAL_TABLET }

    /**
     * Lista todas as simulações encontradas para um paciente, ordenadas da mais recente para a mais antiga.
     * @param nomePaciente nome como será procurado nas pastas (será normalizado)
     */
    suspend fun buscarSimulacoes(nomePaciente: String): List<Simulacao> {
        val nomeNorm = normalizarNome(nomePaciente)

        // Tenta servidor
        val resultadosServidor = try { buscarNoServidor(nomeNorm) }
                                  catch (_: Exception) { emptyList() }
        if (resultadosServidor.isNotEmpty()) return resultadosServidor

        // Fallback local
        return buscarLocal(nomeNorm)
    }

    private fun buscarNoServidor(nomeNorm: String): List<Simulacao> {
        val destinos = config.obterDestinosAtivos()
        if (destinos.isEmpty()) return emptyList()
        if (config.smbUsuario.isBlank() || credentials.obterSenha().isBlank()) return emptyList()

        // Vamos no destino primário (primeiro). Se falhar, tenta backups.
        for (destino in destinos) {
            try {
                val (host, share, subpastaBase) = decomporUnc(destino.caminhoUNC)
                    ?: continue
                val cfg = SmbConfig.builder().withTimeout(15_000, TimeUnit.MILLISECONDS).build()
                val smbClient = SMBClient(cfg)

                smbClient.connect(host).use { conn ->
                    val auth = AuthenticationContext(
                        config.smbUsuario,
                        credentials.obterSenha().toCharArray(),
                        config.smbDominio
                    )
                    val session = conn.authenticate(auth)
                    val diskShare = session.connectShare(share) as DiskShare

                    val pastaBase = subpastaBase
                    val todasPastas = try {
                        diskShare.list(pastaBase).filter {
                            it.fileAttributes.toLong() and 0x10L != 0L && // directory
                            !it.fileName.startsWith(".")
                        }
                    } catch (_: Exception) { return@use emptyList<Simulacao>() }

                    val pastasPaciente = todasPastas.filter { fi ->
                        val n = normalizarNome(fi.fileName)
                        n.startsWith("$nomeNorm POSICIONAMENTO")
                    }

                    val resultado = mutableListOf<Simulacao>()
                    for (pasta in pastasPaciente) {
                        val numSim = extrairNumeroSimulacao(pasta.fileName)
                        val caminhoFotos = if (pastaBase.isEmpty()) pasta.fileName
                                            else "$pastaBase\\${pasta.fileName}"

                        val arquivos = try {
                            diskShare.list(caminhoFotos).filter {
                                !it.fileName.startsWith(".") &&
                                !it.fileName.endsWith(".pdf", ignoreCase = true) &&
                                (it.fileName.lowercase().endsWith(".jpg") ||
                                 it.fileName.lowercase().endsWith(".jpeg")) &&
                                !ehOriginal(it.fileName)
                            }
                        } catch (_: Exception) { continue }

                        val fotos = mutableListOf<FotoInfo>()
                        val cachePasta = File(cacheDir, "${nomeNorm.replace(' ', '_')}_$numSim").apply { mkdirs() }

                        for (arq in arquivos) {
                            val tipo = identificarTipo(arq.fileName)
                            val cachedFile = File(cachePasta, arq.fileName)
                            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                                // Baixa
                                val caminhoArq = "$caminhoFotos\\${arq.fileName}"
                                try {
                                    diskShare.openFile(
                                        caminhoArq,
                                        EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                                        null,
                                        SMB2ShareAccess.ALL,
                                        SMB2CreateDisposition.FILE_OPEN,
                                        null
                                    ).use { remote ->
                                        cachedFile.outputStream().use { saida ->
                                            val buffer = ByteArray(64 * 1024)
                                            var offset = 0L
                                            while (true) {
                                                val lidos = remote.read(buffer, offset)
                                                if (lidos <= 0) break
                                                saida.write(buffer, 0, lidos)
                                                offset += lidos
                                            }
                                        }
                                    }
                                } catch (_: Exception) { continue }
                            }
                            fotos.add(FotoInfo(
                                arquivoLocal = cachedFile,
                                nomeOriginal = arq.fileName,
                                tipo = tipo,
                                timestamp = arq.lastWriteTime.toDate().time
                            ))
                        }

                        if (fotos.isNotEmpty()) {
                            // Tenta localizar/baixar o PDF da folha desta simulação
                            var pdfLocal: File? = null
                            try {
                                val pdfRemoto = diskShare.list(caminhoFotos).firstOrNull {
                                    it.fileName.endsWith(".pdf", ignoreCase = true) &&
                                    !it.fileName.startsWith(".")
                                }
                                if (pdfRemoto != null) {
                                    val cachedPdf = File(cachePasta, pdfRemoto.fileName)
                                    if (!cachedPdf.exists() || cachedPdf.length() == 0L) {
                                        diskShare.openFile(
                                            "$caminhoFotos\\${pdfRemoto.fileName}",
                                            EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                                            null, SMB2ShareAccess.ALL,
                                            SMB2CreateDisposition.FILE_OPEN, null
                                        ).use { remote ->
                                            cachedPdf.outputStream().use { saida ->
                                                val buffer = ByteArray(64 * 1024)
                                                var offset = 0L
                                                while (true) {
                                                    val lidos = remote.read(buffer, offset)
                                                    if (lidos <= 0) break
                                                    saida.write(buffer, 0, lidos)
                                                    offset += lidos
                                                }
                                            }
                                        }
                                    }
                                    if (cachedPdf.exists() && cachedPdf.length() > 0L) pdfLocal = cachedPdf
                                }
                            } catch (_: Exception) { /* PDF é opcional */ }

                            resultado.add(Simulacao(
                                nomePaciente = nomeNorm,
                                nomePastaCompleto = pasta.fileName,
                                numeroSimulacao = numSim,
                                timestampPrincipal = fotos.maxOf { it.timestamp },
                                fotos = fotos,
                                origem = Origem.SERVIDOR,
                                arquivoPdfLocal = pdfLocal
                            ))
                        }
                    }
                    return resultado.sortedByDescending { it.timestampPrincipal }
                }
            } catch (_: Exception) { continue }
        }
        return emptyList()
    }

    /** Fallback: lê fotos da pasta Pictures local (se a simulação foi feita neste tablet). */
    private fun buscarLocal(nomeNorm: String): List<Simulacao> {
        // Se há pasta SAF configurada (fluxo novo, item local), varre por lá.
        if (config.pastaFotosUri.isNotBlank()) {
            val saf = buscarLocalSaf(nomeNorm)
            if (saf.isNotEmpty()) return saf
            // se nada na SAF, ainda tenta os caminhos abaixo
        }

        // Pasta padrão PhotoID_RT/PHOTOS/<PACIENTE> (raiz do armazenamento ou pasta do app)
        val appPhotos = com.radioterapia.ai.util.StorageLocal.photos(context)
        if (appPhotos.exists() && appPhotos.isDirectory) {
            val pastaPac = appPhotos.listFiles { f ->
                f.isDirectory && com.radioterapia.ai.util.StorageLocal.pastaCasaPaciente(f.name, nomeNorm)
            }?.firstOrNull()
            if (pastaPac != null) {
                val arquivos = pastaPac.listFiles { _, name ->
                    (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) &&
                        !ehOriginal(name)
                }?.toList() ?: emptyList()
                if (arquivos.isNotEmpty()) {
                    // SEPARA POR SIMULACAO. Antes tudo voltava como UMA simulacao
                    // de numero 1, e o efeito nao era so o seletor da tela do
                    // paciente nunca aparecer: o carrossel misturava as fotos da
                    // simulacao original com as da reirradiacao, e o Time-Out
                    // lido era sempre o da primeira. As fotos SEMPRE foram
                    // distinguiveis — a reirradiacao leva "_NOVASIMn" no nome —,
                    // so ninguem estava olhando.
                    val pdfs = pastaPac.listFiles { _, name -> name.endsWith(".pdf", true) }
                        ?.toList().orEmpty()
                    return arquivos
                        .groupBy { numeroSimDoArquivo(it.name) }
                        .map { (numSim, doGrupo) ->
                            val fotos = doGrupo.map { arq ->
                                FotoInfo(arquivoLocal = arq, nomeOriginal = arq.name,
                                    tipo = identificarTipo(arq.name),
                                    timestamp = arq.lastModified())
                            }
                            // O PDF tem a MESMA marca no nome. Pegar o mais
                            // recente da pasta entregaria a ficha da reirradiacao
                            // a quem abriu a simulacao original.
                            val pdfLocal = pdfs
                                .filter { numeroSimDoArquivo(it.name) == numSim }
                                .maxByOrNull { it.lastModified() }
                            Simulacao(
                                nomePaciente = nomeNorm,
                                nomePastaCompleto = pastaPac.name,
                                numeroSimulacao = numSim,
                                timestampPrincipal = fotos.maxOfOrNull { it.timestamp } ?: 0L,
                                fotos = fotos, origem = Origem.LOCAL_TABLET,
                                arquivoPdfLocal = pdfLocal)
                        }
                        .sortedByDescending { it.timestampPrincipal }
                }
            }
        }

        val pastaLocal = config.pastaBaseLocal.trimEnd('/')
        val baseDir = try {
            @Suppress("DEPRECATION")
            val raiz = android.os.Environment.getExternalStorageDirectory()
            File(raiz, pastaLocal)
        } catch (_: Exception) { return emptyList() }

        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()

        val pastas = baseDir.listFiles { f -> f.isDirectory && normalizarNome(f.name).startsWith("$nomeNorm POSICIONAMENTO") }
            ?: return emptyList()

        return pastas.map { pasta ->
            val numSim = extrairNumeroSimulacao(pasta.name)
            val arquivos = pasta.listFiles { _, name ->
                (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) &&
                    !ehOriginal(name)
            }?.toList() ?: emptyList()

            val fotos = arquivos.map { arq ->
                FotoInfo(
                    arquivoLocal = arq,
                    nomeOriginal = arq.name,
                    tipo = identificarTipo(arq.name),
                    timestamp = arq.lastModified()
                )
            }

            val pdfLocal = pasta.listFiles { _, name -> name.endsWith(".pdf", true) }
                ?.maxByOrNull { it.lastModified() }

            Simulacao(
                nomePaciente = nomeNorm,
                nomePastaCompleto = pasta.name,
                numeroSimulacao = numSim,
                timestampPrincipal = fotos.maxOfOrNull { it.timestamp } ?: 0L,
                fotos = fotos,
                origem = Origem.LOCAL_TABLET,
                arquivoPdfLocal = pdfLocal
            )
        }.filter { it.fotos.isNotEmpty() }
         .sortedByDescending { it.timestampPrincipal }
    }

    /**
     * Varre a pasta SAF (DocumentFile) escolhida pelo usuário. As subpastas dos
     * pacientes contêm as fotos (.jpg) e o PDF. Como o restante do app usa File,
     * copia os arquivos da simulação para o cache e monta as Simulacoes.
     */
    private fun buscarLocalSaf(nomeNorm: String): List<Simulacao> {
        return try {
            val base = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                context, android.net.Uri.parse(config.pastaFotosUri)) ?: return emptyList()
            // Navega <base>/PhotoID_RT/PHOTOS
            val photos = base.findFile("PhotoID_RT")?.findFile("PHOTOS") ?: return emptyList()
            // Pasta do paciente: casa por NOME (+ prontuário no fim)
            val pastas = photos.listFiles().filter {
                it.isDirectory && com.radioterapia.ai.util.StorageLocal.pastaCasaPaciente(it.name, nomeNorm)
            }
            pastas.mapNotNull { pasta ->
                val cachePasta = File(cacheDir, "saf_${(pasta.name ?: "x").replace(' ', '_')}").apply { mkdirs() }
                val fotos = mutableListOf<FotoInfo>()
                var pdfLocal: File? = null
                var pdfMaisNovoTs = -1L
                pasta.listFiles().forEach { doc ->
                    val nome = doc.name ?: return@forEach
                    val low = nome.lowercase()
                    when {
                        low.endsWith(".jpg") || low.endsWith(".jpeg") -> {
                            if (ehOriginal(low)) return@forEach
                            val destino = File(cachePasta, nome)
                            if (!destino.exists() || destino.length() == 0L) copiarDoc(doc, destino)
                            if (destino.exists()) fotos.add(FotoInfo(
                                arquivoLocal = destino, nomeOriginal = nome,
                                tipo = identificarTipo(nome), timestamp = doc.lastModified()))
                        }
                        low.endsWith(".pdf") -> {
                            // Mantém apenas o PDF MAIS RECENTE (a folha é cumulativa).
                            if (doc.lastModified() >= pdfMaisNovoTs) {
                                val destino = File(cachePasta, nome)
                                if (!destino.exists() || destino.length() == 0L) copiarDoc(doc, destino)
                                if (destino.exists()) { pdfLocal = destino; pdfMaisNovoTs = doc.lastModified() }
                            }
                        }
                    }
                }
                if (fotos.isEmpty()) null else Simulacao(
                    nomePaciente = nomeNorm,
                    nomePastaCompleto = pasta.name ?: "",
                    numeroSimulacao = 1,
                    timestampPrincipal = fotos.maxOfOrNull { it.timestamp } ?: 0L,
                    fotos = fotos,
                    origem = Origem.LOCAL_TABLET,
                    arquivoPdfLocal = pdfLocal
                )
            }.sortedByDescending { it.timestampPrincipal }
        } catch (_: Exception) { emptyList() }
    }

    private fun copiarDoc(doc: androidx.documentfile.provider.DocumentFile, destino: File) {
        try {
            context.contentResolver.openInputStream(doc.uri)?.use { inp ->
                destino.outputStream().use { inp.copyTo(it) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Arquivo é a cópia ORIGINAL (quadro cheio, sem recorte)?
     *
     * Os originais ficam na MESMA pasta do paciente, para o FileSync levar os
     * dois. Só que toda varredura de fotos daqui alimenta o carrossel do
     * Tratamento e a regeneração do PDF — sem este filtro, cada foto apareceria
     * DUAS vezes no carrossel e entraria duplicada na folha impressa.
     */
    /**
     * Numero da simulacao a partir do NOME DO ARQUIVO.
     *
     * A convencao de gravacao poe "_NOVASIMn" no nome dos arquivos da
     * reirradiacao e nada nos da primeira simulacao — ver
     * FinalizarActivity.salvarTodasLocalmente. Entao "_NOVASIM1" e a
     * simulacao 2, e ausencia da marca e a simulacao 1.
     */
    private fun numeroSimDoArquivo(nomeArquivo: String): Int {
        val m = Regex("(?i)_NOVASIM(\\d+)").find(nomeArquivo) ?: return 1
        return (m.groupValues[1].toIntOrNull() ?: 0) + 1
    }

    private fun ehOriginal(nomeArquivo: String): Boolean =
        nomeArquivo.lowercase().endsWith("_original.jpg")

    private fun identificarTipo(nomeArquivo: String): TipoFoto {
        val n = nomeArquivo.lowercase()
        return when {
            n.contains("rosto") || n.contains("face") -> TipoFoto.ROSTO
            n.contains("etiqueta") || n.contains("label") -> TipoFoto.ETIQUETA
            n.contains("acessorios") || n.contains("accessories") -> TipoFoto.ACESSORIOS
            n.contains("posicionamento") || n.contains("positioning") || n.contains("_pos_") -> TipoFoto.POSICIONAMENTO
            n.contains("_doc") || n.contains("documento") || n.contains("impresso") -> TipoFoto.DOCUMENTO
            else -> TipoFoto.DESCONHECIDO
        }
    }

    /** Extrai número da simulação do nome da pasta. */
    private fun extrairNumeroSimulacao(nomePasta: String): Int {
        val m = Regex("NOVA\\s+SIMULACAO\\s+(\\d+)", RegexOption.IGNORE_CASE).find(nomePasta)
        val novaSim = m?.groupValues?.get(1)?.toIntOrNull()
        return if (novaSim != null) novaSim + 1 else 1
    }

    private fun decomporUnc(unc: String): Triple<String, String, String>? {
        val limpo = unc.removePrefix("\\\\").removePrefix("//")
        val partes = limpo.split(Regex("[\\\\/]")).filter { it.isNotEmpty() }
        if (partes.size < 2) return null
        val host = partes[0]
        val share = partes[1]
        val subpasta = partes.drop(2).joinToString("\\")
        return Triple(host, share, subpasta)
    }

    private fun normalizarNome(nome: String): String {
        val s = Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return s.replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ").trim().uppercase(Locale.getDefault())
    }
}
