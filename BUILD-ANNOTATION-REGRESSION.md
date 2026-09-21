# 2.1.2-RC1 Forge event annotation regression fix

The 2.1.0-RC1 runtime source was correct, but its distributed ClientHooks.class was produced by an ad-hoc compile stub whose SubscribeEvent annotation had CLASS retention. The resulting methods contained RuntimeInvisibleAnnotations, so Forge 1.7.10 EventBus registered the object but discovered zero handlers.

2.1.2-RC1 restores RuntimeVisibleAnnotations for all five ClientHooks @SubscribeEvent methods without changing their executable bytecode. The release verifier must reject any build where ClientHooks contains SubscribeEvent only under RuntimeInvisibleAnnotations.

This fix does not revert the 2.1 performance/consolidation work and does not alter first-person framing, combat semantics, flight, dash, animation assets, weapon data, or the protected transformer.
