package com.radioterapia.ai.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.util.StorageLocal
import java.io.File

/**
 * Backup COMPLETO (fotos + PDFs + CSV) entre a pasta local PhotoID_RT/PHOTOS e
 * uma pasta de destino/origem escolhida pelo usuário (Cenário A: pasta, não SMB).
 *
 * Estrutura do backup na pasta escolhida:
 *   <pasta>/PhotoID_RT_Backup/
 *       base_pacientes.csv          (base local, formato fixo)
 *       PHOTOS/<PACIENTE - PRONTUARIO>/...  (fotos e PDFs)
 *
 * Filtro por "últimos N meses": só processa pacientes cuja data mais recente
 * (ultima_simulacao do cadastro OU lastModified mais novo dos arquivos) caiba
 * na janela. Isso evita sobrecarregar a memória do tablet ao importar anos de casos.
 *
 * Tudo é cancelável e reporta progresso (callback onProgresso).
 */
class BackupManager(private val context: Context) {

    @Volatile var cancelado = false

    data class Progresso(val atual: Int, val total: Int, val rotulo: String)
    data class Resultado(val sucesso: Boolean, val pacientes: Int, val arquivos: Int, val mensagem: String)

    private val NOME_BACKUP = "PhotoID_RT_Backup"
    private val NOME_CSV = "base_pacientes.csv"

    // ---------- util de data ----------

