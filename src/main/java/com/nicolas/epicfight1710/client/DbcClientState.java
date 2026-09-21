package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.util.Reflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/**
 * Reflection-only bridge to the exact DBC/JRMCore client state we integrate with.
 *
 * Dragon Block C visual-flight authority is centralized here. JRMCore gameplay state
 * is primary; this player's live JBRA body state, native-prone call-site and vanilla
 * capability are bounded compatibility evidence for render-order gaps.
 */
final class DbcClientState {
    static final int FLIGHT_NONE=DbcFlightStateMachine.NONE, FLIGHT_HOVER=DbcFlightStateMachine.HOVER, FLIGHT_CRUISE=DbcFlightStateMachine.CRUISE;
    static final DbcClientState INSTANCE=new DbcClientState();

    private boolean jrmInit;
    private Method extendedGet;
    private Method getBlocking;
    private Method getAnimKiShoot;
    private Field modelBipedBodyState;
    private Field modelBipedDbcState;

    // Exact JRMCoreH state used by RenderPlayerJBRA's native flight branch.
    private Class<?> jrmCoreH;
    private Method jrmPlayerPower;
    private Method jrmStatusEffect7;
    private Field jrmPlayers;
    private Field jrmData3;
    private Class<?> nameOwner;
    private Method playerName;
    private boolean exactFlightLogged;
    private boolean directFlightLogged;
    private boolean jumpGateLogged;

    // Dragon Block C's own local FloatKi state. DBCKiTech.floating is the client
    // boolean that actually toggles when DBC flight is enabled; unlike data3/status7
    // it does not pulse false between movement/render updates.
    private Class<?> dbcKiTech;
    private Field dbcFloating;

    // Vanilla capability remains a fallback for installations where JRMCoreH cannot
    // be reflected, not a competing source of truth when DBC is present.
    private Class<?> capabilitiesOwner;
    private Field capabilitiesField;
    private Class<?> capabilitiesType;
    private Field flyingField;

    // ModelBipedDBC.y is static/shared. It is retained only per actual player body
    // draw so another entity can never overwrite the local player's state.
    private final IdentityHashMap<Object,RenderState> renderStates=new IdentityHashMap<Object,RenderState>();
    // 2.2 snapshots all tick-stable DBC/JRM/vanilla probes together. Render-only JBRA
    // evidence remains live in RenderState so a prone/body call-site can still refine
    // flight presentation later in the same display frame.
    private final IdentityHashMap<Object,TickSnapshot> tickSnapshots=new IdentityHashMap<Object,TickSnapshot>();
    private final DbcFlightStateMachine flightMachine=new DbcFlightStateMachine();

    private DbcClientState() {}

    int blocking(Object player) {TickSnapshot s=snapshot(player);return s==null?0:s.blocking;}

    /** Exact JRMCore ExtendedPlayer Ki-shoot animation selector used by ModelBipedBody.
     * Values > 0 mean DBC itself is presenting a Ki attack/charge pose. */
    int kiAttackAnimation(Object player) {TickSnapshot s=snapshot(player);return s==null?0:s.kiAttack;}

    private TickSnapshot snapshot(Object player) {
        if(player==null)return null;
        initJrm();
        int tick=CombatController.INSTANCE.tick();
        TickSnapshot state=tickSnapshots.get(player);
        if(state!=null&&state.tick==tick)return state;
        if(state==null){state=new TickSnapshot();tickSnapshots.put(player,state);}
        long prof=RuntimeProfiler.INSTANCE.begin();
        state.tick=tick;state.blocking=0;state.kiAttack=0;
        state.onGround=Compat.onGround(player);state.speed=Compat.speed(player);
        if(extendedGet!=null) {
            try {
                Object extended=extendedGet.invoke(null,player);
                if(extended!=null) {
                    if(getBlocking!=null) {Object v=getBlocking.invoke(extended);if(v instanceof Number)state.blocking=((Number)v).intValue();}
                    if(getAnimKiShoot!=null) {Object v=getAnimKiShoot.invoke(extended);if(v instanceof Number)state.kiAttack=((Number)v).intValue();}
                }
            } catch(Throwable ignored){}
        }
        state.directFloating=readDirectDbcFloatingState(player,state.onGround);
        state.capabilityFlying=capabilityFlying(player);
        RuntimeProfiler.INSTANCE.end(RuntimeProfiler.DBC_STATE,prof);
        if(tickSnapshots.size()>128) {tickSnapshots.clear();tickSnapshots.put(player,state);}
        return state;
    }

