package com.nicolas.epicfight1710.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderHandEvent;

public final class ClientHooks {
    public static final ClientHooks INSTANCE=new ClientHooks();
    private boolean battleWasDown;
    private boolean attackWasDown;
    private boolean reloadWasDown;
    private ClientHooks() {}

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if(!Compat.isEndTick(event))return;
        CombatController.INSTANCE.tickClient();
        boolean gui=Compat.guiOpen();

        boolean battle=ClientKeyBindings.battleModeDown();
        if(battle && !battleWasDown && !gui) CombatController.INSTANCE.toggleBattleMode();
        battleWasDown=battle;

        boolean reload=ClientKeyBindings.reloadWeaponsDown();
        if(!gui&&reload&&!reloadWasDown){
            try{com.nicolas.epicfight1710.combat.WeaponDefinitionLoader.load();System.out.println("[EpicFight1710] Weapon definitions hot-reloaded.");}
            catch(Throwable t){System.err.println("[EpicFight1710] Weapon definition reload failed; previous registry remains active: "+t);}
        }
        reloadWasDown=reload;

        // Mouse-bound Attack is handled from Forge MouseEvent so clicks faster than
        // 20 TPS are not collapsed into one KeyBinding edge. Keyboard attack keeps
        // the ordinary tick-edge path.
        boolean attack=ClientKeyBindings.attackComboDown();
        if(!ClientKeyBindings.attackComboIsMouse()) {
            if(CombatController.INSTANCE.battleMode() && !gui && attack && !attackWasDown)
                CombatController.INSTANCE.attackPressed();
            attackWasDown=attack;
        } else attackWasDown=false;

    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if(!CombatController.INSTANCE.battleMode() || Compat.guiOpen())return;
        // If Attack / Combo is bound to any mouse button, suppress that matching
        // vanilla mouse action only while Battle Mode is active. Outside Battle Mode
        // the original Minecraft hit/break/use input path is completely untouched.
        int code=ClientKeyBindings.attackComboCode();
        if(code<0) {
            int boundButton=code+100;
            if(boundButton>=0 && Compat.mouseButton(event)==boundButton) {
                if(Compat.mouseDown(event))CombatController.INSTANCE.attackPressed();
                event.setCanceled(true);
            }
        }
    }


    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if(!CombatController.INSTANCE.battleMode())return;
        float partial=Compat.handPartial(event);
        if(FirstPersonCombatAnimator.INSTANCE.render(partial)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if(!CombatController.INSTANCE.battleMode())return;
        Object p=Compat.eventPlayer(event), local=Compat.player();
        if(p==null || p!=local)return;

        Object renderer=Compat.eventRenderer(event);
        if(NativeJbraSkinContext.isJbraRenderer(renderer)) {
            NativeJbraSkinContext.INSTANCE.begin(p,renderer,Compat.eventPartial(event));
            return;
        }

        if(SkinnedPlayerRenderer.INSTANCE.render(p,renderer,Compat.eventPartial(event)))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        Object p=Compat.eventPlayer(event);
        if(NativeJbraSkinContext.INSTANCE.activeFor(p)) NativeJbraSkinContext.INSTANCE.end();
    }
}
