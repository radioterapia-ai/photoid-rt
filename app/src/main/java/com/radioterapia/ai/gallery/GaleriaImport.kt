package com.radioterapia.ai.gallery

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Importação de fotos que já estão na galeria do tablet.
 *
 * POR QUE EXISTE: o técnico fotografou com o próprio aparelho enquanto o
 * paciente ainda estava na sala e só depois abriu o app, ou o paciente já foi
 * embora e falta uma foto que alguém tinha. Sem isto, a única saída era
 * refazer a foto — com o paciente ausente, não há como.
 *
 * A cópia é BRUTA, sem recorte e sem reescrever EXIF: o que veio da galeria
 * entra como está. Foto importada não passou pelo enquadramento do app, então
 * fingir que passou seria pior que aceitar que ela é diferente.
 *
 * Usada pelas DUAS telas de câmera — a da simulação e a de fotos adicionais no
 * tratamento. Ficou aqui, e não dentro de uma delas, porque copiar arquivo de
 * `content://` tem detalhe suficiente (stream que pode vir nulo, permissão que
 * expira, arquivo de zero byte) para não valer duplicar.
 */
object GaleriaImport {

    /** MIME aceito no seletor. Só imagem: PDF e vídeo não entram na ficha. */
    const val MIME = "image/*"

    class Resultado(val arquivos: List<File>, val falhas: Int)

    /**
     * Copia cada URI escolhida para um arquivo temporário em `destinoDir`.
     *
     * Falha de uma foto NÃO derruba as outras: quem escolheu oito e teve uma
     * ilegível prefere sete importadas e um aviso a nenhuma e um erro.
     */
    fun copiarParaTemp(context: Context, uris: List<Uri>, destinoDir: File,
                       prefixo: String): Resultado {
        val ok = mutableListOf<File>()
        var falhas = 0
        uris.forEachIndexed { i, uri ->
            val destino = File(destinoDir,
                "${prefixo}_${System.currentTimeMillis()}_$i.jpg")
            try {
                context.contentResolver.openInputStream(uri).use { entrada ->
                    if (entrada == null) { falhas++; return@forEachIndexed }
                    destino.outputStream().use { entrada.copyTo(it) }
                }
                // Arquivo de zero byte acontece quando o provedor da galeria
                // devolve stream vazio para uma foto ainda sincronizando da
                // nuvem. Entra como falha, senão vira miniatura preta na ficha.
                if (destino.length() <= 0L) {
                    destino.delete(); falhas++
                } else {
                    ok.add(destino)
                }
            } catch (_: Exception) {
                try { destino.delete() } catch (_: Exception) {}
                falhas++
            }
        }
        return Resultado(ok, falhas)
    }
}
