# Roadmap de paridade — Epic Fight 20.9.5 / Forge 1.7.10 / DBC

Baseline imutável: `v2.2.0-RC2-STABLE` / `ce1d9162003718bc8bdf0f1d94e82e32de1293f8`.
Referência upstream: `a0a1027cdd210821c0b3bcc461f1ca170303d5fc` (20.9.5).
Veja [auditoria e evidências](ARCHITECTURE-AUDIT.md).

Atualização da Fase 1: infraestrutura implementada em `feature/combat-pose-snapshot`,
a partir da auditoria aprovada `883035e`. Consulte o
[contrato, testes e limites de CombatPoseSnapshot](COMBAT-POSE-SNAPSHOT.md).
A conversão final DBC/JBRA e o consumo pelo collider continuam pendentes; o gate
de integração da Fase 2 não está liberado por esta entrega. As decisões de auditoria
abaixo registram o estado original da Fase 0.

Dependência espacial: `feature/dbc-spatial-transform` extrai os operadores locais
compartilhados com o renderer, mas para na fronteira nativa de escala/bases por
parte/forms/fast flight. Veja [evidências e dados faltantes](DBC-SPATIAL-TRANSFORM.md).
Transformação completa para world e joint-local continuam bloqueados.

## Decisão desta entrega

Auditoria e testes standalone em `feature/joint-local-collider`. Nenhum runtime,
asset, timing, mount ou renderer foi alterado. Joint-local **não implementado**:
os bloqueios B1/B2/B3 da auditoria impedem uma conexão correta usando a API atual.
O próximo trabalho é a fronteira de avaliação de pose, não mover uma OBB para a
última posição de mão desenhada. Não há merge automático, release nova ou tag movida.

## Regras de execução

- Uma branch por objetivo; criar a partir do baseline ou de dependência já revisada.
- Não misturar build, input, renderer, combate e assets numa refatoração ampla.
- Preservar WORLD-BODY, Tool_R visual, voo, dash, JBRA e clips; qualquer alteração
  nesses contratos deve ser explícita, isolada e comparada com upstream e RC2.
- Cada fase entrega testes, diff, limitações e evidência antes de revisão/merge.
- Rollback: descartar a branch antes do merge; depois, revert da fase e reexecução
  dos testes da dependência. Nunca force-push no baseline ou mover sua tag.
- Não considerar clips registrados como features portadas. Não alegar FPS ou
  correção visual com base somente em inspeção estática.

## Fases e dependências

| Fase | Branch | Dependência | Escopo / saída verificável |
|---|---|---|---|
| 0 — auditoria | `feature/joint-local-collider` (entrega atual, somente docs/testes) | RC2 | Inventário, limites, bloqueios, 242 clips e goldens raw. |
| 1 — avaliação de pose para combate | `feature/combat-pose-snapshot` | 0 | Snapshot com identidade temporal, avaliação sem efeitos colaterais e contrato matemático modelToWorld/DBC. |
| 2 — primeiro joint-local | `feature/joint-local-collider` | 1 revisada | Binding por fase e volume ligado a joint, reutilizando snapshot. Sem alterar timing/dano/mount. |
| 3 — trajetória temporal | `feature/swept-collider` | 2 | Amostras recortadas à janela; endpoints e intermediárias da animação. |
| 4 — trajetória por arma | `feature/weapon-collider` | 3 | Joint/volume por fase e WeaponType, Tool_R/Tool_L, exceções Root e multi-collider. |
| 5 — estados de combate | `feature/combat-state-parity` | 2 + harness de combate | Estados por fase, recovery, limits/priority/dedupe sem reimplementar dano DBC inadvertidamente. |
| 6 — capability | `feature/weapon-capability-parity` | 4/5 | Resolução de itens, styles, properties e living motions com fonte explícita. |
| 7 — transições | `feature/animation-transition-system` | 1 + goldens finais | Prioridades, máscaras, link/interrupt e continuidade. |
| 8 — input | `refactor/combat-input` | 5 | Snapshot/event queue central, preservando mouse de alta frequência e ordem Forge. |
| 9 — medições | `perf/runtime-measurements` | runner de cliente | Expandir profiler existente com contadores/percentis sem custo quando desligado. |
| 10 — JBRA | `perf/jbra-skinning` | 9 | Só otimizações justificadas por medições e goldens/visual parity. |
| 11 — Animator | `perf/animator` | 7/9 | Reduzir avaliações/allocations comprovadas, mesma pose e frequência. |
| Paralela — build | `chore/reproducible-build` | RC2 | JDK/Gradle/ForgeGradle fixados, wrapper, dependências e build de release verificável. |

