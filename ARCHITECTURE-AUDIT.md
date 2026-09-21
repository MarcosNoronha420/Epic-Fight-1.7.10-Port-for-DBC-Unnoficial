# Auditoria de arquitetura — EpicFight1710-Port RC2

Data: 2026-09-21. Baseline protegido: `v2.2.0-RC2-STABLE`, commit
`ce1d9162003718bc8bdf0f1d94e82e32de1293f8`. Trabalho em
`feature/joint-local-collider`. Esta auditoria não muda o runtime nem reclassifica
a aceitação do baseline. O `TEST-REPORT.txt` histórico permanece intacto.

## Resultado e limites

A estrutura contém 68 fontes de produção, oito testes Java anteriores e 242 clips.
O runtime separa dados, reprodução, composição, compatibilidade DBC e skinning,
mas não implementa integralmente o combate do Epic Fight. O collider RC2 continua
orientado pela visão do jogador; ainda não acompanha um joint.

**Decisão: não ativar joint-local collider nesta entrega.** A arquitetura atual
não oferece uma amostra de combate temporalmente identificada, independente da
renderização, nem uma transformação completa e testável da armature para o mundo
DBC. Usar a última matriz renderizada introduziria dependência de FPS/câmera.
Chamar o Animator do resolver também produz efeitos colaterais. A seção de
bloqueios descreve a menor mudança proposta, conforme a instrução de não fazer
uma implementação improvisada quando houver impedimento arquitetural.

Foi feita inspeção estática dos subsistemas abaixo, auditoria binária de **todos os
242 clips e das duas malhas**, comparação de nomes/tracks/tempos com source fixado,
e execução de testes standalone. Não foram realizados nesta sessão: execução do
cliente Forge/DBC, medição de frametime, validação visual das 242 animações,
reconstrução numérica integral da conversão Blender/JSON para TRS, build Forge ou
verificação do JAR distribuído. Essas verificações não são substituídas pelos testes.

## Referência Epic Fight 20.9.5