    boolean flying(Object player) {
        if(player==null)return false;
        refreshFlightVisual(player,CombatController.INSTANCE.tick());
        RenderState r=renderStates.get(player);
        return r!=null&&r.flight.mode!=FLIGHT_NONE;
    }

    int flightVisualMode(Object player) {
        if(player==null)return FLIGHT_NONE;
        refreshFlightVisual(player,CombatController.INSTANCE.tick());
        RenderState r=renderStates.get(player);
        return r==null?FLIGHT_NONE:r.flight.mode;
    }

    /**
     * Called from the exact native JBRA prone-flight transform. Reaching this call-site
     * is the strongest possible CRUISE evidence because the renderer already selected
     * its y=2 branch for this exact player.
     */
    void observeNativeProne(Object player) {
        if(player==null)return;
        if(!CombatController.INSTANCE.battleMode()||player!=Compat.player())return;
        int tick=CombatController.INSTANCE.tick();
        RenderState r=stateFor(player);
        // DBC/JBRA can briefly enter the native y=2 branch during an ordinary jump.
        // Do not promote that first takeoff burst to flight; sustained evidence after
        // the short jump window is still accepted normally.
        TickSnapshot snap=snapshot(player);
        if(r.flight.mode==FLIGHT_NONE&&freshJumpTakeoff(player)&&snap!=null&&snap.directFloating!=1) {
            logJumpGate();
            return;
        }
        int before=r.flight.mode;
        flightMachine.noteNativeProne(r.flight,tick);
        if(before!=r.flight.mode)r.visualRevision++;
        logFlightTransition(player,r,before);
    }

    void refreshFlightVisual(Object player,int tick) {
        if(player==null)return;
        RenderState r=stateFor(player);

        TickSnapshot snap=snapshot(player);if(snap==null)return;
        int direct=snap.directFloating;
        int exact=gameplayFlightState(player,direct,snap.onGround);
        boolean bodyFlight=r.state==2&&r.tick!=Integer.MIN_VALUE&&DbcFlightStateMachine.age(tick,r.tick)<=3;
        boolean nativeProne=r.flight.lastNativeProneTick!=Integer.MIN_VALUE&&DbcFlightStateMachine.age(tick,r.flight.lastNativeProneTick)<=3;
        boolean capability=snap.capabilityFlying;
        if(r.flight.mode==FLIGHT_NONE&&freshJumpTakeoff(player)&&direct!=1) {
            // A normal jump may momentarily satisfy legacy JRM/JBRA render signals.
            // Do not gate DBCKiTech.floating=true: that is an explicit local DBC
            // flight toggle and must enter creative flight immediately.
            if(exact==1)exact=-1;
            bodyFlight=false;
            nativeProne=false;
            capability=false;
            logJumpGate();
        }
        int before=r.flight.mode;
        flightMachine.refresh(r.flight,tick,snap.speed,snap.onGround,exact,bodyFlight,nativeProne,capability);
        if(before!=r.flight.mode)r.visualRevision++;
        logFlightTransition(player,r,before);

        if(!exactFlightLogged&&exact>=0&&player==Compat.player()) {
            exactFlightLogged=true;
            System.out.println("[EpicFight1710] DBC flight authority is centralized: DBCKiTech.floating is primary for the local player; JRMCore, live y=2/native-prone and capability are bounded fallback/fast-flight evidence.");
        }
    }