Nenhuma dessas branches futuras é criada nesta entrega. A fase de build pode ser
feita antes da integração runtime para permitir os gates de startup/packaging.

## Fase 1: menor pré-requisito arquitetural

Objetivo único: disponibilizar pose válida de combate sem interferir no renderer.

Contrato proposto (nomes ilustrativos, não APIs existentes):

```text
CombatPoseSnapshot
  player/world identity, actionSerial, tick, previousAge, currentAge, profileRevision
  joint globals in armature space
  modelToWorld and explicit DBC socket conversion
  sample time / validity

prepareSamples(snapshot, uniqueTimes)
  one reusable full hierarchy per unique sample
  no input consumption, no guard polling, no GL, no renderer callback
```

Separar avanço de estado e composição com a menor extração possível do Animator,
preservando ordem e fórmulas existentes. Não criar uma segunda implementação de
SLERP/sanitização/retarget. Definir como modifiers de câmera entram na apresentação
e como a referência espacial de combate permanece determinística. Um snapshot
com `poseSerial` sem action/time/world não resolve o problema.

A transformação DBC deve ter origem em dados válidos de entidade/modelo, com
unidades, eixos, pivots e escalas documentados; não ler OpenGL nem copiar o último
`rightHandSocketDelta`. Se a geometria atual só expuser esses dados durante render,
primeiro extrair a transformação pura e provar equivalência, preservando o draw.

Testes obrigatórios: tick sem render; 0/1/10 renders no mesmo tick; câmera alternada;
generic/DBC intercalados; entrada/saída de voo; duas execuções do mesmo clip;
troca de mundo/jogador; guard reaction consumida uma única vez; poses finais antes/
depois idênticas para os contratos protegidos. Golden raw atual é apenas a base;
faltam fixtures da composição final e do retarget DBC.

Gate: nenhum side effect de avaliação e nenhuma diferença visual nos sistemas
protegidos. Revisão antes de habilitar consumo pelo collider.

## Fase 2: joint-local mínimo

Substituir somente a origem/orientação espacial dos golpes explicitamente mapeados.
Manter ataques ainda não mapeados identificados como caminho legado; não chamá-los
de joint-local. Não esconder falha de snapshot sob fallback silencioso.

Mapeamento deve vir de Animations/Phase upstream. Punhos alternam lados: auto1/3
usam Tool_L e auto2 Tool_R; Tool é filho de Hand. Adaptação para Hand/Arm precisa
de conversão do offset e teste contra a trajetória. Relentless usa Root/FIST_FIXED
upstream; preservá-lo explicitamente nesta primeira fase é preferível a inventar
um volume de mão. Tool_R visual nunca recebe as matrizes do collider de volta.

Preparar transformações de todos os joints necessários uma vez por amostra e
reutilizá-las em todos os targets. Não mudar clips, CPS, hit windows, recovery,
filtro de dano, flight ou dash nesta fase. Manter no relatório as limitações de
OBB conservadora e broad/facing; mudanças nessas políticas precisam de revisão
própria para não confundir efeito de trajetória com alteração de alcance.

Testes: parent × bind × local; tradução/rotação/yaw ±180°; escala; centro/half-extents;
lado esquerdo/direito; Tool sem RenderItemBase correction; pose indisponível;
dois alvos tocados; mesmo alvo não atingido duas vezes por janela; duas janelas
podem atingir o mesmo alvo; troca de ação limpa dedupe; contador de avaliações
independente do número de targets. Comparar debug collider com a mão/arma real no
cliente em terra, voo e formas DBC antes de promoção.

