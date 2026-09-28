package com.radioterapia.ai.update

import android.content.Context
import com.radioterapia.ai.util.StorageLocal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A cópia de segurança que roda ANTES de instalar versão nova.
 *
 * O QUE ELA PROTEGE, e o que não precisa de proteção. Atualizar um APK por cima
 * de outro com a MESMA assinatura não apaga dado do app: `filesDir` e as
 * preferências sobrevivem, e as fotos nem estão lá — moram no armazenamento
 * externo, em PhotoID_RT, onde uma instalação não alcança. O Time-Out e as
 * observações também: são arquivos ocultos DENTRO da pasta de cada paciente, ao
 * lado das fotos.
 *
 * Então isto não é backup de foto, e prometer que fosse seria mentira de
 * tamanho — são gigabytes que nenhuma atualização ameaça. O que esta cópia
 * cobre é o que uma instalação interrompida, ou uma desinstalação feita para
 * «resolver», levaria embora: o CADASTRO dos pacientes e a CONFIGURAÇÃO do
 * serviço. São kilobytes, e por isso a cópia não custa clique nem espera.
 *
 * SEM SENHA. A configuração sai pelo [com.radioterapia.ai.transfer.PacoteConfig],
 * que já não leva senha de destino de sincronia — a mesma regra do pacote que
 * viaja por e-mail. Gravar senha em texto no armazenamento compartilhado para
 * economizar uma digitação seria trocar um risco real por uma comodidade.
 */
object BackupPreAtualizacao {

    /** Quantas cópias ficam. As antigas saem: a mais nova é a que interessa, e
     *  acumular cópia de configuração sem limite enche o armazenamento com
     *  arquivo que ninguém abriu. */
    private const val QUANTAS_MANTER = 3

    const val NOME_PASTA = "BACKUP_ATUALIZACAO"

    data class Resultado(val pasta: File?, val arquivos: Int, val bytes: Long) {
        val ok: Boolean get() = pasta != null && arquivos > 0
    }

    /**
     * Grava a cópia e devolve o que foi gravado.
     *
     * Nunca lança: uma falha aqui não pode impedir a atualização, porque o dado
     * que a cópia protege não está em risco na atualização — está em risco na
     * desinstalação, que é outra coisa. Quem chama decide se avisa.
     */
    fun executar(context: Context): Resultado {
        return try {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val raiz = File(StorageLocal.base(context), NOME_PASTA)
            val pasta = File(raiz, stamp)
            pasta.mkdirs()
            if (!pasta.exists()) return Resultado(null, 0, 0L)

            var arquivos = 0
            var bytes = 0L

            // 1) A configuração, pelo mesmo caminho do pacote que o serviço
            //    troca entre tablets — logo, sem senha e item a item.
            val zip = File(pasta, "configuracao.zip")
            try {
                zip.outputStream().use { saida ->
                    com.radioterapia.ai.transfer.PacoteConfig.exportar(
                        context,
                        com.radioterapia.ai.transfer.PacoteConfig.Item.values().toSet(),
                        saida)
                }
                if (zip.exists() && zip.length() > 0) {
                    arquivos++
                    bytes += zip.length()
                }
            } catch (_: Throwable) {
                zip.delete()
            }

            // 2) O cadastro e o que mais estiver em filesDir. É o que a
            //    desinstalação leva, e o que o PacoteConfig não cobre.
            val destinoCadastro = File(pasta, "cadastro")
            destinoCadastro.mkdirs()
            context.filesDir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    try {
                        val alvo = File(destinoCadastro, f.name)
                        f.copyTo(alvo, overwrite = true)
                        arquivos++
                        bytes += alvo.length()
                    } catch (_: Throwable) {
                    }
                }
            }

            gravarLeiaMe(pasta)
            limparAntigas(raiz)
            Resultado(pasta, arquivos, bytes)
        } catch (_: Throwable) {
            Resultado(null, 0, 0L)
        }
    }

    /**
     * O bilhete que explica a pasta a quem a encontrar.
     *
     * Existe porque cópia de segurança sem instrução de restauração é cópia que
     * ninguém restaura. E porque a ausência das fotos precisa estar escrita, ou
     * alguém vai contar com elas no dia errado.
     */
    private fun gravarLeiaMe(pasta: File) {
        try {
            File(pasta, "LEIA-ME.txt").writeText(
                """
                COPIA DE SEGURANCA ANTES DE ATUALIZAR - PhotoID RT
                Gerada em: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())}

                O QUE TEM AQUI
                  configuracao.zip  as configuracoes do servico, item a item.
                                    Restaure em Configuracoes > Transferencia.
                  cadastro/         o cadastro dos pacientes (nome, prontuario,
                                    nascimento, sexo, medico, equipamento).

                O QUE NAO TEM AQUI, E POR QUE NAO PRECISA
                  As FOTOS, os PDFs, o Time-Out e as observacoes nao estao nesta
                  copia. Eles ficam em PhotoID_RT/PHOTOS, no armazenamento do
                  aparelho, e instalar versao nova do aplicativo nao os toca.

                  A SENHA dos destinos de sincronia nao esta aqui, de proposito.
                  Depois de restaurar, digite-a uma vez em Configuracoes.

                COMO RESTAURAR O CADASTRO
                  Copie o conteudo de cadastro/ de volta apenas se o aplicativo
                  tiver sido desinstalado e reinstalado. Uma atualizacao normal
                  preserva o cadastro, e nesse caso nao ha nada a restaurar.
                """.trimIndent()
            )
        } catch (_: Throwable) {
        }
    }

    /** Mantém as [QUANTAS_MANTER] mais recentes, pelo nome — que é a data, e por
     *  isso ordena sozinho. */
    private fun limparAntigas(raiz: File) {
        try {
            val pastas = raiz.listFiles()?.filter { it.isDirectory }
                ?.sortedBy { it.name } ?: return
            if (pastas.size > QUANTAS_MANTER) {
                pastas.take(pastas.size - QUANTAS_MANTER).forEach { it.deleteRecursively() }
            }
        } catch (_: Throwable) {
        }
    }
}