    /**
     * Returns 1=true, 0=false, -1=JRMCore state unavailable.
     *
     * RenderPlayerJBRA 1.6.52 enters its native flight transform when the player has
     * JRMCore power state 1, is airborne, and either status effect 7 or data3 flag
     * "1" is active. Reusing that exact branch eliminates the OFF/HOVER/CRUISE
     * oscillation caused by creative-flight heuristics and render-order sampling.
     */
    private int gameplayFlightState(Object player,int direct,boolean onGroundNow) {
        RenderState cache=stateFor(player);
        int tick=CombatController.INSTANCE.tick();
        // DBCKiTech.floating=true is the strongest local gameplay signal and must
        // win even if a weaker legacy probe was already cached earlier this tick.
        // A false value is not final because DBC's fast/swoop path can temporarily
        // clear floating while native-prone/JRM evidence remains active.
        if(direct==1)return cacheExact(cache,tick,1);
        if(cache.exactEvalTick==tick)return cache.exactEvalValue;
        initJrm();
        if(jrmCoreH==null||jrmPlayers==null||jrmData3==null)return cacheExact(cache,tick,-1);
        Method powerMethod=compatiblePlayerPower(player);
        if(powerMethod==null)return cacheExact(cache,tick,-1);
        Method statusMethod=compatibleStatusEffect(player);
        try {
            Object power=powerMethod.invoke(null,player);
            if(!(power instanceof Number))return cacheExact(cache,tick,-1);
            if(((Number)power).intValue()!=1)return cacheExact(cache,tick,0);
            if(onGroundNow)return cacheExact(cache,tick,0);

            String name=playerName(player);
            Object po=jrmPlayers.get(null),d3o=jrmData3.get(null);
            if(!(po instanceof String[])||!(d3o instanceof String[]))return cacheExact(cache,tick,-1);
            String[] players=(String[])po,data3=(String[])d3o;
            int index=-1;
            for(int i=0;i<players.length;i++)if(players[i]!=null&&players[i].equals(name)){index=i;break;}
            if(index<0||index>=data3.length)return cacheExact(cache,tick,-1);

            boolean status7=false;
            if(statusMethod!=null) {
                try { Object v=statusMethod.invoke(null,Integer.valueOf(7),player); status7=Boolean.TRUE.equals(v); }
                catch(Throwable ignored) {}
            }
            String flags=data3[index];
            return cacheExact(cache,tick,status7||(flags!=null&&flags.indexOf('1')>=0)?1:0);
        } catch(Throwable ignored){return cacheExact(cache,tick,-1);}
    }

    private static int cacheExact(RenderState r,int tick,int value){r.exactEvalTick=tick;r.exactEvalValue=value;return value;}

    private RenderState stateFor(Object player) {
        RenderState r=renderStates.get(player);
        if(r==null){r=new RenderState();renderStates.put(player,r);}
        return r;
    }

    private void logFlightTransition(Object player,RenderState r,int before) {
        if(r==null||before==r.flight.mode)return;
        if(!CombatController.INSTANCE.battleMode()||player!=Compat.player())return;
        // HOVER vs CRUISE is only evidence quality now; it no longer chooses an
        // animation. Keep logs focused on actual flight ON/OFF transitions.
        if(before!=FLIGHT_NONE&&r.flight.mode!=FLIGHT_NONE)return;
        String from=modeName(before),to=modeName(r.flight.mode);
        System.out.println("[EpicFight1710] flight "+from+" -> "+to+" reason="+r.flight.transitionReason+
                " evidence="+DbcFlightStateMachine.evidenceName(r.flight.lastEvidence)+
                " speed="+round3(r.flight.smoothedSpeed)+".");
    }

    private static String modeName(int mode){return mode==FLIGHT_CRUISE?"CRUISE":(mode==FLIGHT_HOVER?"HOVER":"OFF");}
    private static float round3(float v){return Math.round(v*1000.0F)/1000.0F;}

    int renderState(Object mainModel) {
        initJrm();
        int s=staticInt(modelBipedDbcState,-1);
        if(s<0&&mainModel!=null) {
            try { Field f=Reflect.field(mainModel.getClass(),"y"); s=staticInt(f,-1); } catch(Throwable ignored) {}
        }
        if(s<0)s=staticInt(modelBipedBodyState,-1);
        return s;
    }

