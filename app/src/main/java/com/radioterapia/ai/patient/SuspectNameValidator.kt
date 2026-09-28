package com.radioterapia.ai.patient

import java.text.Normalizer
import java.util.Locale

/**
 * Detecta nomes que provavelmente estão incompletos ou errados.
 * Usado para gerar uma confirmação extra antes de criar a simulação.
 *
 * Não é um validador rígido — é uma rede de segurança contra erros óbvios
 * (ex: técnico apertou Enter sem digitar sobrenome).
 */
object SuspectNameValidator {

    /**
     * Lista de nomes muito comuns que, isolados sem sobrenome, são quase
     * certamente entrada incompleta.
     */
    private val NOMES_COMUNS_ISOLADOS = setOf(
        "JOAO", "JOSE", "MARIA", "ANA", "ANTONIO", "PEDRO", "PAULO", "CARLOS",
        "FRANCISCO", "MANUEL", "MANOEL", "LUIS", "LUIZ", "LUCAS", "MIGUEL",
        "GABRIEL", "RAFAEL", "DANIEL", "MATEUS", "MATHEUS", "BRUNO", "MARCOS",
        "MARCO", "MARCIO", "ROBERTO", "RICARDO", "EDUARDO", "FERNANDO", "LUCIA",
        "ANA", "JULIA", "JULIANA", "PATRICIA", "FERNANDA", "ROSANE", "ROSA",
        "MARGARIDA", "BEATRIZ", "TERESA", "TEREZA", "SILVIA", "SILVANA",
        "REGINA", "ROSANGELA", "SANDRA", "ELIZABETE", "ELIZABETH", "CRISTINA"
    )

    sealed class Resultado {
        object Ok : Resultado()
        data class Suspeito(val motivo: String) : Resultado()
    }

    fun validar(nome: String): Resultado {
        val normalizado = normalizar(nome)

        if (normalizado.length < 5) {
            return Resultado.Suspeito("O nome tem menos de 5 caracteres.")
        }

        val palavras = normalizado.split(" ").filter { it.isNotBlank() }

        if (palavras.size < 2) {
            return Resultado.Suspeito("O nome tem apenas uma palavra (sem sobrenome). Pacientes geralmente têm nome e sobrenome.")
        }

        // Caso especial: 2 palavras, uma delas é nome comum isolado e a outra é muito curta
        if (palavras.size == 2) {
            val p1 = palavras[0]
            val p2 = palavras[1]
            // Ex: "ANA M" ou "JOSE S"
            if ((p1 in NOMES_COMUNS_ISOLADOS && p2.length <= 2) ||
                (p2 in NOMES_COMUNS_ISOLADOS && p1.length <= 2)) {
                return Resultado.Suspeito("O sobrenome parece truncado.")
            }
        }

        // Detecta padrões obviamente inválidos
        if (Regex("^[A-Z]\\.?\\s*[A-Z]?\\.?$").matches(normalizado)) {
            return Resultado.Suspeito("Apenas iniciais foram digitadas.")
        }

        return Resultado.Ok
    }

    private fun normalizar(nome: String): String {
        val semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return semAcento
            .replace(Regex("[^A-Za-z ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase(Locale("pt", "BR"))
    }
}

