# Skills deste projeto — e onde está o repositório comum

**Este repositório não hospeda skills.** O repositório comum do ecossistema
Radioterapia.AI mora em `local-suite`, na pasta `.claude/skills/` — 33 skills,
com catálogo, procedência, licenças e a ressalva de cada uma.

> **Por que não uma cópia aqui.** Duas árvores divergem, e a que fica para trás
> é a que ninguém relê — é a mesma razão pela qual `checar_listas_de_preservados`
> existe no `local-suite`. Uma árvore, dois caminhos para alcançá-la.

## Os dois caminhos

**1. Anexar o `local-suite` como fonte desta sessão.** É um clique ao criar a
sessão, e o Claude Code carrega as skills dele junto. É o caminho de menos
manutenção.

**2. Apontar para a pasta espelhada.** Na máquina, rodando de dentro do
`local-suite`:

    sincronizar_skills.cmd

Ele espelha a árvore para `C:\AI_PROJETOS\SKILLS`. Dê acesso dessa pasta ao
agente desta sessão.

## O que ainda NÃO existe, e é justamente para este repositório

**Não há skill de Kotlin, Android ou Gradle em fonte pública nenhuma** —
varridas obra/superpowers, mattpocock/skills, anthropics/skills,
wshobson/agents, nvidia/skills, knowledge-work-plugins, mindrally, hashicorp,
patricio0312rev e karpathy, em 20/09/2026. O que mais se aproxima é
`wshobson/agents@frontend-mobile-development`, e ele é **React Native e
Flutter**: 19 menções a React Native contra 3 a Kotlin.

> Instalar aquilo aqui seria prescrever a stack errada num repositório nativo —
> o mesmo erro da `internationalization-i18n`, que prescreve i18next num projeto
> Python.

**O caminho é escrever a nossa**, com o `skill-creator`. Os doze footguns do
`CLAUDE.md` deste projeto já são o conteúdo dela: a `TabPage` que informa
200 × 100 antes de aparecer, `catch (Exception)` que não pega `NoSuchMethodError`,
`obtainStyledAttributes` com `int[]` fora de ordem devolvendo o atributo errado
em silêncio, recorte por índice engolindo vizinhos, e a View tocada em
`Dispatchers.IO`.
