# Fase 1 — snapshot de pose para combate

Branch: `feature/combat-pose-snapshot`, criada de
`883035ee7d0d15cba7298ab4e29a8aec22849587`.
Esta entrega fornece avaliação de armature e um contrato afim explícito. Não
conecta o resultado ao hit resolver, não implementa collider e não muda o main.

## Arquitetura e propriedade dos dados

`CombatPoseState` pertence ao tick/ação. `CombatController` observa identidade de
player/world no tick, no início de ação e na captura; início de clip incrementa
`actionSerial`, inclusive ao repetir o mesmo clip. Encerramento invalida a captura.
Nenhum relógio, regra de seleção, timing, dano ou ação foi transferido para o
avaliador. O serial também distingue ações de dash, sem alterar o dash.

`captureCombatPoseFrame(profileRevision)` fornece `Frame` imutável com identidade
por referência de player/world, serial, tick, clip, previous/current action age e
revisão do perfil. Uma mudança observada de contexto/tick, início/fim de ação ou
nova captura com idades/revisão diferentes invalida frames e snapshots anteriores.
Capturas idênticas reutilizam o mesmo frame. Não há recuperação implícita de uma
ação antiga após troca de player/world: é necessário novo início de ação.

`CombatPoseEvaluator` recebe armature e biblioteca de clips de uma geração fixa
de assets. Copia nomes/parents/bind locals; possui apenas buffers de composição e
cache. Usa `Clip.sampleTRS`, `AnimationPose.overlay`/SLERP já existentes e
`ArmaturePoseMath`. Não instancia nem chama `Animator`, `AnimationPlayer`,
`AnimationLayer`, `PoseEngine`, input, guard, câmera, GL ou callbacks de renderer.
Também compila no runner standalone sem essas classes de cliente.

`Plan` é uma composição explícita e imutável: camadas ordenadas com clip, tempo
anterior/atual, loop, peso, máscara e política de Root; depois a ação do Frame
com seu peso/máscara. Não seleciona locomotion, não guarda clocks e não toma
decisões de transição. O chamador deve capturar esses dados no tick, nunca lendo
`AnimationLayer.effectiveWeight`, que pode alterar `freshEntry`, para alimentar
este caminho. A API desta entrega não exporta as camadas mutáveis do Animator.

`sampleTime` é idade absoluta da ação em segundos de clip, no intervalo inclusivo
capturado. Já incorpora a progressão/CPS determinada pelo controlador; avaliação
não aplica velocidade novamente. Endpoints de camadas em loop devem estar
desenrolados (por exemplo, duration-.02 até duration+.03). `Clip` faz o wrap.

`CombatPoseSnapshot` contém todos os joint globals/locals calculados uma vez,
Frame, perfil, tempo e validade. Matrizes só saem por cópia; snapshot expirado ou
inválido recusa leitura. Ausência de ação/assets, tempo inválido ou hierarquia não
finita retorna snapshot inválido com motivo. Argumentos estruturais inválidos de
construtores/captura geram `IllegalArgumentException`.

Toda a API é de uma única game thread. Para combate de múltiplas entidades,
manter um state/evaluator por combatente. Não é uma API de render thread/network.
O owner deve observar contexto a cada tick e antes de reutilizar capturas; a
validade não consulta o Minecraft por conta própria. O chamador é responsável por
incrementar `profileRevision` quando mudar o perfil espacial/DBC ou a geração de
assets, e recriar o evaluator se trocar os assets. Não usar como revisão a última
revisão publicada por callback visual de JBRA. Assets não podem ser mutados
enquanto o evaluator estiver em uso.

## Cache e uso por múltiplos targets

Um evaluator memoriza `(Frame, identidade do Plan, bits de sampleTime)`. Construir
um Plan por composição/tick e reutilizar a MESMA instância para todos os targets.
Plans diferentes são composições potencialmente diferentes; o cache não tenta
deduzir equivalência. Trocar Frame limpa o cache. Até 128 combinações de Plan e
tempo ficam retidas; exceder o orçamento retorna inválido, sem descartar uma
amostra válida e recalculá-la por target. `completeEvaluations()` e `cacheHits()`
são contadores monotônicos de diagnóstico, separados do profiler visual.

Exemplo conceitual (não conectado ao hit resolver nesta fase):

```java
CombatPoseState.Frame frame = combat.captureCombatPoseFrame(profileRevision);
// plan já capturado no tick; evaluator criado uma vez para estes assets.
CombatPoseSnapshot sample = evaluator.evaluate(frame, plan, actionSampleTime);
if (!sample.isValid()) return; // sem fallback para a última pose desenhada
// Todos os targets consultam o mesmo sample. Nenhum target recalcula armature.
sample.copyGlobalJointMatrix(jointIndex, jointMatrix);
```

## Joint globals, skinning e coordenadas

`ArmaturePoseMath` extrai a matemática existente, compartilhada com
`LayeredAnimator`, preservando sua ordem de operações:

```text
local[j] = bindLocal[j] * TRS[j]
global[j] = parentGlobal * local[j]     (ou local[j] na raiz)
skin[j] = global[j] * inverseBind[j]   (função separada, só skinning)
worldJoint = translate(worldOrigin) * modelToPlayer * global[j]
```