    void noteRenderState(Object player,int state,int tick) {
        if(player==null||state<0)return;
        RenderState r=stateFor(player);
        int oldState=r.state;
        r.state=state;r.tick=tick;
        int before=r.flight.mode;
        if(!(state==2&&r.flight.mode==FLIGHT_NONE&&freshJumpTakeoff(player)))
            flightMachine.noteBodyState(r.flight,tick,state);
        else logJumpGate();
        if(oldState!=state||before!=r.flight.mode)r.visualRevision++;
        logFlightTransition(player,r,before);
        if(renderStates.size()>128) {
            RenderState keep=r;
            renderStates.clear();
            renderStates.put(player,keep);
        }
    }


    /** Revision of render-only DBC evidence that can invalidate a same-frame pose cache. */
    int poseRevision(Object player) {
        RenderState r=player==null?null:renderStates.get(player);
        return r==null?0:r.visualRevision;
    }

    int stableRenderState(Object player) {
        if(player==null)return -1;
        RenderState r=renderStates.get(player);if(r==null||r.tick==Integer.MIN_VALUE)return -1;
        int age=CombatController.INSTANCE.tick()-r.tick;
        return age>=0&&age<=3?r.state:-1;
    }


    private boolean freshJumpTakeoff(Object player) {
        TickSnapshot s=snapshot(player);
        if(player==null||s==null||s.onGround)return false;
        int air=CombatController.INSTANCE.airborneTicks();
        return air>0&&air<=10;
    }

    private void logJumpGate() {
        if(jumpGateLogged)return;
        jumpGateLogged=true;
        System.out.println("[EpicFight1710] Fresh-jump flight debounce active: transient JRM/JBRA y=2 evidence during the first 10 airborne ticks cannot replace JUMP.");
    }

    private synchronized void initJrm() {
        if(jrmInit)return;
        jrmInit=true;
        try {
            Class<?> extended=Class.forName("JinRyuu.JRMCore.i.ExtendedPlayer");
            extendedGet=Reflect.staticMethod(extended,1,"get");
            getBlocking=Reflect.method(extended,0,"getBlocking");
            getAnimKiShoot=Reflect.method(extended,0,"getAnimKiShoot");
        } catch(Throwable ignored) {}
        try {
            Class<?> body=Class.forName("JinRyuu.JRMCore.entity.ModelBipedBody");
            modelBipedBodyState=Reflect.field(body,"y");
        } catch(Throwable ignored) {}
        try {
            Class<?> dbc=Class.forName("JinRyuu.JBRA.ModelBipedDBC");
            modelBipedDbcState=Reflect.field(dbc,"y");
        } catch(Throwable ignored) {}
        try {
            dbcKiTech=Class.forName("JinRyuu.DragonBC.common.DBCKiTech");
            dbcFloating=Reflect.field(dbcKiTech,"floating");
        } catch(Throwable ignored) {dbcKiTech=null;dbcFloating=null;}
        try {
            jrmCoreH=Class.forName("JinRyuu.JRMCore.JRMCoreH");
            jrmPlayers=Reflect.field(jrmCoreH,"plyrs");
            jrmData3=Reflect.field(jrmCoreH,"data3");
            // Overloads exist, so resolve by compatible parameter types rather than
            // taking the first same-name method returned by reflection.
            jrmPlayerPower=findCompatibleStatic(jrmCoreH,"PlyrPwr",new Class<?>[]{null});
            jrmStatusEffect7=findCompatibleStatic(jrmCoreH,"StusEfctsClient",new Class<?>[]{Integer.TYPE,null});
        } catch(Throwable ignored) {jrmCoreH=null;}
    }

    private int readDirectDbcFloatingState(Object player,boolean onGroundNow) {
        if(player==null||player!=Compat.player())return -1;
        initJrm();
        if(dbcFloating==null)return -1;
        try {
            boolean v=dbcFloating.getBoolean(null);
            if(!directFlightLogged) {
                directFlightLogged=true;
                System.out.println("[EpicFight1710] Direct DBC flight state active: DBCKiTech.floating drives local creative-flight continuity; data3/status7 no longer drop the animator to FALL mid-flight.");
            }
            return v&&!onGroundNow?1:0;
        } catch(Throwable ignored) {return -1;}
    }