Source consultado: commit upstream
[`a0a1027cdd210821c0b3bcc461f1ca170303d5fc`](https://github.com/Antikythera-Studios/epicfight/commit/a0a1027cdd210821c0b3bcc461f1ca170303d5fc),
cujo `gradle.properties` declara Minecraft 1.20.1 e `mod_version=20.9.5`.
Não foi encontrada uma tag `20.9.5`; existem vários commits com essa mensagem.
Este SHA é a referência explícita desta auditoria, sem presumir identidade binária
com um JAR publicado. O ZIP fonte foi consultado em `build/audit`, ignorado pelo Git.

Referências primárias (todas fixadas nesse SHA):

- [BasicAttack](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/skill/BasicAttack.java): execução no servidor, seleção da sequência, dash, mount e reset de combo.
- [AttackAnimation](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/api/animation/types/AttackAnimation.java): fases, estados, janela temporal, mãos, pares joint/collider, prioridade e limites de alvos.
- [BasicAttackAnimation](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/api/animation/types/BasicAttackAnimation.java): estados, máscara, movimentação e modificadores.
- [Collider](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/api/collider/Collider.java), [MultiCollider](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/api/collider/MultiCollider.java) e [OBBCollider](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/api/collider/OBBCollider.java): pose por tempo, bind do joint, matriz do modelo e seleção espacial.
- [Animations](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/gameasset/Animations.java) e [ColliderPreset](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/gameasset/ColliderPreset.java): definições concretas de golpes, joints e volumes.
- [ClientAnimator](https://github.com/Antikythera-Studios/epicfight/blob/a0a1027cdd210821c0b3bcc461f1ca170303d5fc/src/main/java/yesman/epicfight/api/client/animation/ClientAnimator.java): camadas por prioridade e composição com modificadores de bind.

## Fluxo atual e responsabilidades

```text
Forge/FML -> ClientHooks -> CombatController -> LocalPlayerPatch1710
                                  |              -> WeaponCapabilityRegistry
                                  -> CombatGuardRuntime / AttackCadenceTracker
                                  -> CombatHitResolver -> Compat.vanillaAttack

RenderHand / RenderPlayer / JBRA callsites
    -> PoseEngine -> LayeredAnimator -> AnimationLayer -> AnimationPlayer -> Clip
    -> global / skin matrices
    -> NativeJbraSkinContext -> JbraWeightedPartRenderer -> native geometry
    -> FirstPersonBodyRenderer1710 / FirstPersonWeaponRenderer
```

O tick de combate e o avanço do Animator são dois percursos distintos.
`LayeredAnimator.updateInternal` avança ticks pendentes somente quando recebe
`update`/`updateDbcJbra`. Esses métodos hoje são chamados nos percursos de render.

## Combate, estados e dano

| Área | Evidência no port | Classificação / consequência |
|---|---|---|
| BasicAttack | `CombatController.attackPressed`, `LocalPlayerPatch1710` | Há gates de battle/guard/dash, escolha por capability/style, sprint e mount. É adaptação cliente, não o pipeline server-side upstream. |
| Combo | `commit`, `update`, `stateAt` | Reset por tipo/style/geração e após mais de 16 ticks; janela de encadeamento explícita. Não há event bus equivalente a SkillConsume/BasicAttack/ComboCounterHandle. |
| Fonte dos perfis | `selectCombo` / `FistMoveset` | Punhos usam perfis hardcoded, enquanto armas usam JSON. Alterar o JSON de punhos não redefine automaticamente o combo selecionado. |
| StateSpectrum | `AttackStateSpectrum` | Cinco booleanos e limites escalares. `CAN_BASIC_ATTACK` é consumido; não há consumidor de movement/turning lock no controle de movimento. Não equivale ao mapa tipado/por-fase upstream. |
| Fases/hit windows | `AttackPhase.activeBetween` | Interseção de intervalos evita perder janelas curtas; teste confirma 8/8 do relentless a 2x. Ausentes mão/joint/volume por fase, phase-level, propriedades de impacto/stun. |
| Recovery | `chainOpen/chainClose` e fim de `Clip.duration` | Chaining e término são separados. Não confundir fim visual, recovery, inaction e phase-end como se fossem um único tempo. |
| Relógio | `attackElapsed` no controller; outro cursor no `AnimationPlayer` | Ambos usam incremento .05 × speed, mas não são o mesmo estado. Sincronização inicial não prova igualdade durante restart, catch-up, blend ou troca de ação. |
| Attack speed | `AttackCadenceTracker` | Janela de CPS, limiar 6.8, ratio arredondado e teto 2x. Upstream usa atributo de attack speed / basis speed / factor; CPS é extensão do port. Armas ficam em 1x no controller. |
| Guard | `CombatGuardRuntime` | JRMCore controla blocking; atraso visual de saída de dois ticks. Reação detecta subida de hurtTime; não prova que houve um bloqueio bem-sucedido. A histerese também participa do gate de ataque. |
| Hit stun / knockback | `Compat.vanillaAttack` | Dano delegado a PlayerControllerMP/DBC. Não há implementação geral de stun/impact/knockback por fase como upstream; clips de hit disponíveis não significam mecânica implementada. |
| Multi-target | loop sobre `loadedEntityList` | Pode despachar ataque em vários alvos. Não replica max strikes, hit priority, teammates ou normalização de entidades multipart upstream. |
| Dedupe | `IdentityHashMap<Object,Integer>` em `CombatHitResolver` | Alvo × janela, limpo ao trocar/limpar ação; limitado a 31 fases. Marca antes de despachar: retorno false não permite retry na mesma janela. |
| Confirmação de dano | `Compat.vanillaAttack` retorna true após `Method.invoke` | Mede despacho, não dano confirmado pelo servidor. Não deve alimentar estatísticas de dano real. |
| Lifecycle | `tickClientInternal` quando player==null | Limpa guard/patch/dash, mas não chama `clearAction`; revisar risco de ação e referências a targets sobreviverem à saída do mundo. Não corrigido aqui. |

`@Mod(clientSideOnly=true)` e `acceptableRemoteVersions="*"` confirmam o escopo
cliente. Servidor, sincronização de pose de outros jogadores e autoridade de dano
Epic são trabalho separado; não devem ser simulados por packets inventados.

## Collider atual e bloqueios do joint-local

### O que funciona e o que é aproximação

`CombatHitResolver` aplica centro e half-extents de `ColliderDefinition`, usa
count para várias amostras, faz filtros de alcance/facing/visibilidade e conserva
o dedupe por janela. Isso é mais espacial que o antigo seletor de cone.

Entretanto, as amostras interpolam posição/visão do jogador; não interpolam a
trajetória animada do braço. `intersectsObbAabb` testa somente as três direções da
OBB, uma aproximação conservadora, não SAT completo. Reconstrói a caixa do alvo a
partir de width/height em vez de usar a AABB real. O broad phase varre a lista inteira
e não deriva seus limites do volume transformado. O filtro de facing pode excluir
um alvo tocado por uma futura trajetória lateral. Essas mudanças devem ser revisadas
separadamente da conexão ao joint.

Upstream recorta o intervalo da pose à janela ativa, resolve o joint da fase,
amostra a animação nesse intervalo, aplica bind e model/world transform, depois
consulta entidades. MultiCollider ajusta o número de amostras com attack speed;
não é uma colisão contínua exata. O port atualmente varre partial 0..1 do tick
inteiro mesmo quando a janela ocupa só parte dele.

### B1 — Vida útil da pose (bloqueante)

`PoseEngine.globalJointMatrix` copia o último buffer composto sem validar player,
tick, ação, tempo ou perfil DBC. Antes do primeiro render pode haver matriz ainda
não composta; em FPS baixo pode haver amostra antiga. `skinMatrices` inclui inverse
bind e também não é a matriz correta para localizar a origem de um joint.

Chamar `updateDbcJbra` em `CombatHitResolver` não é uma solução isolada:

- `tickOnce` consome `pollGuardReaction`, avança locomotion e inicia layers;
- `AnimationLayer.enterWeight` pode mudar `freshEntry` durante amostragem;
- `compose` consulta câmera, estado DBC e aplica sanitização/modificadores;
- a comparação de ação usa nome (`observedAction`), sem serial de execução;
- os caches generic/DBC têm chaves separadas, mas compartilham um só Animator.
  Reusar uma chave antiga depois do outro caminho compor pode devolver pose de
  outro perfil. Deve ser coberto antes de adicionar um terceiro consumidor.

Uma avaliação por target seria ainda pior: repetiria esses efeitos e multiplicaria
o custo pela quantidade de entidades.

### B2 — Armature-space não é world-space DBC (bloqueante)

`NativeJbraSkinContext` deixa JBRA possuir transformações globais.
`JbraWeightedPartRenderer.drawCompiled` aplica correções de sockets aos membros;
`rightHandSocketDelta` é produzido no render e consumido no mount visual.
Escala/formas, pivots, offset do modelo e orientação global de voo não estão
representados integralmente em `PoseEngine.globalJointMatrix`.

Somar `player.pos` e aplicar yaw a essa matriz pode acompanhar um esqueleto abstrato,
mas não demonstra acompanhar a mão/arma desenhada em todas as formas DBC.
Também não se pode ler uma matriz OpenGL no tick ou reusar offset do frame anterior.

### B3 — Semântica de joint por fase (bloqueante para paridade)

O schema local não contém joint nem mão por fase. A referência concreta upstream:

| Golpe | Joint / collider upstream |
|---|---|
| `fist_auto1` | `Tool_L`, OFF_HAND, collider da capability |
| `fist_auto2` | `Tool_R`, MAIN_HAND, collider da capability |
| `fist_auto3` | `Tool_L`, OFF_HAND, collider da capability |
| `relentless_combo` | `Root`, `FIST_FIXED`, oito fases alternando mãos |

`Tool_*` é filho de `Hand_*`. Não são nomes intercambiáveis sem verificar offsets.
A diretriz de usar Hand_R/Arm_R para socos exige preservar o lado real da animação
e documentar a adaptação em relação à origem upstream. Não aplicar uma mão direita
fixa nem transportar o volume FIST_FIXED de Root para Hand sem converter seu espaço.

### Menor alteração arquitetural proposta

1. Introduzir uma identidade de execução de ação e um snapshot de avaliação
   `(player/world, actionSerial, tick, prevAge, age, DBC/profile revision)`.
2. Separar avanço/consumo de eventos da avaliação de pose. Reutilizar a mesma
   composição e matrizes do Animator, mas permitir amostra sem mutar clocks,
   layers, filas de guard ou cache visual. Um buffer reutilizável por amostra;
   todos os joints calculados uma vez, fora de loops de colliders/targets.
3. Expor um contrato matemático de `modelToWorld` e sockets DBC sem depender de GL
   ou do último draw. Validar equivalência visual das transformações extraídas;
   não alterar mount, armature, retarget nem flight gameplay como efeito colateral.
4. Representar binding espacial explícito por fase, separado de Tool_R visual.
   Preservar timings, half-extents, dedupe e despacho de dano. Aplicar a conversão
   de espaço necessária para dados existentes, sem deslocamentos arbitrários.
5. Só então conectar o resolver. Reutilizar amostras por tempo/poseSerial; tratar
   falta de snapshot explicitamente, sem alegar joint-local enquanto usa view cone.

Esse pré-requisito toca a fronteira Animator/DBC e necessita de golden tests da pose
final e comparação no cliente antes de habilitar o novo caminho. Não foi implementado.

## Animações, camadas e transições

`Clip` faz busca binária de keyframes e SLERP de quaternions; `AnimationPlayer`
desembrulha o ciclo no loop. `AnimationLayer` conserva snapshot da transição,
interpolação de fades e o intervalo final antes de desligar. `AnimationPose` usa
buffers reutilizados. São bases úteis que devem ser preservadas.

`LayeredAnimator` compõe numa ordem fixa base/composite/reaction/action. O enum de
priority não implementa a seleção dinâmica de layers do `ClientAnimator` original.
`StaticAnimation`, `ActionAnimation`, `AttackAnimation` e `BasicAttackAnimation`
não existem como classes equivalentes: metadados e comportamento foram repartidos
entre catálogo, controller e composição. Eventos, coord/root motion, link animation,
propriedades tipadas e bind modifiers não têm cobertura integral.

| Família / estado | Situação observada |
|---|---|
| Idle/walk/run/sprint | Clips reais, preservação de fase WALK/RUN, velocidade suavizada; RUN depende de sprint. Não existe uma nova animação sprint independente. |
| Jump/fall | Ambos presentes; DBC escolhe JUMP quando está no ar sem voo, suprimindo FALL deliberadamente. É workaround protegido, não ausência de asset. |
| Landing | Clip existe, mas `applySafeLandingPose` sobrescreve rotações de pernas/torso com uma curva procedural e máscara suavizada. Divergência explícita, não corrigida. |
| Sneak | Clip original; preservação de translation dos helpers Knee/Elbow na sanitização DBC. |
| Fist/relentless | Clips presentes; máscara BASIC_ATTACK para punhos e full para relentless. CPS gate e escolha de innate são adaptações. |
| Guard | Camada superior sobre locomotion. Punho reutiliza `guard_dualsword`; não é guard unarmed original. |
| Weapon idle/walk/run | Capability/style resolve clips; composites superiores preservam fase. Fallback por família não garante semântica upstream de todos os clips. |
| Ki | DBC fornece getAnimKiShoot; port escolhe crossbow_aim down/mid/up. Dados reais reutilizados, sem equivalência a um skill Ki upstream. |
| Creative flight | Clips idle/forward/backward reais e política direcional. DBC fornece estado; Root sanitizado e whole-body flight pertence ao JBRA. |
| Transições | Conversão/fade suave já presente; snapshot, priority fixa e clocks distintos exigem cenários de interrupt/restart para comprovar continuidade. |

`sanitizeDbcLayerPose` remove translation/scale locais, preserva exceções de helpers
e trata Root yaw. Logo, mesmo com dados originais, a pose final é adaptada.
`EpicSourceRegistry` torna todos os clips endereçáveis por classificação de nome;
**242 registros não significam 242 ações executáveis ou 242 comportamentos portados**.

## Armature e espaços

Malhas: modern=260 vértices; legacy=138. Ambas têm os mesmos nomes, parents,
bindLocal e inverse-bind, verificados numericamente. Parents precedem filhos,
matrizes são finitas, globalBind × inverseBind é identidade com tolerância 1e-4,
pesos somam 1 e índices de malha são válidos.

```text
Root
  Thigh_R -> Leg_R / Knee_R
  Thigh_L -> Leg_L / Knee_L
  Torso -> Chest
    Head
    Shoulder_R -> Arm_R -> Hand_R -> Tool_R
                          Elbow_R (filho de Arm_R)
    Shoulder_L -> Arm_L -> Hand_L -> Tool_L
                          Elbow_L (filho de Arm_L)
```

São 20 joints; Knee/Elbow são helpers irmãos do segmento seguinte, não uma cadeia
humanoide genérica que possa ser reconstruída pela ordem dos nomes.
`Mat4` é row-major com vetores-coluna: global=parentGlobal × bindLocal × deltaTRS;
skin=global × inverseBind. Matrizes de skin não localizam diretamente o joint.
`DbcRetargetMath`/`DbcRetargetProfile` adaptam sockets e anatomia. Reaplicar rotação
nativa DBC nos vértices já deformados duplicaria a transformação.

## DBC, JRMCore, JBRA e sistemas protegidos

`DbcClientState` usa snapshots por tick, reflexão cacheada e revisão visual para
evidências tardias do draw. `DbcFlightStateMachine` usa sinais explícitos, debounce
e grace periods; tempo no ar não liga voo. `DbcFlightRenderHook` separa apresentação
prone/fast flight. `DashController` conserva movimento de 4.5 blocos em fatias com
colisão e integração de efeito DBC; não equivale a BasicAttack dash.

`NativeJbraSkinContext` e `ModelRendererSkinHook` substituem draws específicos,
mantendo textura, geometria, raça e layers nativos. Head/face/hair pertencem ao
JBRA; herança da deformação do torso não transfere a propriedade da geometria.
Mapeamentos por reflexão e callsites ASM dependem de versões/layouts exatos; são
os pontos mais sensíveis a outro coremod e mudança de transformação/raça.

WORLD-BODY permanece protegido: `FirstPersonCombatAnimator` delega ao fast path,
`FirstPersonBodyRenderer1710` mantém offset/framing e exclusão de cabeça no POV.
Não foi adicionado renderEntityStatic no RenderHandEvent, renderer de braços
genérico ou configuração XYZ. O mount em `FirstPersonWeaponRenderer` preserva
`Tool_R × RenderItemBase(Tz=-0.13, Rx=-90°)` e a adaptação de display 1.7.10.
Collider espacial não deve usar a correção de display do item como se fosse dado
de alcance ou volume. Todos esses arquivos permanecem byte-identical ao baseline.

## Input e dados

Mouse attack usa Forge MouseEvent; teclado usa edge no tick. Isso evita comprimir
cliques rápidos a 20 TPS. Battle/reload ficam em ClientHooks; dash no controller;
guard vem de DBC. Não há um snapshot central de input. Unificar polling requer
preservar ordem de eventos, GUI, cancelamento vanilla e binds em mouse/teclado.

WeaponCapability1710/WeaponStyleDefinition/StyleCondition separam tipo, estilo,
combo e living motions. Há oito tipos e sete regras baseadas em hierarquia/nome de
classe; é aproximação frente a capabilities por item/NBT/tags no upstream.
Hot reload valida clips e troca registro/geração, porém parse inválido do arquivo
externo pode instalar defaults embedded: o comentário de manter registro anterior
em ClientHooks não descreve todos os casos. Não mudar esse contrato nesta fase.

## CPU e fragilidade

Sem captura de profiler no Minecraft, não há ranking medido de CPU. A tabela é uma
priorização por loops e frequência, não uma promessa de FPS.

| Candidato | Custo/evidência | Próxima medição |
|---|---|---|
| JBRA weighted skinning | Por vértice/peso e por pass; topologia compilada, vértices deduplicados, normals e skin cacheados por frameSerial. Ainda não é cache global por poseSerial. | vértices únicos, passes, cache hits/misses e p95/p99 por forma |
| Collider | O(entidades × fases × count); transform/view refeitos por target/sample e reflexão de posição/tamanho | candidatos, samples preparados, OBB tests, tempo vs densidade |
| Animator/PoseEngine | Várias layers × 20 joints, busca por key e composição por amostra; cache suprime duplicatas, mas revisões DBC podem invalidar | composições por tick/frame/perfil e cache misses |
| Descoberta JBRA | Cold path compila/subdivide cubos e procura campos; auditorias de topologia escalonadas | spike na primeira forma/transformação e memória de caches |
| Reflexão | Fields/Methods cacheados; invocações permanecem em Compat/DBC. MethodHandle no fallback evita boxing por chamada | custo de probes e falhas por versão |
| First person | GL state queries, body pass e item mount; evita pipeline JBRA inteiro | diferença OFF/ON no mesmo POV e modpack |
| Matrizes | `Mat4.mul` aloca temporário quando output aliasa input | contagem de alias nos hot paths antes de qualquer alteração |

Nos loops de skin inspecionados as matrizes são obtidas antes dos vértices, sem
recalcular pose por vértice. Descoberta reflexiva de geometria pertence ao cold path;
invalidar cache repetidamente pode torná-la quente. `SkinnedPlayerRenderer` já usa
poseSerial. Não reduzir densidade de mesh, frequência de animação ou qualidade.

`RuntimeProfiler` já existe e é opt-in com `-Depicfight1710.profiler=true`.
Mede médias/call em seis slots; COMBAT inclui outros custos medidos, então somar
slots não dá custo total. Não mede percentis, GC, tempo de GPU ou frame completo.
Não foi criado um segundo profiler nem alterado logging.

Fragilidades prioritárias: pose/render acoplados; espaço JBRA fora da fachada de
pose; ausência de metadata espacial por fase; estado cliente vs servidor;
retarget/callsites dependentes de versão; lifecycle de caches/ação entre mundos.

## Testes e reprodução

Executados nesta auditoria com Java 8 e compilador Eclipse ECJ 4.6.1 (sem stubs):

- `CombatTiming200Test`: PASS, relentless 8/8 por intervalo, controle pontual 4/8.
- `AttackCadence220Test`: PASS, 6.7/8/10/13.6/20 CPS -> 1/1.176/1.471/2/2.
- `RuntimeProfiler220Test`: PASS em processos separados, disabled e enabled.
- `AnimationAudit220Test`: PASS, registro dos 242 clips, 968 poses finitas,
  interpolação no wrap, máscara inferior preservada e 480 matrizes golden.
- `audit_assets.py`: PASS, assets/manifest/armature e comparação estrutural upstream.
- Controle negativo do golden: PASS; uma cópia isolada em `build/audit/negative`
  com Root[0] deslocado em 0.1 foi rejeitada por AssertionError. Fixture original
  e recursos do projeto não foram alterados nesse ensaio.

Golden fixture captura oito clips × três tempos (0/.1/.2) × 20 joints do código
baseline inalterado. Usa tolerância absoluta 2e-5. É **pose raw em armature-space**;
não cobre ainda composição DBC, sockets, modelToWorld, flight ou WORLD-BODY final.
Não regenerar fixture automaticamente para fazer um teste passar.

Não executados aqui: `CombatCollider220Test`, `WeaponRegistry200Test`,
`StyleMount200Test`, `EmbeddedLoad200Test`, `HotReload200Test`, build Forge,
`verify_release.sh`, startup/world/DBC visual. O classpath de build Forge/SRG não
está estabelecido; o runner standalone não finge cobrir classes excluídas.
O compilador acusou somente import não utilizado no teste de timing existente.

SHA-256 do compilador ECJ usado:
`9cddda75f4a1b4469e73f44e7b61a3e897d0f657df4797f9106ffe88c4eeade0`.
Os downloads e resultados intermediários ficam em `build/audit`, fora do commit.

```powershell
# Da raiz. ECJ 4.6.1: org.eclipse.jdt.core.compiler:ecj:4.6.1 (Maven Central).
# Forneça Java 8, Python 3 e o JAR do compilador; não há download automático.
./tools/tests/run_audit.ps1 -CompilerJar <ecj-4.6.1.jar> -Java <java.exe> -Python <python.exe>
# Comparação opcional contra ZIP do SHA upstream fixado:
python tools/tests/audit_assets.py --upstream <epicfight-source.zip>
```

## Inventário completo dos clips

242/242 caminhos existem no ZIP upstream. As 20 tracks e seus tempos correspondem
em todos os clips. Famílias por diretório de origem: combat=119, skill=61,
living=61, interact=1. `MATCH` abaixo significa tracks/tempos, **não** paridade de
transformações convertidas, roteamento de gameplay ou resultado visual.

| Clip | Duration | Tracks | Source track/time comparison |
|---|---:|---:|---|
| axe_airslash | 1.0000 | 20 | MATCH |
| axe_auto1 | 1.0000 | 20 | MATCH |
| axe_auto2 | 1.0000 | 20 | MATCH |
| axe_dash | 0.8667 | 20 | MATCH |
| battojutsu | 1.5000 | 20 | MATCH |
| battojutsu_dash | 1.5000 | 20 | MATCH |
| blade_rush_combo1 | 1.0000 | 20 | MATCH |
| blade_rush_combo2 | 1.0000 | 20 | MATCH |
| blade_rush_combo3 | 1.0000 | 20 | MATCH |
| blade_rush_execute | 1.5167 | 20 | MATCH |
| blade_rush_failed | 1.0500 | 20 | MATCH |
| blade_rush_hit | 1.4000 | 20 | MATCH |
| blade_rush_try | 0.3600 | 20 | MATCH |
| bow_aim_down | 0.4333 | 20 | MATCH |
| bow_aim_lying | 0.4333 | 20 | MATCH |
| bow_aim_mid | 0.4333 | 20 | MATCH |
| bow_aim_up | 0.4333 | 20 | MATCH |
| bow_shot_down | 0.0667 | 20 | MATCH |
| bow_shot_lying | 0.0667 | 20 | MATCH |
| bow_shot_mid | 0.0667 | 20 | MATCH |
| bow_shot_up | 0.0667 | 20 | MATCH |
| climb | 0.8000 | 20 | MATCH |
| creative_fly_backward | 4.0000 | 20 | MATCH |
| creative_fly_forward | 4.0000 | 20 | MATCH |
| creative_idle | 2.6667 | 20 | MATCH |
| crossbow_aim_down | 0.4333 | 20 | MATCH |
| crossbow_aim_lying | 0.4333 | 20 | MATCH |
| crossbow_aim_mid | 0.4333 | 20 | MATCH |
| crossbow_aim_up | 0.4333 | 20 | MATCH |
| crossbow_reload | 1.1667 | 20 | MATCH |
| crossbow_shot_down | 0.3333 | 20 | MATCH |
| crossbow_shot_lying | 0.3333 | 20 | MATCH |
| crossbow_shot_mid | 0.3333 | 20 | MATCH |
| crossbow_shot_up | 0.3333 | 20 | MATCH |
| dagger_airslash | 0.5667 | 20 | MATCH |
| dagger_auto1 | 0.6500 | 20 | MATCH |
| dagger_auto2 | 0.6500 | 20 | MATCH |
| dagger_auto3 | 1.1000 | 20 | MATCH |
| dagger_dash | 1.1000 | 20 | MATCH |
| dagger_dual_auto1 | 1.0500 | 20 | MATCH |
| dagger_dual_auto2 | 1.0000 | 20 | MATCH |
| dagger_dual_auto3 | 0.9833 | 20 | MATCH |
| dagger_dual_auto4 | 1.1500 | 20 | MATCH |
| dagger_dual_dash | 0.9000 | 20 | MATCH |
| dancing_edge | 1.2500 | 20 | MATCH |
| death | 1.3333 | 20 | MATCH |
| demolition_leap | 1.0000 | 20 | MATCH |
| demolition_leap_charge | 0.5000 | 20 | MATCH |
| dig_mainhand | 0.2500 | 20 | MATCH |
| dig_offhand | 0.2500 | 20 | MATCH |
| drink_mainhand | 1.6000 | 20 | MATCH |
| drink_offhand | 1.6000 | 20 | MATCH |
| eat_mainhand | 1.2667 | 20 | MATCH |
| eat_offhand | 1.2667 | 20 | MATCH |
| everlasting_allegiance_call | 0.5500 | 20 | MATCH |
| everlasting_allegiance_catch | 0.9333 | 20 | MATCH |
| eviscerate_first | 0.4500 | 20 | MATCH |
| eviscerate_second | 0.5000 | 20 | MATCH |
| fall | 8.3333 | 20 | MATCH |
| fist_airslash | 0.4000 | 20 | MATCH |
| fist_auto1 | 0.5000 | 20 | MATCH |
| fist_auto2 | 0.5000 | 20 | MATCH |
| fist_auto3 | 0.5000 | 20 | MATCH |
| fist_dash | 0.9167 | 20 | MATCH |
| float | 2.3333 | 20 | MATCH |
| fly | 0.1667 | 20 | MATCH |
| fly_backward | 4.0000 | 20 | MATCH |
| fly_forward | 4.0000 | 20 | MATCH |
| grasping_spire_second | 1.3500 | 20 | MATCH |
| greatsword_airslash | 1.0000 | 20 | MATCH |
| greatsword_auto1 | 1.2500 | 20 | MATCH |
| greatsword_auto2 | 1.7167 | 20 | MATCH |
| greatsword_dash | 1.6500 | 20 | MATCH |
| greatsword_slam | 0.5833 | 20 | MATCH |
| guard_break1 | 2.0000 | 20 | MATCH |
| guard_break2 | 2.0000 | 20 | MATCH |
| guard_dualsword | 2.6667 | 20 | MATCH |
| guard_dualsword_hit | 0.5000 | 20 | MATCH |
| guard_greatsword | 2.6667 | 20 | MATCH |
| guard_greatsword_hit | 0.5000 | 20 | MATCH |
| guard_longsword | 2.6667 | 20 | MATCH |
| guard_longsword_hit | 0.5000 | 20 | MATCH |
| guard_longsword_hit_active1 | 0.6667 | 20 | MATCH |
| guard_longsword_hit_active2 | 0.6667 | 20 | MATCH |
| guard_spear | 2.6667 | 20 | MATCH |
| guard_spear_hit | 0.5000 | 20 | MATCH |
| guard_sword | 2.6667 | 20 | MATCH |
| guard_sword_hit | 0.5000 | 20 | MATCH |
| guard_sword_hit_active1 | 0.6667 | 20 | MATCH |
| guard_sword_hit_active2 | 0.6667 | 20 | MATCH |
| guard_sword_hit_active3 | 0.6667 | 20 | MATCH |
| guard_uchigatana | 2.6667 | 20 | MATCH |
| guard_uchigatana_hit | 0.5000 | 20 | MATCH |
| heartpiercer | 1.0833 | 20 | MATCH |
| hit_long | 0.7500 | 20 | MATCH |
| hit_on_mount | 0.6000 | 20 | MATCH |
| hit_shield_mainhand | 0.0500 | 20 | MATCH |
| hit_shield_offhand | 0.0500 | 20 | MATCH |
| hit_short | 0.0500 | 20 | MATCH |
| hold_crossbow | 2.6667 | 20 | MATCH |
| hold_dual | 2.6667 | 20 | MATCH |
| hold_greatsword | 2.6667 | 20 | MATCH |
| hold_liechtenauer | 2.6667 | 20 | MATCH |
| hold_longsword | 2.6667 | 20 | MATCH |
| hold_map_mainhand | 3.3333 | 20 | MATCH |
| hold_map_mainhand_move | 3.3333 | 20 | MATCH |
| hold_map_offhand | 3.3333 | 20 | MATCH |
| hold_map_offhand_move | 3.3333 | 20 | MATCH |
| hold_map_twohand | 3.3333 | 20 | MATCH |
| hold_map_twohand_move | 3.3333 | 20 | MATCH |
| hold_spear | 2.6667 | 20 | MATCH |
| hold_tachi | 2.6667 | 20 | MATCH |
| hold_uchigatana | 2.6667 | 20 | MATCH |
| hold_uchigatana_sheath | 2.6667 | 20 | MATCH |
| idle | 2.6667 | 20 | MATCH |
| javelin_aim_down | 0.2833 | 20 | MATCH |
| javelin_aim_lying | 0.2833 | 20 | MATCH |
| javelin_aim_mid | 0.2833 | 20 | MATCH |
| javelin_aim_up | 0.2833 | 20 | MATCH |
| javelin_throw_down | 0.5833 | 20 | MATCH |
| javelin_throw_lying | 0.5833 | 20 | MATCH |
| javelin_throw_mid | 0.5833 | 20 | MATCH |
| javelin_throw_up | 0.5833 | 20 | MATCH |
| jump | 0.3167 | 20 | MATCH |
| kneel | 2.6667 | 20 | MATCH |
| knockdown | 2.3333 | 20 | MATCH |
| knockdown_wakeup_left | 0.6000 | 20 | MATCH |
| knockdown_wakeup_right | 0.6000 | 20 | MATCH |
| landing | 0.8333 | 20 | MATCH |
| liechtenauer_ready | 0.3000 | 20 | MATCH |
| longsword_airslash | 0.7000 | 20 | MATCH |
| longsword_auto1 | 1.3500 | 20 | MATCH |
| longsword_auto2 | 1.3500 | 20 | MATCH |
| longsword_auto3 | 1.2500 | 20 | MATCH |
| longsword_dash | 1.3500 | 20 | MATCH |
| longsword_liechtenauer_auto1 | 1.2500 | 20 | MATCH |
| longsword_liechtenauer_auto2 | 1.2000 | 20 | MATCH |
| longsword_liechtenauer_auto3 | 1.0833 | 20 | MATCH |
| mob_dagger_onehand1 | 0.4167 | 20 | MATCH |
| mob_dagger_onehand2 | 0.4500 | 20 | MATCH |
| mob_dagger_onehand3 | 0.9167 | 20 | MATCH |
| mob_dagger_twohand1 | 1.0000 | 20 | MATCH |
| mob_dagger_twohand2 | 1.3333 | 20 | MATCH |
| mob_greatsword1 | 2.2000 | 20 | MATCH |
| mob_longsword1 | 1.5500 | 20 | MATCH |
| mob_longsword2 | 1.0000 | 20 | MATCH |
| mob_onehand1 | 0.9500 | 20 | MATCH |
| mob_onehand2 | 0.9500 | 20 | MATCH |
| mob_spear_onehand | 1.1000 | 20 | MATCH |
| mob_spear_twohand1 | 1.0000 | 20 | MATCH |
| mob_spear_twohand2 | 1.0000 | 20 | MATCH |
| mob_spear_twohand3 | 1.0000 | 20 | MATCH |
| mob_sword_dual1 | 1.1667 | 20 | MATCH |
| mob_sword_dual2 | 1.1667 | 20 | MATCH |
| mob_sword_dual3 | 1.4000 | 20 | MATCH |
| mob_tachi_special | 1.0000 | 20 | MATCH |
| mob_throw | 1.0000 | 20 | MATCH |
| mob_uchigatana1 | 0.7000 | 20 | MATCH |
| mob_uchigatana2 | 0.5667 | 20 | MATCH |
| mob_uchigatana3 | 0.7000 | 20 | MATCH |
| mount | 2.0000 | 20 | MATCH |
| phantom_ascent_backward | 0.7000 | 20 | MATCH |
| phantom_ascent_forward | 0.7000 | 20 | MATCH |
| relentless_combo | 1.1667 | 20 | MATCH |
| revelation_normal | 0.6667 | 20 | MATCH |
| revelation_twohand | 0.6667 | 20 | MATCH |
| roll_backward | 0.6333 | 20 | MATCH |
| roll_forward | 0.6333 | 20 | MATCH |
| run | 0.5333 | 20 | MATCH |
| run_dual | 0.5333 | 20 | MATCH |
| run_greatsword | 0.5333 | 20 | MATCH |
| run_longsword | 0.5333 | 20 | MATCH |
| run_spear | 0.5333 | 20 | MATCH |
| run_uchigatana | 0.4667 | 20 | MATCH |
| run_uchigatana_sheath | 0.5333 | 20 | MATCH |
| rushing_tempo1 | 1.2833 | 20 | MATCH |
| rushing_tempo2 | 1.2833 | 20 | MATCH |
| rushing_tempo3 | 1.4500 | 20 | MATCH |
| sharp_stab | 1.2000 | 20 | MATCH |
| shield_mainhand | 8.3333 | 20 | MATCH |
| shield_offhand | 8.3333 | 20 | MATCH |
| sit | 3.3333 | 20 | MATCH |
| sleep | 2.6667 | 20 | MATCH |
| sneak | 0.7667 | 20 | MATCH |
| spear_dash | 1.2500 | 20 | MATCH |
| spear_mount_attack | 0.8333 | 20 | MATCH |
| spear_onehand_airslash | 0.6000 | 20 | MATCH |
| spear_onehand_auto | 1.1667 | 20 | MATCH |
| spear_twohand_airslash | 0.7000 | 20 | MATCH |
| spear_twohand_auto1 | 1.0667 | 20 | MATCH |
| spear_twohand_auto2 | 1.0500 | 20 | MATCH |
| spyglass_mainhand | 3.3333 | 20 | MATCH |
| spyglass_offhand | 3.3333 | 20 | MATCH |
| steel_whirlwind | 2.5333 | 20 | MATCH |
| steel_whirlwind_charging | 0.6667 | 20 | MATCH |
| step_backward | 0.3500 | 20 | MATCH |
| step_forward | 0.3500 | 20 | MATCH |
| step_left | 0.3500 | 20 | MATCH |
| step_right | 0.3500 | 20 | MATCH |
| sweeping_edge | 1.0000 | 20 | MATCH |
| swim | 1.0000 | 20 | MATCH |
| sword_airslash | 0.6167 | 20 | MATCH |
| sword_auto1 | 0.6000 | 20 | MATCH |
| sword_auto2 | 0.6000 | 20 | MATCH |
| sword_auto3 | 0.6000 | 20 | MATCH |
| sword_auto4 | 0.6250 | 20 | MATCH |
| sword_dash | 0.6000 | 20 | MATCH |
| sword_dual_airslash | 0.6167 | 20 | MATCH |
| sword_dual_auto1 | 0.6000 | 20 | MATCH |
| sword_dual_auto2 | 0.6000 | 20 | MATCH |
| sword_dual_auto3 | 0.7500 | 20 | MATCH |
| sword_dual_dash | 0.7500 | 20 | MATCH |
| sword_mount_attack | 0.5667 | 20 | MATCH |
| tachi_auto1 | 1.2333 | 20 | MATCH |
| tachi_auto2 | 1.2333 | 20 | MATCH |
| tachi_auto3 | 1.1833 | 20 | MATCH |
| tachi_dash | 1.4167 | 20 | MATCH |
| the_guillotine | 1.1000 | 20 | MATCH |
| tool_dash | 0.5833 | 20 | MATCH |
| trident_auto1 | 0.9000 | 20 | MATCH |
| trident_auto2 | 0.9000 | 20 | MATCH |
| trident_auto3 | 0.9000 | 20 | MATCH |
| tsunami | 1.5000 | 20 | MATCH |
| tsunami_reinforced | 1.3833 | 20 | MATCH |
| uchigatana_airslash | 0.5000 | 20 | MATCH |
| uchigatana_auto1 | 0.8500 | 20 | MATCH |
| uchigatana_auto2 | 0.8500 | 20 | MATCH |
| uchigatana_auto3 | 0.8500 | 20 | MATCH |
| uchigatana_dash | 1.2167 | 20 | MATCH |
| uchigatana_scrap | 0.5000 | 20 | MATCH |
| uchigatana_sheath_airslash | 0.6167 | 20 | MATCH |
| uchigatana_sheath_auto | 0.8333 | 20 | MATCH |
| uchigatana_sheath_dash | 0.8500 | 20 | MATCH |
| walk | 0.7833 | 20 | MATCH |
| walk_greatsword | 0.7833 | 20 | MATCH |
| walk_liechtenauer | 0.7833 | 20 | MATCH |
| walk_longsword | 0.7333 | 20 | MATCH |
| walk_spear | 0.7833 | 20 | MATCH |
| walk_twohand | 0.6000 | 20 | MATCH |
| walk_uchigatana | 0.8000 | 20 | MATCH |
| walk_uchigatana_sheath | 0.7833 | 20 | MATCH |
| wrathful_lighting | 1.3333 | 20 | MATCH |
