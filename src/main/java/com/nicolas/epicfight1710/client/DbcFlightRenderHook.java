package com.nicolas.epicfight1710.client;

import java.lang.reflect.Field;
import org.lwjgl.opengl.GL11;

/**
 * DBC/JBRA flight presentation gate.
 *
 * Acceptance contract from the live reference video:
 * - DBC owns flight physics.
 * - Without the run/sprint key, Epic Fight creative-flight clips own the pose and the
 *   legacy JBRA +90 degree whole-player prone transform must NOT be applied.
 * - Holding the run/sprint key is the only condition that enables the legacy prone/
 *   plank presentation.
 *
 * ModelBipedBody y=2 carries a fixed -60 degree head compensation that only makes
 * sense together with JBRA's prone transform. Therefore non-sprint flight also
 * normalizes y back to 1 so head/hair/body cannot disagree.
 */
public final class DbcFlightRenderHook {
    private static boolean epicLogged,plankLogged,firstPersonFlightLogged,stateLookupDone;
    private static Field modelState;
    private DbcFlightRenderHook(){}

    public static void translate(float x,float y,float z,Object player) {
        boolean active=active(player);
        if(active)DbcClientState.INSTANCE.observeNativeProne(player);
        if(active&&!plankRequested(player)) { logEpic(); return; }
        if(active)logPlank();
        GL11.glTranslatef(x,y,z);
    }

    public static void rotate(float angle,float x,float y,float z,Object player) {
        boolean active=active(player);
        if(active)DbcClientState.INSTANCE.observeNativeProne(player);
        if(active&&!plankRequested(player)) { logEpic(); return; }
        if(active)logPlank();
        GL11.glRotatef(angle,x,y,z);
    }

    public static void afterFlightStateWrite(Object player) {
        if(!active(player))return;
        if(!plankRequested(player)) {
            normalizeBodyState();
            logEpic();
        } else logPlank();
    }

    static boolean plankRequested(Object player) {
        if(!active(player))return false;
        // The native JBRA sprint/prone transform rotates/translates the entire player.
        // That presentation is correct in third person, but WORLD-BODY first person is
        // rendered from inside the same body; applying the whole-player prone basis there
        // swings RL/LL into the camera. Keep Epic creative-flight choreography in POV and
        // reserve JBRA's global plank transform for third person only.
        if(FirstPersonBodyRenderer1710.fastContextActive()) {
            logFirstPersonFlight();
            return false;
        }
        return Compat.sprintKeyHeld(player);
    }

    private static boolean active(Object player){return CombatController.INSTANCE.battleMode()&&player==Compat.player();}

    private static void normalizeBodyState() {
        Field f=modelState;
        if(f==null&&!stateLookupDone) {
            stateLookupDone=true;
            try {
                f=Class.forName("JinRyuu.JBRA.ModelBipedDBC").getField("y");
                f.setAccessible(true);
                modelState=f;
            } catch(Throwable ignored) { modelState=null; }
        }
        if(f!=null)try{f.setInt(null,1);}catch(Throwable ignored){}
    }

    private static void logEpic(){if(!epicLogged){epicLogged=true;System.out.println("[EpicFight1710] Default DBC flight presentation: JBRA legacy +90deg prone transform is suppressed and y=2 is normalized; Epic creative_idle/forward/backward joint choreography remains authoritative while sprint is not held.");}}
    private static void logPlank(){if(!plankLogged){plankLogged=true;System.out.println("[EpicFight1710] Sprint-only plank presentation active in third person: holding the run key permits JBRA's paired prone translate/rotate + y=2 head compensation. Releasing sprint returns immediately to Epic creative flight.");}}
    private static void logFirstPersonFlight(){if(!firstPersonFlightLogged){firstPersonFlightLogged=true;System.out.println("[EpicFight1710] First-person fast-flight isolation active: JBRA whole-player prone transform is suppressed in WORLD-BODY POV so sprint flight cannot rotate the legs into the camera; Epic creative-flight pose remains authoritative.");}}
}