    private Method compatiblePlayerPower(Object player) {
        if(jrmCoreH==null||player==null)return null;
        if(jrmPlayerPower!=null) {
            Class<?>[] p=jrmPlayerPower.getParameterTypes();
            if(p.length==1&&p[0].isAssignableFrom(player.getClass()))return jrmPlayerPower;
        }
        jrmPlayerPower=findCompatibleStatic(jrmCoreH,"PlyrPwr",new Class<?>[]{player.getClass()});
        return jrmPlayerPower;
    }

    private Method compatibleStatusEffect(Object player) {
        if(jrmCoreH==null||player==null)return null;
        if(jrmStatusEffect7!=null) {
            Class<?>[] p=jrmStatusEffect7.getParameterTypes();
            if(p.length==2&&isIntType(p[0])&&p[1].isAssignableFrom(player.getClass()))return jrmStatusEffect7;
        }
        jrmStatusEffect7=findCompatibleStatic(jrmCoreH,"StusEfctsClient",new Class<?>[]{Integer.TYPE,player.getClass()});
        return jrmStatusEffect7;
    }

    private static Method findCompatibleStatic(Class<?> owner,String name,Class<?>[] wanted) {
        if(owner==null)return null;
        Class<?> c=owner;
        while(c!=null) {
            Method[] methods;try{methods=c.getDeclaredMethods();}catch(Throwable t){methods=new Method[0];}
            for(Method m:methods) {
                if(!m.getName().equals(name)||!Modifier.isStatic(m.getModifiers()))continue;
                Class<?>[] p=m.getParameterTypes();if(p.length!=wanted.length)continue;
                boolean ok=true;
                for(int i=0;i<p.length;i++) {
                    Class<?> w=wanted[i];
                    if(w==null)continue;
                    if(isIntType(w)) { if(!isIntType(p[i])){ok=false;break;} }
                    else if(!p[i].isAssignableFrom(w)){ok=false;break;}
                }
                if(ok){try{m.setAccessible(true);}catch(Throwable ignored){}return m;}
            }
            c=c.getSuperclass();
        }
        return null;
    }

    private static boolean isIntType(Class<?> c){return c==Integer.TYPE||c==Integer.class;}

    private String playerName(Object player) {
        if(player==null)return "";
        try {
            Class<?> pc=player.getClass();
            if(nameOwner!=pc) {
                nameOwner=pc;
                playerName=Reflect.method(pc,0,"func_70005_c_","getCommandSenderName","getName");
            }
            if(playerName!=null) {
                Object n=playerName.invoke(player);
                if(n!=null)return String.valueOf(n);
            }
        } catch(Throwable ignored) {}
        return "";
    }

    private static int staticInt(Field f,int fallback){
        if(f==null)return fallback;
        try{return f.getInt(null);}catch(Throwable ignored){try{Object v=f.get(null);return v instanceof Number?((Number)v).intValue():fallback;}catch(Throwable ignored2){return fallback;}}
    }

    private boolean capabilityFlying(Object player) {
        try {
            Class<?> pc=player.getClass();
            if(capabilitiesOwner!=pc) {
                capabilitiesOwner=pc;
                capabilitiesField=Reflect.field(pc,"field_71075_bZ","capabilities");
                capabilitiesType=null;flyingField=null;
            }
            Object caps=Reflect.get(capabilitiesField,player);
            if(caps==null)return false;
            if(capabilitiesType!=caps.getClass()) {
                capabilitiesType=caps.getClass();
                flyingField=Reflect.field(capabilitiesType,"field_75100_b","isFlying");
            }
            return Reflect.getBoolean(flyingField,caps,false);
        } catch(Throwable ignored){return false;}
    }

    private static final class RenderState {
        int state=-1;
        int tick=Integer.MIN_VALUE;
        int exactEvalTick=Integer.MIN_VALUE;
        int exactEvalValue=-1;
        int visualRevision;
        final DbcFlightStateMachine.State flight=new DbcFlightStateMachine.State();
    }
    private static final class TickSnapshot {
        int tick=Integer.MIN_VALUE,blocking,kiAttack,directFloating=-1;
        boolean onGround=true,capabilityFlying;
        float speed;
    }
}
