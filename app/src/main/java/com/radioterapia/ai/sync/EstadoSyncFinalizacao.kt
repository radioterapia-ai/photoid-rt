package com.radioterapia.ai.sync

/**
 * O que a linha de sincronia da tela de finalização diz, a partir do estado do
 * trabalho que a própria finalização enfileirou.
 *
 * PURO, sem Android: a tela traduz o `WorkInfo` do WorkManager em [Fase] e a
 * conferência local da pasta em `total`/`pendentes`, e a decisão do que mostrar
 * mora aqui, onde o teste alcança a tabela inteira sem aparelho.
 *
 * A REGRA QUE ORIENTA A TABELA: a linha só diz «realizada» depois de conferir.
 * O trabalho terminar com sucesso não basta — ele pode ter rodado antes de os
 * arquivos da simulação existirem, ou com um destino recusando parte deles.
 * Por isso SUCESSO sem conferência continua «em curso», e SUCESSO com arquivo
 * pendente é «não realizada».
 */
object EstadoSyncFinalizacao {

    /**
     * Quanto tempo um trabalho pode ficar na fila sem começar antes de a linha
     * desistir de «em curso».
     *
     * Na fila parado é sem Wi-Fi com «somente rede sem franquia», bateria baixa,
     * ou atrás de outro trabalho em recuo. Nenhum desses se resolve enquanto o
     * técnico olha a tela, e «em curso» para sempre seria a linha mentindo.
     */
    const val LIMITE_ESPERA_MS = 120_000L

    /** O estado do trabalho, já traduzido do `WorkInfo.State` pela tela. */
    enum class Fase { AGUARDANDO, RODANDO, SUCESSO, FALHA, CANCELADO }

    /** O que a linha mostra. [OCULTA] é a linha fora da tela. */
    enum class Linha { OCULTA, EM_CURSO, OK, OK_SEM_CONFERENCIA, FALHOU }

    /**
     * @param fase nulo quando não há trabalho acompanhado.
     * @param tentativas `runAttemptCount` do trabalho; acima de zero na fila
     *   significa que ele já rodou e pediu nova tentativa.
     * @param decorridoMs tempo desde que a finalização enfileirou o trabalho.
     * @param total arquivos que a conferência local encontrou na pasta da
     *   simulação; nulo enquanto a conferência não rodou.
     * @param pendentes desses, quantos ainda faltam em algum destino ativo;
     *   nulo enquanto a conferência não rodou.
     */
    fun calcular(fase: Fase?, tentativas: Int, decorridoMs: Long,
                 total: Int?, pendentes: Int?): Linha {
        if (fase == null) return Linha.OCULTA
        return when (fase) {
            Fase.RODANDO -> Linha.EM_CURSO
            // Já rodou e voltou para a fila: a rodada falhou. A tela continua
            // observando, e se a nova tentativa der certo a linha muda sozinha.
            Fase.AGUARDANDO ->
                if (tentativas > 0) Linha.FALHOU
                else if (decorridoMs < LIMITE_ESPERA_MS) Linha.EM_CURSO
                else Linha.FALHOU
            Fase.FALHA, Fase.CANCELADO -> Linha.FALHOU
            Fase.SUCESSO -> when {
                total == null -> Linha.EM_CURSO
                // Nada na pasta que o motor enviaria — armazenamento por SAF,
                // por exemplo, que fica fora da origem do motor. A rodada deu
                // certo, mas não há como afirmar que ESTE prontuário subiu.
                total <= 0 -> Linha.OK_SEM_CONFERENCIA
                pendentes == null -> Linha.EM_CURSO
                pendentes <= 0 -> Linha.OK
                else -> Linha.FALHOU
            }
        }
    }
}