    /** Limite (epoch ms) de N meses atrás. meses<=0 => sem limite (tudo). */
    private fun limiteMs(meses: Int): Long {
        if (meses <= 0) return 0L
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, -meses)
        return cal.timeInMillis
    }

    /** Data mais recente de uma pasta de paciente (File): maior lastModified. */
    private fun dataRecentePasta(pasta: File): Long {
        var maior = pasta.lastModified()
        pasta.listFiles()?.forEach { if (it.lastModified() > maior) maior = it.lastModified() }
        return maior
    }

    private fun dataRecentePastaDoc(pasta: DocumentFile): Long {
        var maior = pasta.lastModified()
        pasta.listFiles().forEach { if (it.lastModified() > maior) maior = it.lastModified() }
        return maior
    }

    // ---------- EXPORTAR ----------

    fun exportar(destinoTree: Uri, meses: Int, onProgresso: (Progresso) -> Unit): Resultado {
        cancelado = false
        try {
            val raiz = DocumentFile.fromTreeUri(context, destinoTree)
                ?: return Resultado(false, 0, 0, "Pasta de destino inválida")
            val pastaBackup = obterOuCriarDir(raiz, NOME_BACKUP)
                ?: return Resultado(false, 0, 0, "Não foi possível criar a pasta de backup")
            val photosDestino = obterOuCriarDir(pastaBackup, "PHOTOS")
                ?: return Resultado(false, 0, 0, "Não foi possível criar PHOTOS no destino")

            val cache = PatientCache(context)
            val photosLocal = StorageLocal.photos(context)
            val limite = limiteMs(meses)

            // Seleciona pastas de pacientes locais dentro da janela de meses
            val pastas = (photosLocal.listFiles()?.filter { it.isDirectory } ?: emptyList())
                .filter { meses <= 0 || dataRecentePasta(it) >= limite }

            if (pastas.isEmpty())
                return Resultado(false, 0, 0, "Nenhum paciente no período selecionado")

            // CSV (somente dos pacientes filtrados)
            val nomesFiltrados = pastas.map { StorageLocal.chaveNome(it.name) }.toSet()
            val csv = cache.exportarCsvFiltrado(nomesFiltrados)
            gravarTexto(pastaBackup, NOME_CSV, csv)

            var arquivos = 0
            val total = pastas.size
            pastas.forEachIndexed { idx, pastaPac ->
                if (cancelado) return Resultado(false, idx, arquivos, "Cancelado pelo usuário")
                onProgresso(Progresso(idx + 1, total, pastaPac.name ?: ""))
                val destinoPac = obterOuCriarDir(photosDestino, pastaPac.name ?: "SEM_NOME") ?: return@forEachIndexed
                pastaPac.listFiles()?.forEach { arq ->
                    if (cancelado) return Resultado(false, idx, arquivos, "Cancelado pelo usuário")
                    if (arq.isFile) {
                        if (copiarFileParaDoc(arq, destinoPac, arq.name)) arquivos++
                    }
                }
            }
            return Resultado(true, total, arquivos, "Backup concluído")
        } catch (e: Exception) {
            return Resultado(false, 0, 0, e.message ?: "Erro no backup")
        }
    }

    // ---------- IMPORTAR ----------

    fun importar(origemTree: Uri, meses: Int, onProgresso: (Progresso) -> Unit): Resultado {
        cancelado = false
        try {
            val raiz = DocumentFile.fromTreeUri(context, origemTree)
                ?: return Resultado(false, 0, 0, "Pasta de origem inválida")
            // Aceita tanto a pasta de backup quanto uma pasta que CONTENHA o backup
            val pastaBackup = if (raiz.findFile("PHOTOS") != null) raiz
                else raiz.findFile(NOME_BACKUP) ?: raiz
            val photosOrigem = pastaBackup.findFile("PHOTOS")?.takeIf { it.isDirectory }
                ?: return Resultado(false, 0, 0, "Pasta PHOTOS não encontrada na origem")

            val limite = limiteMs(meses)
            val pastas = photosOrigem.listFiles()
                .filter { it.isDirectory }
                .filter { meses <= 0 || dataRecentePastaDoc(it) >= limite }

            if (pastas.isEmpty())
                return Resultado(false, 0, 0, "Nenhum paciente no período selecionado")

            val photosLocal = StorageLocal.photos(context)
            var arquivos = 0
            val total = pastas.size
            pastas.forEachIndexed { idx, pastaPacDoc ->
                if (cancelado) return Resultado(false, idx, arquivos, "Cancelado pelo usuário")
                onProgresso(Progresso(idx + 1, total, pastaPacDoc.name ?: ""))
                val destinoPac = File(photosLocal, pastaPacDoc.name ?: "SEM_NOME").apply { mkdirs() }
                pastaPacDoc.listFiles().forEach { docArq ->
                    if (cancelado) return Resultado(false, idx, arquivos, "Cancelado pelo usuário")
                    val nome = docArq.name ?: return@forEach
                    if (!docArq.isDirectory) {
                        val destino = File(destinoPac, nome)
                        if (!destino.exists() || destino.length() == 0L) {
                            if (copiarDocParaFile(docArq, destino)) arquivos++
                        } else arquivos++
                    }
                }
            }

            // Importa/mescla o CSV (se existir)
            val csvDoc = pastaBackup.findFile(NOME_CSV)
            var pacientesCsv = 0
            if (csvDoc != null && csvDoc.isFile) {
                val texto = context.contentResolver.openInputStream(csvDoc.uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (texto.isNotBlank()) {
                    // Integridade ANTES de mesclar: um CSV com separador errado ou
                    // colunas trocadas corrompia o cadastro silenciosamente.
                    val cache = PatientCache(context)
                    val previa = cache.analisarCsv(texto)
                    if (!previa.valido) {
                        return Resultado(false, 0, arquivos,
                            "Fotos e PDFs importados, mas o cadastro NÃO foi mesclado: " +
                            previa.problema + ". Os arquivos estão a salvo.")
                    }
                    pacientesCsv = cache.importarCsv(texto)
                }
            }
            val pacientes = maxOf(total, pacientesCsv)
            return Resultado(true, pacientes, arquivos, "Importação concluída")
        } catch (e: Exception) {
            return Resultado(false, 0, 0, e.message ?: "Erro na importação")
        }
    }

    // ---------- LIMPAR CACHE (manter só recentes) ----------

    data class PreviaLimpeza(val pacientes: Int, val arquivos: Int, val bytes: Long)

    /** Calcula o que seria removido: pacientes MAIS ANTIGOS que N meses. */
    fun previaLimpeza(meses: Int): PreviaLimpeza {
        // meses <= 0 significa TODOS os casos (limite no futuro engloba tudo).
        val limite = if (meses <= 0) Long.MAX_VALUE else limiteMs(meses)
        val photosLocal = StorageLocal.photos(context)
        var pac = 0; var arq = 0; var bytes = 0L
        photosLocal.listFiles()?.filter { it.isDirectory }?.forEach { pasta ->
            if (dataRecentePasta(pasta) < limite) {
                pac++
                pasta.listFiles()?.forEach { if (it.isFile) { arq++; bytes += it.length() } }
            }
        }
        return PreviaLimpeza(pac, arq, bytes)
    }

    /** Remove pacientes mais antigos que N meses (pastas locais + registro no cache). */
    fun limparAntigos(meses: Int): Resultado {
        // meses <= 0 significa TODOS os casos.
        val limite = if (meses <= 0) Long.MAX_VALUE else limiteMs(meses)
        val photosLocal = StorageLocal.photos(context)
        val cache = PatientCache(context)
        var pac = 0; var arq = 0
        photosLocal.listFiles()?.filter { it.isDirectory }?.forEach { pasta ->
            if (dataRecentePasta(pasta) < limite) {
                pasta.listFiles()?.forEach { if (it.isFile) arq++ }
                val nome = pasta.name ?: ""
                pasta.deleteRecursively()
                cache.removerPorNomePasta(nome)
                pac++
            }
        }
        // "Todos": zera também registros órfãos (sem pasta) — evita contagem fantasma
        if (meses <= 0) cache.removerTodosRegistros()
        return Resultado(true, pac, arq, "Limpeza concluída")
    }

    // ---------- helpers SAF ----------

    private fun obterOuCriarDir(pai: DocumentFile, nome: String): DocumentFile? =
        pai.findFile(nome)?.takeIf { it.isDirectory } ?: pai.createDirectory(nome)

    private fun gravarTexto(pasta: DocumentFile, nome: String, conteudo: String) {
        pasta.findFile(nome)?.delete()
        val doc = pasta.createFile("text/csv", nome) ?: return
        context.contentResolver.openOutputStream(doc.uri)?.use { it.write(conteudo.toByteArray(Charsets.UTF_8)) }
    }

    private fun copiarFileParaDoc(origem: File, pastaDestino: DocumentFile, nome: String?): Boolean {
        val nomeArq = nome ?: origem.name
        return try {
            pastaDestino.findFile(nomeArq)?.delete()
            val mime = if (nomeArq.endsWith(".pdf", true)) "application/pdf" else "image/jpeg"
            val doc = pastaDestino.createFile(mime, nomeArq) ?: return false
            context.contentResolver.openOutputStream(doc.uri)?.use { saida ->
                origem.inputStream().use { it.copyTo(saida) }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun copiarDocParaFile(origem: DocumentFile, destino: File): Boolean {
        return try {
            context.contentResolver.openInputStream(origem.uri)?.use { entrada ->
                destino.outputStream().use { entrada.copyTo(it) }
            }
            true
        } catch (_: Exception) { false }
    }
}