## Fases 3 e 4: sweep e weapons

Recortar [prevAge, age] à janela ativa antes de amostrar. Interpolar pose/transform
de entidade nos mesmos tempos. Sweep por segmentos discretos deve declarar sua
aproximação; não confundir com continuous collision detection exata. A referência
20.9.5 usa MultiCollider com amostras dependentes de attack speed, não um simples
lerp de duas matrizes 4×4.

Testar janela atravessada a 2x, frame sem hit ativo, contato somente no meio do
arco, motion do alvo, rotação grande, múltiplos joints/volumes e custo limitado.
Depois estender WeaponType com joint/phase/hand/collider por definição validada,
incluindo espada, dagger, spear, two-hand e exceções Root. Nenhuma correção visual
de mount entra no volume espacial. Não alterar todos os presets de uma vez.

## Fases 5 e 6: paridade de combate e capability

Extrair os estados por fase do source, diferenciando antic, preDelay, contact,
recovery e end. Decidir explicitamente a integração com movimento/turning DBC,
em vez de ativar flags atualmente apenas declarativas. Tratar limites de alvos,
prioridade, attacked vs hurt, iframe e multiplayer como decisões separadas;
PlayerControllerMP despachado não é confirmação de dano.

Testes de StateSpectrum nas bordas; reset/interrupt/restart; estilo e mount;
hot reload inválido/geração; troca de item no tick; regra desconhecida; perfis
por arma; prioridade/limite/dedupe. Hit stun/knockback só com integração DBC
fonteada e ensaios reais; não impor a escala de dano modernizada ao DBC.

## Fase 7: continuidade de animação

Cobrir antes de editar: idle↔walk↔run, jump→landing, sneak, guard on/off/hit,
combo encadeado, skill interrupt, Ki aim, weapon stance e flight forward↔backward.
Comparar camadas/priority/masks/link do upstream. Remover aproximações somente
com substituto verificado: FALL suprimido e landing procedural são contratos de
compatibilidade existentes, não oportunidades para uma correção incidental.

Gate: goldens da pose final, ausência de salto de joint em interrupção e teste
visual na mesma cena. Alterações que toquem POV requerem branch exclusiva
(`feature/first-person-parity`) e escopo aprovado; ela não faz parte desta fase.

## Performance e build

Medir cenas iguais OFF/ON, cold/warm cache, múltiplas entidades e formas DBC.
Registrar CPU, frametime p50/p95/p99, allocations, avaliações por tick e chamadas
de reflexão. Não somar slots aninhados do profiler. Otimizações devem manter
matrizes/qualidade/frequência, sem pose por vértice, target ou collider.

Build reproduzível deve fixar o ForgeGradle atualmente dinâmico, prover Java 8,
compilar contra APIs reais e executar verificador de annotations/major52/JAR.
Não usar stubs de compilação que possam reapresentar SubscribeEvent invisível.
A identidade de bytecode do transformer protegido é gate de release, além de
source diff. Nenhum wrapper ou arquivo de build foi modificado agora.

## Gates de regressão para cada fase runtime

Automáticos: startup/package, RuntimeProfiler OFF/ON, registro de armas/animações,
armature, pose matrices/goldens, hit windows, CPS, StateSpectrum, hot reload,
collider/multi-target e regressões específicas da fase.

Cliente mínimo e modpack: startup, abrir mundo, Battle Mode OFF/ON, punhos e
relentless, guard, walk/run/sneak/jump/fall/landing, dash, Ki, voo normal/rápido,
primeira/terceira pessoa, Tool_R, armas, transformações DBC, cabeça/rosto/cabelo
JBRA, ausência de corpo duplicado e frametime. Registrar versões, logs e cena.

Revisor recebe: diff contra baseline/dependência, arquivos alterados, testes
executados e não executados, divergências restantes, medições e instrução de
rollback. Somente depois da revisão humana considerar merge no main.
