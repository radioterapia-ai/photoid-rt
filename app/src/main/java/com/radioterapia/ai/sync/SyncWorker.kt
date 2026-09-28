package com.radioterapia.ai.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
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
            MotorSync(applicationContext).sincronizarTudo()
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
            val cfg = SyncConfig(context)
            if (!cfg.ativo) return
            val pedido = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(restricoes(cfg))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(IMEDIATO, ExistingWorkPolicy.APPEND_OR_REPLACE, pedido)
        }

        /** Gatilho de foto salva, respeitando a preferência do serviço. */
        fun aoSalvarFoto(context: Context) {
            if (SyncConfig(context).gatilhoAoSalvarFoto) agora(context)
        }

        /** Gatilho de simulação finalizada — o momento em que a ficha fica pronta. */
        fun aoFinalizar(context: Context) {
            if (SyncConfig(context).gatilhoAoFinalizar) agora(context)
        }

        /** Gatilho de abertura, para o tablet que passou a noite desligado. */
        fun aoAbrir(context: Context) {
            reprogramar(context)
            if (SyncConfig(context).gatilhoAoAbrir) agora(context)
        }
    }
}
