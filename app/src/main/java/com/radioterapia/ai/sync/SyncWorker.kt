package com.radioterapia.ai.sync

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A varredura rodando fora da tela, e sobrevivendo ao tablet ser reiniciado.
 *
 * POR QUE WORKMANAGER E NÃO UMA CORROTINA
 * O tablet da sala de simulação é desligado no fim do turno, fica sem rede
 * quando alguém leva para o acelerador, e tem o app fechado pelo Android quando
 * a memória aperta. Uma corrotina morre em qualquer um desses três casos e
 * ninguém fica sabendo — o técnico acha que sincronizou. O `WorkManager` guarda
 * o pedido em disco e o retoma quando as condições voltam.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val cfg = SyncConfig(applicationContext)
        // O INTERRUPTOR MESTRE MANDA, inclusive aqui. Desligar a sincronização
        // cancela os trabalhos agendados, mas um trabalho que já estava na fila
        // do sistema ainda chega. Sem esta linha, desligar não desligaria.
        if (!cfg.ativo) return Result.success()

        val resumos = try {
            // O sistema pode parar o trabalho antes do prazo (perda de rede,
            // restrição de bateria): a varredura confere isStopped a cada
            // arquivo e não segue enviando depois disso.
            MotorSync(applicationContext).sincronizarTudo(continuar = { !isStopped })
        } catch (_: Exception) {
            // Falha inesperada é temporária por definição: se fosse conhecida,
            // teria sido classificada no adaptador. Tentar de novo com recuo é
            // mais barato que perder a rodada.
            return Result.retry()
        }

        // RETENTATIVA COM RECUO, e sem contador próprio. O WorkManager já dobra
        // o intervalo a cada `retry` e persiste a contagem; reimplementar isso
        // aqui daria dois relógios discordando depois de um reinício.
        return if (resumos.any { it.houveFalha }) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODICO = "photoid_sync_periodico"
        private const val IMEDIATO = "photoid_sync_imediato"

        /**
         * Atraso do gatilho de finalização, em segundos.
         *
         * GUARDA: precisa ficar ACIMA da janela de frescor do motor
         * ([MotorSync.JANELA_FRESCOR_MS]). A finalização copia fotos e PDF para
         * a pasta do paciente e enfileira o trabalho logo em seguida, e cada
         * cópia nasce com data de modificação nova. Sem o atraso, a varredura
         * pode começar antes de esses arquivos saírem da janela, pulá-los como
         * recém-escritos, terminar com sucesso — e a simulação que acabou de
         * ser finalizada esperar a rodada periódica, sem nem contar como
         * pendente.
         */
        const val ATRASO_FINALIZAR_S = 5L

        private fun restricoes(cfg: SyncConfig) = Constraints.Builder()
            .setRequiredNetworkType(
                if (cfg.somenteRedeNaoTarifada) NetworkType.UNMETERED else NetworkType.CONNECTED)
            // BATERIA BAIXA SEGURA A FILA. Subir um acervo de fotos com o tablet
            // em 10% no meio do turno é trocar documentação por autonomia, e a
            // documentação pode esperar o carregador.
            .setRequiresBatteryNotLow(true)
            .build()

        /**
         * Põe (ou tira) o trabalho periódico de pé conforme a configuração.
         *
         * Chamado sempre que a configuração muda e na abertura do app. Usar
         * `UPDATE` em vez de `KEEP` é o que faz a mudança de intervalo valer
         * imediatamente — com `KEEP`, trocar de 60 para 15 minutos não teria
         * efeito nenhum até alguém desinstalar o app.
         */
        fun reprogramar(context: Context) {
            val cfg = SyncConfig(context)
            val wm = WorkManager.getInstance(context)
            if (!cfg.ativo) {
                wm.cancelUniqueWork(PERIODICO)
                wm.cancelUniqueWork(IMEDIATO)
                return
            }
            val pedido = PeriodicWorkRequestBuilder<SyncWorker>(
                cfg.intervaloMinutos.toLong(), TimeUnit.MINUTES)
                .setConstraints(restricoes(cfg))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                PERIODICO, ExistingPeriodicWorkPolicy.UPDATE, pedido)
        }

        /**
         * Um gatilho: foto salva, simulação finalizada, app aberto.
         *
         * `APPEND_OR_REPLACE` na mesma fila nomeada. Duas fotos salvas em
         * sequência não disparam duas varreduras concorrentes contra o mesmo
         * servidor — a segunda espera a primeira, e como o índice já registrou
         * o que subiu, ela encontra pouco a fazer.
         */
        fun agora(context: Context) {
            enfileirar(context, 0L)
        }

        /**
         * Põe um trabalho único na fila imediata e devolve o id dele.
         *
         * Nulo quando o interruptor mestre está desligado: nada foi enfileirado.
         */
        private fun enfileirar(context: Context, atrasoS: Long): UUID? {
            val cfg = SyncConfig(context)
            if (!cfg.ativo) return null
            val construtor = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(restricoes(cfg))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            if (atrasoS > 0L) construtor.setInitialDelay(atrasoS, TimeUnit.SECONDS)
            val pedido = construtor.build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(IMEDIATO, ExistingWorkPolicy.APPEND_OR_REPLACE, pedido)
            return pedido.id
        }

        /** Gatilho de foto salva, respeitando a preferência do serviço. */
        fun aoSalvarFoto(context: Context) {
            if (SyncConfig(context).gatilhoAoSalvarFoto) agora(context)
        }

        /**
         * Gatilho de simulação finalizada — o momento em que a ficha fica pronta.
         *
         * Devolve o id do trabalho enfileirado, ou nulo quando nada foi
         * enfileirado (interruptor mestre desligado, ou o serviço desligou
         * «enviar ao finalizar»). Começa [ATRASO_FINALIZAR_S] segundos depois,
         * pelo motivo descrito ali.
         *
         * Quem quer acompanhar observa PELO ID ([observar]), não pelo nome da
         * fila: com `APPEND_OR_REPLACE` o nome pode estar segurando um trabalho
         * anterior, de foto salva, e só o id diz se ESTA finalização rodou.
         */
        fun aoFinalizar(context: Context): UUID? {
            if (!SyncConfig(context).gatilhoAoFinalizar) return null
            return enfileirar(context, ATRASO_FINALIZAR_S)
        }

        /**
         * O estado de um trabalho enfileirado, para a tela acompanhar.
         *
         * A API do WorkManager é Java: o valor emitido chega nulo se o trabalho
         * deixar de existir (o WorkManager poda trabalho terminado há dias).
         */
        fun observar(context: Context, id: UUID): LiveData<WorkInfo> =
            WorkManager.getInstance(context).getWorkInfoByIdLiveData(id)

        /**
         * A finalização tem um envio para acompanhar? Interruptor mestre
         * ligado, «enviar ao finalizar» ligado e ao menos um destino ativo e
         * utilizável.
         *
         * Lê o arquivo de perfis: chamar fora da thread principal.
         */
        fun acompanhavelAoFinalizar(context: Context): Boolean {
            val cfg = SyncConfig(context)
            if (!cfg.ativo || !cfg.gatilhoAoFinalizar) return false
            return PerfilStore(context).listar().any { it.ativo && it.utilizavel() }
        }

        /** Gatilho de abertura, para o tablet que passou a noite desligado. */
        fun aoAbrir(context: Context) {
            reprogramar(context)
            if (SyncConfig(context).gatilhoAoAbrir) agora(context)
        }
    }
}