`global[j]` está em armature/model space e é a matriz a usar para a origem do
joint. `skin[j]` inclui inverse bind e não representa posição de joint.
Matrizes são row-major, vetores-coluna, translação nos índices 3/7/11.

`ModelToWorld` recebe uma matriz afim explícita, finita e não singular
`modelToPlayer`, incluindo base/eixos, escala/unidades e pivot. Soma a origem
world em double e multiplica globals sem ler GL. Não presume que unidade do asset
seja bloco nem escolhe yaw, offset de olhos, inversão de eixos ou escala DBC.
O teste usa uma matriz sintética com rotação, escala não uniforme e pivot, como
teste matemático; ela não é uma configuração proposta para Minecraft/DBC.

Perfis disponíveis:

- `SOURCE_ARMATURE`: TRS authored, inclusive movimento de Root. Não é o fallback
  visual genérico, que zera Root X/Z durante apresentação.
- `DBC_ARMATURE`: sanitização RC2 compartilhada (translation/scale e Root yaw,
  preservando helpers Knee/Elbow). Não é a pose final retargeted de JBRA.

## Fronteira DBC/JBRA e menor próximo passo

A conversão completa permanece **não disponível**. O draw possui pivots/sockets,
retarget e apresentação prone do modelo concreto; reutilizar a última matriz
publicada pelo renderer faria o combate depender de render/FPS. Não há offset
inventado, leitura de GL ou fallback silencioso para `skinMatrices`.

Além dessa conversão, a API não reproduz automaticamente link snapshots,
stabilização de locomotion, landing procedural nem o modifier de ataque que
depende de primeira/terceira pessoa. A composição recebida é explícita, não uma
alegação de equivalência entre pose espacial de combate e todo efeito visual.
O caminho visual continua executando todos esses comportamentos como antes.

Antes de habilitar joint-local: capturar um descritor espacial imutável no tick
com a base/pivot/escala/prone e os dados dos sockets do perfil concreto, extrair a
transformação pura correspondente e compará-la ao draw atual com fixtures reais
de JBRA. Se a pose de combate precisar de camadas/transições além de clips
explícitos, definir sua entrada autoritativa no tick e extrair os operadores
necessários do Animator, sem consumir as camadas de apresentação e sem criar um
segundo controlador de animação. Isso exige revisão separada; não foi improvisado
nesta branch. O contrato de mundo está pronto, o adaptador final DBC ainda não.

## Testes e evidência

Executar na raiz (Java 8; ECJ 4.6.1, como na auditoria):

```powershell
./tools/tests/run_combat_pose.ps1 -CompilerJar build/audit/ecj-4.6.1.jar
./tools/tests/run_audit.ps1 -CompilerJar build/audit/ecj-4.6.1.jar -Python <python>
```

O runner novo compila as classes reais de Animator, controller, guard e Compat.
Somente entry point Forge/resource anchor, teclado nativo e renderer de contadores
são doubles em `tools/tests/headless`, fora de `src/main` e do artefato do mod.
Os doubles de input/renderer falham se chamados. Player/settings são fixtures
refletidas pelo Compat real. A versão antiga completa de `LayeredAnimator` é
obtida com `git show 883035e:...`, renomeada e compilada somente em `build/`.
O teste não escreve nem atualiza os goldens existentes.

| Requisito | Evidência executada |
|---|---|
| Sem render | Snapshot avaliado antes de criar Animator visual. |
| 0/1/10 renders e câmera | Composição visual real, câmera 0/1/2, misses e hits generic/DBC iguais. São chamadas de composição, não draws GPU. |
| Clocks e camadas | `AnimationPlayer` anterior/atual, `freshEntry`, pose serial e matrizes visuais preservados. |
| Guard | Reaction pendente do `CombatGuardRuntime` real intacta; depois consumida exatamente uma vez. |
| Serial de execução | Repetir clip invalida anterior/incrementa serial; também testado pelo `CombatController` real. |
| Contexto | Player, world, tick, idades, revisão e clear invalidam; troca de world detectada na captura do controller. |
| Perfis/cache | Generic/DBC alternados, 100 targets: apenas 2 hierarquias completas (uma por perfil). Limite de 128 sem eviction/recompute. |
| Hierarquia | 968 poses dos 242 clips: globals finitos e parent*local corretos; locais comparados ao sampling authored. |
| Extração visual | 4.356 composições (242 clips, 3 tempos, 2 perfis, 3 câmeras) e skin bit a bit iguais ao commit aprovado. |
| Voo/transições | Outras 180 composições com entrada/saída de voo, guard, landing e transições iguais ao aprovado. |
| Golden/regressões | 480 matrizes golden existentes, timing, CPS, profiler OFF/ON, OBB atual, registry e mount: PASS. |
| Assets | Auditoria binária PASS; 242/242 tracks/tempos correspondem ao upstream fixado, nenhuma diferença. |

Nenhum asset, clip, renderer, mount Tool_R, flight/dash, guard ou hit resolver foi
editado. Alterações de produção preexistente: delegação matemática no Animator,
metadados no controller e getter de identidade world no Compat. Não foram
encontradas diferenças nas matrizes protegidas testadas. Isso não substitui
aceitação visual no Minecraft: não houve draw OpenGL/JBRA nem build Forge completo
nesta execução. Warnings preexistentes de imports/campos não utilizados continuam
presentes; não houve limpeza de código fora do escopo.
