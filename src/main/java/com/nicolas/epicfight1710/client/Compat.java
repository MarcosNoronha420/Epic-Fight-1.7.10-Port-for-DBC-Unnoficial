package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.util.Reflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class Compat {
    private static boolean initialized;
    private static Object minecraft;
    private static Field mcPlayer, mcController, mcTextureManager, mcScreen, mcSettings, mcEntityRenderer, mcDisplayWidth, mcDisplayHeight, mcDataDir;
    private static Field gsThirdPerson, gsSprintKey, gsForwardKey, gsBackKey, gsLeftKey, gsRightKey;
    private static Class<?> sprintKeyType, movementKeyType;
    private static Method sprintKeyDown, movementKeyDown;

    private static Field posX,posY,posZ,prevX,prevY,prevZ,motionXField,motionYField,motionZField,yaw,prevYaw,pitch,prevPitch,renderYawOffset,prevRenderYawOffset,width,height,yOffset,onGround,worldObj,ticksExisted,isDead,ridingEntity,hurtTime;
    private static Field loadedEntities;
    private static Method isSprinting, isSneaking, isInWater, isOnLadder, isSleeping, canSeeEntity, getEyeHeight, moveEntity, isInvisible, setInvisible;
    private static Method getItemInUse, getItemUseAction, getCurrentEquippedItem, stackGetItem;
    // Entity boolean state is tick-driven in 1.7.10. Avoid reflective Method.invoke
    // hundreds of times per second from render-time animation sampling.
    private static Object flagPlayer;
    private static int flagTick=Integer.MIN_VALUE;
    private static boolean flagSprinting,flagSneaking,flagWater,flagLadder,flagSleeping;
    private static Class<?> stackType;
    private static final Map<Class<?>,String> heldItemHierarchyCache=new HashMap<Class<?>,String>();
    // Forge event access used to rescan class hierarchies on every RenderHand and
    // RenderPlayer Pre/Post callback. Cache resolved Fields by concrete event class.
    private static final Map<Class<?>,EventAccess> eventAccessCache=new HashMap<Class<?>,EventAccess>();
    private static final Map<Class<?>,Boolean> livingClassCache=new HashMap<Class<?>,Boolean>();
    private static final Map<Class<?>,Method> rendererTextureMethodCache=new HashMap<Class<?>,Method>();
    private static Method skinLocation, bindTexture, attackEntity;
    private static Method worldPlaySoundAtEntity;
    private static Class<?> worldEffectClass;

    // Exact JRMCore Instant Transmission client-particle bridge. Resolved lazily so
    // the port remains load-safe when DBC/JRMCore is absent on a physical server.
    private static Field dbcPacketHandlerField;
    private static Object dbcPacketHandler;
    private static Method dbcHandleTick, entityId;
    private static boolean dbcInstantParticleLogged, dbcInstantParticleWarned;

    private static Field rmX,rmY,rmZ, rmInstance;
    private static Object renderManager;
    private static Object rendererPlayer,cachedPlayerRenderer;
    private static Method rmEntityRenderer;
    private static boolean warned;
    private static boolean textureLogged;

    private Compat() {}

    public static synchronized boolean init() {
        if(initialized) return minecraft!=null;
        initialized=true;
        try {
            Class<?> mcClass=Class.forName("net.minecraft.client.Minecraft");
            Method get=Reflect.staticMethod(mcClass,0,"func_71410_x","getMinecraft");
            if(get==null) throw new NoSuchMethodException("Minecraft.getMinecraft/func_71410_x");
            minecraft=get.invoke(null);
            mcPlayer=Reflect.field(mcClass,"field_71439_g","thePlayer");
            mcController=Reflect.field(mcClass,"field_71442_b","playerController");
            mcTextureManager=Reflect.field(mcClass,"field_71446_o","renderEngine");
            mcScreen=Reflect.field(mcClass,"field_71462_r","currentScreen");
            mcSettings=Reflect.field(mcClass,"field_71474_y","gameSettings");
            mcEntityRenderer=Reflect.field(mcClass,"field_71460_t","entityRenderer");
            mcDisplayWidth=Reflect.field(mcClass,"field_71443_c","displayWidth");
            mcDisplayHeight=Reflect.field(mcClass,"field_71440_d","displayHeight");
            mcDataDir=Reflect.field(mcClass,"field_71412_D","mcDataDir");
            Object player=Reflect.get(mcPlayer,minecraft);
            if(player!=null) initPlayer(player);
            Class<?> rm=Class.forName("net.minecraft.client.renderer.entity.RenderManager");
            rmX=Reflect.field(rm,"field_78725_b","renderPosX");
            rmY=Reflect.field(rm,"field_78726_c","renderPosY");
            rmZ=Reflect.field(rm,"field_78723_d","renderPosZ");
            rmInstance=Reflect.field(rm,"field_78727_a","instance");
            renderManager=Reflect.get(rmInstance,null);
            rmEntityRenderer=Reflect.method(rm,1,"func_78713_a","getEntityRenderObject");
            return true;
        } catch(Throwable t) {
            warn("compat bootstrap",t); minecraft=null; return false;
        }
    }

    private static synchronized void initPlayer(Object player) {
        if(posX!=null || player==null) return;
        Class<?> pc=player.getClass();
        posX=Reflect.field(pc,"field_70165_t","posX"); posY=Reflect.field(pc,"field_70163_u","posY"); posZ=Reflect.field(pc,"field_70161_v","posZ");
        prevX=Reflect.field(pc,"field_70142_S","prevPosX"); prevY=Reflect.field(pc,"field_70137_T","prevPosY"); prevZ=Reflect.field(pc,"field_70136_U","prevPosZ");
        motionXField=Reflect.field(pc,"field_70159_w","motionX"); motionYField=Reflect.field(pc,"field_70181_x","motionY"); motionZField=Reflect.field(pc,"field_70179_y","motionZ");
        yaw=Reflect.field(pc,"field_70177_z","rotationYaw"); prevYaw=Reflect.field(pc,"field_70126_B","prevRotationYaw");
        pitch=Reflect.field(pc,"field_70125_A","rotationPitch"); prevPitch=Reflect.field(pc,"field_70127_C","prevRotationPitch");
        renderYawOffset=Reflect.field(pc,"field_70761_aq","renderYawOffset"); prevRenderYawOffset=Reflect.field(pc,"field_70760_ar","prevRenderYawOffset");
        width=Reflect.field(pc,"field_70130_N","width"); height=Reflect.field(pc,"field_70131_O","height");
        // Entity.yOffset is the exact vertical contract RenderPlayer 1.7.10 subtracts
        // before delegating to RendererLivingEntity.  The SRG name is confirmed by
        // the exact DBC/JBRA runtime jars used by this project.
        yOffset=Reflect.field(pc,"field_70129_M","yOffset");
        onGround=Reflect.field(pc,"field_70122_E","onGround"); worldObj=Reflect.field(pc,"field_70170_p","worldObj");
        ticksExisted=Reflect.field(pc,"field_70173_aa","ticksExisted"); isDead=Reflect.field(pc,"field_70128_L","isDead"); ridingEntity=Reflect.field(pc,"field_70154_o","ridingEntity"); hurtTime=Reflect.field(pc,"field_70737_aN","hurtTime");
        isSprinting=Reflect.method(pc,0,"func_70051_ag","isSprinting");
        isSneaking=Reflect.method(pc,0,"func_70093_af","isSneaking");
        isInWater=Reflect.method(pc,0,"func_70090_H","isInWater");
        isOnLadder=Reflect.method(pc,0,"func_70617_f_","isOnLadder");
        isSleeping=Reflect.method(pc,0,"func_71065_l","isPlayerSleeping");
        getItemInUse=Reflect.method(pc,0,"func_71011_bu","getItemInUse");
        getCurrentEquippedItem=Reflect.method(pc,0,"func_70694_bm","getCurrentEquippedItem");
        getEyeHeight=Reflect.method(pc,0,"func_70047_e","getEyeHeight");
        moveEntity=Reflect.method(pc,3,"func_70091_d","moveEntity");
        isInvisible=Reflect.method(pc,0,"func_82150_aj","isInvisible");
        setInvisible=Reflect.method(pc,1,"func_82142_c","setInvisible");
        canSeeEntity=Reflect.method(pc,1,"func_70685_l","canEntityBeSeen");
        skinLocation=Reflect.method(pc,0,"func_110306_p","getLocationSkin");
        Object world=Reflect.get(worldObj,player);
        if(world!=null) loadedEntities=Reflect.field(world.getClass(),"field_72996_f","loadedEntityList");
        Object settings=Reflect.get(mcSettings,minecraft);
        if(settings!=null) gsThirdPerson=Reflect.field(settings.getClass(),"field_74320_O","thirdPersonView");
        Object tm=Reflect.get(mcTextureManager,minecraft);
        if(tm!=null) bindTexture=Reflect.method(tm.getClass(),1,"func_110577_a","bindTexture");
        Object ctrl=Reflect.get(mcController,minecraft);
        if(ctrl!=null) attackEntity=Reflect.method(ctrl.getClass(),2,"func_78764_a","attackEntity");
    }

    public static Object minecraft() { init(); return minecraft; }
    public static Object player() {
        if(!init()) return null;
        Object p=Reflect.get(mcPlayer,minecraft); if(p!=null) initPlayer(p); return p;
    }
    public static boolean guiOpen() { return init() && Reflect.get(mcScreen,minecraft)!=null; }

    /** Minecraft instance directory used for persistent port configuration. */
    public static java.io.File gameDirectory(){
        try{init();Object v=Reflect.get(mcDataDir,minecraft);return v instanceof java.io.File?(java.io.File)v:new java.io.File(".");}
        catch(Throwable t){return new java.io.File(".");}
    }

    private static EventAccess eventAccess(Object event) {
        if(event==null)return EventAccess.EMPTY;
        Class<?> type=event.getClass();
        EventAccess a=eventAccessCache.get(type);
        if(a!=null)return a;
        synchronized(eventAccessCache) {
            a=eventAccessCache.get(type);
            if(a==null) {
                a=new EventAccess();
                a.player=Reflect.field(type,"entityPlayer");
                a.partial=Reflect.field(type,"partialRenderTick");
                a.handPartial=Reflect.field(type,"partialTicks","partialRenderTick");
                a.renderer=Reflect.field(type,"renderer");
                a.button=Reflect.field(type,"button");
                a.buttonState=Reflect.field(type,"buttonstate");
                a.phase=Reflect.field(type,"phase");
                eventAccessCache.put(type,a);
            }
        }
        return a;
    }
    public static Object eventPlayer(Object event) { return Reflect.get(eventAccess(event).player,event); }
    public static float eventPartial(Object event) { return Reflect.getFloat(eventAccess(event).partial,event,0.0F); }
    public static float handPartial(Object event) { return Reflect.getFloat(eventAccess(event).handPartial,event,0.0F); }
    public static Object eventRenderer(Object event) { return Reflect.get(eventAccess(event).renderer,event); }
    public static int mouseButton(Object event) { return Reflect.getInt(eventAccess(event).button,event,-1); }
    public static boolean mouseDown(Object event) { return Reflect.getBoolean(eventAccess(event).buttonState,event,false); }
    public static boolean isEndTick(Object event) {
        Object p=Reflect.get(eventAccess(event).phase,event);
        return p==null || "END".equals(String.valueOf(p));
    }

    public static double x(Object p){initPlayer(p);return Reflect.getDouble(posX,p,0);} public static double y(Object p){initPlayer(p);return Reflect.getDouble(posY,p,0);} public static double z(Object p){initPlayer(p);return Reflect.getDouble(posZ,p,0);}
    public static double px(Object p){initPlayer(p);return Reflect.getDouble(prevX,p,x(p));} public static double py(Object p){initPlayer(p);return Reflect.getDouble(prevY,p,y(p));} public static double pz(Object p){initPlayer(p);return Reflect.getDouble(prevZ,p,z(p));}
    public static float yaw(Object p){initPlayer(p);return Reflect.getFloat(yaw,p,0);} public static float prevYaw(Object p){initPlayer(p);return Reflect.getFloat(prevYaw,p,yaw(p));}
    public static float pitch(Object p){initPlayer(p);return Reflect.getFloat(pitch,p,0);} public static float prevPitch(Object p){initPlayer(p);return Reflect.getFloat(prevPitch,p,pitch(p));}
    public static float bodyYaw(Object p){initPlayer(p);return Reflect.getFloat(renderYawOffset,p,yaw(p));}
    public static float prevBodyYaw(Object p){initPlayer(p);return Reflect.getFloat(prevRenderYawOffset,p,bodyYaw(p));}
    public static float aimYawDelta(Object p){return wrapDegrees(yaw(p)-bodyYaw(p));}
    public static float aimYawDelta(Object p,float partial){
        float a=partial<0?0:(partial>1?1:partial);
        float hy=prevYaw(p)+wrapDegrees(yaw(p)-prevYaw(p))*a;
        float by=prevBodyYaw(p)+wrapDegrees(bodyYaw(p)-prevBodyYaw(p))*a;
        return wrapDegrees(hy-by);
    }
    public static float width(Object p){initPlayer(p);return Reflect.getFloat(width,p,.6F);}
    public static float height(Object p){initPlayer(p);return Reflect.getFloat(height,p,1.8F);}
    /** Exact legacy RenderPlayer vertical offset.  Do not substitute getEyeHeight():
     * 1.7.10 uses different eye-height semantics. */
    public static float yOffset(Object p){initPlayer(p);return Reflect.getFloat(yOffset,p,Math.max(.1F,height(p)*.90F));}
    public static float viewX(Object p){float iy=interpolatedYaw(p,1.0F),ip=interpolatedPitch(p,1.0F);double yr=Math.toRadians(iy),pr=Math.toRadians(ip);return (float)(-Math.sin(yr)*Math.cos(pr));}
    public static float viewY(Object p){return (float)(-Math.sin(Math.toRadians(interpolatedPitch(p,1.0F))));}
    public static float viewZ(Object p){float iy=interpolatedYaw(p,1.0F),ip=interpolatedPitch(p,1.0F);double yr=Math.toRadians(iy),pr=Math.toRadians(ip);return (float)(Math.cos(yr)*Math.cos(pr));}
    private static float interpolatedYaw(Object p,float partial){float a=partial<0?0:(partial>1?1:partial);return prevYaw(p)+wrapDegrees(yaw(p)-prevYaw(p))*a;}
    private static float interpolatedPitch(Object p,float partial){float a=partial<0?0:(partial>1?1:partial);return prevPitch(p)+(pitch(p)-prevPitch(p))*a;}
    /** Allocation-free interpolated look vector used by the DBC flight selector. */
    public static void viewVector(Object p,float partial,float[] out){
        if(out==null||out.length<3)return;
        float iy=interpolatedYaw(p,partial);
        float ip=interpolatedPitch(p,partial);
        double yr=Math.toRadians(iy),pr=Math.toRadians(ip),cp=Math.cos(pr);
        out[0]=(float)(-Math.sin(yr)*cp);
        out[1]=(float)(-Math.sin(pr));
        out[2]=(float)(Math.cos(yr)*cp);
    }
    public static boolean onGround(Object p){initPlayer(p);return Reflect.getBoolean(onGround,p,true);} public static int ticks(Object p){initPlayer(p);return Reflect.getInt(ticksExisted,p,0);}
    public static boolean dead(Object p){initPlayer(p);return Reflect.getBoolean(isDead,p,false);}
    /** Coherent, allocation-free motion sample. DBC occasionally zeroes Entity.motion*
     * while still moving the player, so the entire vector falls back to position delta. */
    /** Exact Entity.motion* sample without the position-delta fallback. */
    public static void rawMotionVector(Object p,float[] out){
        if(out==null||out.length<3)return;initPlayer(p);
        out[0]=(float)Reflect.getDouble(motionXField,p,0);
        out[1]=(float)Reflect.getDouble(motionYField,p,0);
        out[2]=(float)Reflect.getDouble(motionZField,p,0);
    }

    public static void motionVector(Object p,float[] out){
        if(out==null||out.length<3)return;initPlayer(p);
        double mx=Reflect.getDouble(motionXField,p,0),my=Reflect.getDouble(motionYField,p,0),mz=Reflect.getDouble(motionZField,p,0);
        double pdx=x(p)-px(p),pdy=y(p)-py(p),pdz=z(p)-pz(p);
        if((motionXField==null&&motionYField==null&&motionZField==null)||
           (mx*mx+my*my+mz*mz<1.0E-8&&pdx*pdx+pdy*pdy+pdz*pdz>1.0E-8)) {mx=pdx;my=pdy;mz=pdz;}
        out[0]=(float)mx;out[1]=(float)my;out[2]=(float)mz;
    }
    public static void deltaVector(Object p,float[] out){
        if(out==null||out.length<3)return;
        out[0]=(float)(x(p)-px(p));out[1]=(float)(y(p)-py(p));out[2]=(float)(z(p)-pz(p));
    }
    public static float deltaX(Object p){return (float)(x(p)-px(p));}
    public static float deltaY(Object p){return (float)(y(p)-py(p));}
    public static float deltaZ(Object p){return (float)(z(p)-pz(p));}
    public static float speed(Object p){ double dx=x(p)-px(p), dz=z(p)-pz(p); return (float)Math.sqrt(dx*dx+dz*dz); }
    public static float verticalSpeed(Object p){ return (float)(y(p)-py(p)); }
    public static float forwardMotion(Object p){
        double dx=x(p)-px(p), dz=z(p)-pz(p);
        double rad=Math.toRadians(yaw(p)); double fx=-Math.sin(rad), fz=Math.cos(rad);
        return (float)(dx*fx+dz*fz);
    }
    public static float sideMotion(Object p){
        double dx=x(p)-px(p), dz=z(p)-pz(p);
        double rad=Math.toRadians(yaw(p)); double rx=Math.cos(rad), rz=Math.sin(rad);
        return (float)(dx*rx+dz*rz);
    }
    private static void refreshPlayerFlags(Object p){
        if(p==null)return;
        initPlayer(p);
        int tick=Reflect.getInt(ticksExisted,p,Integer.MIN_VALUE);
        if(flagPlayer==p&&flagTick==tick)return;
        flagPlayer=p;flagTick=tick;
        flagSprinting=invokeBool(isSprinting,p);
        flagSneaking=invokeBool(isSneaking,p);
        flagWater=invokeBool(isInWater,p);
        flagLadder=invokeBool(isOnLadder,p);
        flagSleeping=invokeBool(isSleeping,p);
    }
    private static boolean invokeBool(Method m,Object owner){
        try{return m!=null&&Boolean.TRUE.equals(m.invoke(owner));}catch(Throwable t){return false;}
    }
    public static boolean sprinting(Object p){refreshPlayerFlags(p);return flagPlayer==p&&flagSprinting;}
    /** True while the vanilla sprint/run key is physically held.  DBC flight uses
     * this as a presentation modifier: sprint held = prone/plank, otherwise the
     * normal Epic Fight creative-flight presentation remains upright/directional. */
    public static boolean sprintKeyHeld(Object p){
        try {
            init();
            Object settings=Reflect.get(mcSettings,minecraft);
            if(settings!=null) {
                if(gsSprintKey==null)gsSprintKey=Reflect.field(settings.getClass(),"field_151444_V","keyBindSprint");
                Object key=Reflect.get(gsSprintKey,settings);
                if(key!=null) {
                    if(sprintKeyType!=key.getClass()) {
                        sprintKeyType=key.getClass();
                        sprintKeyDown=Reflect.method(sprintKeyType,0,"func_151470_d","getIsKeyPressed","isKeyDown");
                    }
                    if(sprintKeyDown!=null) {
                        Object v=sprintKeyDown.invoke(key);
                        if(v instanceof Boolean)return ((Boolean)v).booleanValue();
                    }
                }
            }
        } catch(Throwable ignored) {}
        return sprinting(p);
    }
    /**
     * Current physical Forward/Back/Left/Right binding vector in world-horizontal
     * coordinates. This reads GameSettings KeyBinding state directly, so rebinds and
     * diagonals work and stale Entity.motion* can never select a Vanish Dash direction.
     * Returns false when the directional bindings cancel or none is held.
     */
    public static boolean movementKeyVector(Object p,float[] out){
        if(out==null||out.length<3||p==null)return false;
        out[0]=out[1]=out[2]=0;
        try {
            init();
            Object settings=Reflect.get(mcSettings,minecraft);if(settings==null)return false;
            Class<?> st=settings.getClass();
            if(gsForwardKey==null)gsForwardKey=Reflect.field(st,"field_74351_w","keyBindForward");
            if(gsBackKey==null)gsBackKey=Reflect.field(st,"field_74368_y","keyBindBack");
            if(gsLeftKey==null)gsLeftKey=Reflect.field(st,"field_74370_x","keyBindLeft");
            if(gsRightKey==null)gsRightKey=Reflect.field(st,"field_74366_z","keyBindRight");
            int forward=(keyBindingDown(Reflect.get(gsForwardKey,settings))?1:0)-(keyBindingDown(Reflect.get(gsBackKey,settings))?1:0);
            // Vanilla 1.7.10 MovementInput uses +strafe for LEFT and -strafe for RIGHT.
            // Keep that exact sign convention before the yaw transform: A -> left, D -> right.
            int side=(keyBindingDown(Reflect.get(gsLeftKey,settings))?1:0)-(keyBindingDown(Reflect.get(gsRightKey,settings))?1:0);
            if(forward==0&&side==0)return false;
            double rad=Math.toRadians(yaw(p));
            double fx=-Math.sin(rad),fz=Math.cos(rad),rx=Math.cos(rad),rz=Math.sin(rad);
            out[0]=(float)(forward*fx+side*rx);
            out[1]=0;
            out[2]=(float)(forward*fz+side*rz);
            return out[0]*out[0]+out[2]*out[2]>1.0E-6F;
        } catch(Throwable ignored){return false;}
    }

    private static boolean keyBindingDown(Object key){
        if(key==null)return false;
        try {
            if(movementKeyType!=key.getClass()) {
                movementKeyType=key.getClass();
                movementKeyDown=Reflect.method(movementKeyType,0,"func_151470_d","getIsKeyPressed","isKeyDown");
            }
            if(movementKeyDown==null)return false;
            Object v=movementKeyDown.invoke(key);return v instanceof Boolean&&((Boolean)v).booleanValue();
        } catch(Throwable ignored){return false;}
    }

    public static boolean sneaking(Object p){refreshPlayerFlags(p);return flagPlayer==p&&flagSneaking;}
    public static boolean inWater(Object p){refreshPlayerFlags(p);return flagPlayer==p&&flagWater;}
    public static boolean onLadder(Object p){refreshPlayerFlags(p);return flagPlayer==p&&flagLadder;}
    public static boolean sleeping(Object p){refreshPlayerFlags(p);return flagPlayer==p&&flagSleeping;}
    public static boolean riding(Object p){ try{initPlayer(p);return Reflect.get(ridingEntity,p)!=null;}catch(Throwable t){return false;} }
    public static int hurtTime(Object p){try{initPlayer(p);return Reflect.getInt(hurtTime,p,0);}catch(Throwable t){return 0;}}


    /** Move through Minecraft's own collision resolver. Returns actual travelled distance. */
    public static double moveEntity(Object p,double dx,double dy,double dz) {
        if(p==null)return 0.0;
        try {
            initPlayer(p);
            if(moveEntity==null)moveEntity=Reflect.method(p.getClass(),3,"func_70091_d","moveEntity");
            if(moveEntity==null)return 0.0;
            double bx=x(p),by=y(p),bz=z(p);
            moveEntity.invoke(p,Double.valueOf(dx),Double.valueOf(dy),Double.valueOf(dz));
            double ax=x(p)-bx,ay=y(p)-by,az=z(p)-bz;
            return Math.sqrt(ax*ax+ay*ay+az*az);
        } catch(Throwable t){warn("dash moveEntity",t);return 0.0;}
    }

    /** Preserve/restore the player's native invisibility bit for the short vanish window. */
    public static boolean invisible(Object p) {
        if(p==null)return false;
        try {initPlayer(p);return isInvisible!=null&&Boolean.TRUE.equals(isInvisible.invoke(p));}
        catch(Throwable ignored){return false;}
    }
    public static void setInvisible(Object p,boolean value) {
        if(p==null)return;
        try {
            initPlayer(p);
            if(setInvisible==null)setInvisible=Reflect.method(p.getClass(),1,"func_82142_c","setInvisible");
            if(setInvisible!=null)setInvisible.invoke(p,Boolean.valueOf(value));
        } catch(Throwable t){warn("dash invisibility",t);}
    }

    public static void setMotion(Object p,double mx,double my,double mz) {
        if(p==null)return;initPlayer(p);
        try {if(motionXField!=null)motionXField.setDouble(p,mx);}catch(Throwable ignored){}
        try {if(motionYField!=null)motionYField.setDouble(p,my);}catch(Throwable ignored){}
        try {if(motionZField!=null)motionZField.setDouble(p,mz);}catch(Throwable ignored){}
    }

    /**
     * Spawn the exact client particle used by JRMCore/DBC Instant Transmission.
     * JRMCorePacHanC.handleTick(50, ...) constructs EntityCusPar with
     * jinryuudragonbc:bens_particles2.png and obeys the native client option
     * instantTransmissionParticles. No copied/custom particle renderer is involved.
     */
    public static boolean dbcInstantTransmissionParticle(Object p,double x,double y,double z) {
        if(p==null)return false;
        try {
            initPlayer(p);
            if(dbcPacketHandlerField==null) {
                Class<?> client=Class.forName("JinRyuu.JRMCore.JRMCoreClient");
                dbcPacketHandlerField=Reflect.field(client,"phc");
            }
            Object handler=Reflect.get(dbcPacketHandlerField,null);
            if(handler==null)return false;
            if(dbcPacketHandler!=handler||dbcHandleTick==null) {
                dbcPacketHandler=handler;
                dbcHandleTick=Reflect.method(handler.getClass(),3,"handleTick");
            }
            if(dbcHandleTick==null)return false;
            if(entityId==null||!entityId.getDeclaringClass().isAssignableFrom(p.getClass()))
                entityId=Reflect.method(p.getClass(),0,"func_145782_y","getEntityId");
            if(entityId==null)return false;
            Object idv=entityId.invoke(p);if(!(idv instanceof Number))return false;
            String payload=((Number)idv).intValue()+";"+x+";"+y+";"+z;
            dbcHandleTick.invoke(handler,Integer.valueOf(50),payload,p);
            if(!dbcInstantParticleLogged) {
                dbcInstantParticleLogged=true;
                System.out.println("[EpicFight1710] Exact DBC/JRMCore Instant Transmission particle bridge active: tick type 50 -> jinryuudragonbc:bens_particles2.png (native client setting respected).");
            }
            return true;
        } catch(Throwable t) {
            if(!dbcInstantParticleWarned) {
                dbcInstantParticleWarned=true;
                System.err.println("[EpicFight1710] Native DBC Instant Transmission particle bridge unavailable; dash continues without the removed cloud/crit fallback.");
            }
            return false;
        }
    }

    /** Reuses a sound event bundled by Dragon Block C itself. */
    public static void playEntitySound(Object p,String event,float volume,float pitchValue) {
        if(p==null||event==null)return;
        try {
            initPlayer(p);Object world=Reflect.get(worldObj,p);if(world==null)return;
            if(worldEffectClass!=world.getClass()) {
                worldEffectClass=world.getClass();
                worldPlaySoundAtEntity=Reflect.method(worldEffectClass,4,"func_72956_a","playSoundAtEntity");
            }
            if(worldPlaySoundAtEntity!=null)worldPlaySoundAtEntity.invoke(world,p,event,Float.valueOf(volume),Float.valueOf(pitchValue));
        } catch(Throwable ignored){}
    }

    /**
     * Lower-case class hierarchy of the currently held Item. This stays reflection-only
     * so the client port does not hard-link DBC weapon classes or a particular addon.
     */
    public static String heldItemTypeName(Object p){
        if(p==null)return null;
        try {
            initPlayer(p);
            if(getCurrentEquippedItem==null)getCurrentEquippedItem=Reflect.method(p.getClass(),0,"func_70694_bm","getCurrentEquippedItem");
            if(getCurrentEquippedItem==null)return null;
            Object stack=getCurrentEquippedItem.invoke(p);if(stack==null)return null;
            if(stackType!=stack.getClass()){stackType=stack.getClass();stackGetItem=Reflect.method(stackType,0,"func_77973_b","getItem");}
            if(stackGetItem==null)return null;Object item=stackGetItem.invoke(stack);if(item==null)return null;
            Class<?> itemClass=item.getClass();String hierarchy=heldItemHierarchyCache.get(itemClass);if(hierarchy!=null)return hierarchy;
            StringBuilder b=new StringBuilder();Class<?> c=itemClass;int depth=0;
            while(c!=null&&c!=Object.class&&depth++<12){if(b.length()>0)b.append(';');b.append(c.getName().toLowerCase(java.util.Locale.ENGLISH));c=c.getSuperclass();}
            hierarchy=b.toString();if(heldItemHierarchyCache.size()>256)heldItemHierarchyCache.clear();heldItemHierarchyCache.put(itemClass,hierarchy);return hierarchy;
        } catch(Throwable t){return null;}
    }

    /** Current vanilla/Forge item-use action without hard-linking ItemStack/EnumAction. */
    public static String itemUseAction(Object p){
        try {
            initPlayer(p);if(getItemInUse==null)return null;Object stack=getItemInUse.invoke(p);if(stack==null)return null;
            if(getItemUseAction==null||!getItemUseAction.getDeclaringClass().isAssignableFrom(stack.getClass()))getItemUseAction=Reflect.method(stack.getClass(),0,"func_77975_n","getItemUseAction");
            if(getItemUseAction==null)return null;Object action=getItemUseAction.invoke(stack);
            return action==null?null:String.valueOf(action).toLowerCase(java.util.Locale.ENGLISH);
        } catch(Throwable t){return null;}
    }

    /** Exact JRMCore ExtendedPlayer blocking state (1 == defending). */
    public static int dbcBlocking(Object p){return DbcClientState.INSTANCE.blocking(p);}
    /** Exact DBC/JRMCore Ki-shoot visual state (ExtendedPlayer#getAnimKiShoot). */
    public static int dbcKiAttackAnimation(Object p){return DbcClientState.INSTANCE.kiAttackAnimation(p);}
    /** DBC/JRM flight state, resolved before generic airborne heuristics. */
    public static boolean dbcFlying(Object p){return DbcClientState.INSTANCE.flying(p);}
    /** 0=not flying, 1=upright hover/slow flight, 2=sustained fast cruise/prone. */
    public static int dbcFlightVisualMode(Object p){return DbcClientState.INSTANCE.flightVisualMode(p);}

    public static double renderX(){return Reflect.getDouble(rmX,null,0);} public static double renderY(){return Reflect.getDouble(rmY,null,0);} public static double renderZ(){return Reflect.getDouble(rmZ,null,0);}

    /** Camera eye height used by Epic Fight-style first-person body-space rendering. */
    public static float eyeHeight(Object p) {
        try {
            initPlayer(p);
            if(getEyeHeight==null)getEyeHeight=Reflect.method(p.getClass(),0,"func_70047_e","getEyeHeight");
            if(getEyeHeight!=null){Object v=getEyeHeight.invoke(p);if(v instanceof Number)return ((Number)v).floatValue();}
        } catch(Throwable ignored) {}
        return Math.max(.1F,height(p)*.90F);
    }

    /** Active entity renderer for the local player, resolved without a hard dependency on JBRA. */
    public static Object rendererFor(Object p) {
        try {
            init();
            if(p!=null&&rendererPlayer==p&&cachedPlayerRenderer!=null)return cachedPlayerRenderer;
            if(renderManager==null&&rmInstance!=null)renderManager=Reflect.get(rmInstance,null);
            if(renderManager==null||p==null)return null;
            if(rmEntityRenderer==null)rmEntityRenderer=Reflect.method(renderManager.getClass(),1,"func_78713_a","getEntityRenderObject");
            Object r=rmEntityRenderer==null?null:rmEntityRenderer.invoke(renderManager,p);
            if(r!=null){rendererPlayer=p;cachedPlayerRenderer=r;}
            return r;
        } catch(Throwable ignored){return null;}
    }

    public static int displayWidth(){init();return Math.max(1,Reflect.getInt(mcDisplayWidth,minecraft,854));}
    public static int displayHeight(){init();return Math.max(1,Reflect.getInt(mcDisplayHeight,minecraft,480));}

    /** Vanilla FOV setting; hand rendering uses the same projection family but does not need world far-plane precision. */
    public static float fovSetting(){
        try {
            Object gs=Reflect.get(mcSettings,minecraft);if(gs==null)return 70.0F;
            Field f=Reflect.field(gs.getClass(),"field_74334_X","fovSetting");
            return Reflect.getFloat(f,gs,70.0F);
        }catch(Throwable ignored){return 70.0F;}
    }

    public static boolean bindPlayerSkin(Object p) { return bindPlayerTexture(p,null); }

    /**
     * Bind the same base entity texture the active player renderer would use when possible.
     * JBRA can override the normal player render path, so asking the renderer first is more
     * faithful than blindly binding AbstractClientPlayer#getLocationSkin.
     */
    public static boolean bindPlayerTexture(Object p,Object renderer) {
        try {
            if(!init() || p==null)return false;
            Object loc=null;
            if(renderer!=null) {
                Method getTexture=findRendererTextureMethod(renderer.getClass(),p.getClass());
                if(getTexture!=null) {
                    try { loc=getTexture.invoke(renderer,p); } catch(Throwable ignored) {}
                }
            }
            if(loc==null) {
                if(skinLocation==null) skinLocation=Reflect.method(p.getClass(),0,"func_110306_p","getLocationSkin");
                if(skinLocation!=null) loc=skinLocation.invoke(p);
            }
            if(loc==null)return false;
            Object tm=Reflect.get(mcTextureManager,minecraft); if(tm==null)return false;
            if(bindTexture==null) bindTexture=Reflect.method(tm.getClass(),1,"func_110577_a","bindTexture");
            if(bindTexture==null)return false;
            bindTexture.invoke(tm,loc);
            if(!textureLogged) {
                textureLogged=true;
                System.out.println("[EpicFight1710] Animated body texture: "+String.valueOf(loc)+(renderer==null?"":" via "+renderer.getClass().getName()));
            }
            return true;
        } catch(Throwable t){ warn("player texture binding",t); return false; }
    }

    private static Method findRendererTextureMethod(Class<?> type,Class<?> playerType) {
        if(type==null)return null;
        if(rendererTextureMethodCache.containsKey(type))return rendererTextureMethodCache.get(type);
        Method found=null;Class<?> c=type;
        while(c!=null&&found==null) {
            Method[] ms; try{ms=c.getDeclaredMethods();}catch(Throwable t){ms=new Method[0];}
            for(String n:new String[]{"func_110775_a","getEntityTexture","getTextureLocation"}) {
                for(Method m:ms) {
                    Class<?>[] pt=m.getParameterTypes();
                    if(!m.getName().equals(n)||pt.length!=1)continue;
                    if(!pt[0].isAssignableFrom(playerType))continue;
                    try{m.setAccessible(true);}catch(Throwable ignored){}
                    found=m;break;
                }
                if(found!=null)break;
            }
            c=c.getSuperclass();
        }
        rendererTextureMethodCache.put(type,found);
        return found;
    }

    public static int getThirdPerson() {
        try { Object gs=Reflect.get(mcSettings,minecraft); if(gs==null)return 0; if(gsThirdPerson==null)gsThirdPerson=Reflect.field(gs.getClass(),"field_74320_O","thirdPersonView"); return Reflect.getInt(gsThirdPerson,gs,0); }
        catch(Throwable t){return 0;}
    }
    public static void setThirdPerson(int mode) {
        try { Object gs=Reflect.get(mcSettings,minecraft); if(gs==null)return; if(gsThirdPerson==null)gsThirdPerson=Reflect.field(gs.getClass(),"field_74320_O","thirdPersonView"); if(gsThirdPerson!=null)gsThirdPerson.setInt(gs,mode); }
        catch(Throwable ignored){}
    }

    @SuppressWarnings("unchecked")
    public static List<?> loadedEntities(Object p) {
        try {
            if(p==null)return null;
            Object world=Reflect.get(worldObj,p);if(world==null)return null;
            if(loadedEntities==null)loadedEntities=Reflect.field(world.getClass(),"field_72996_f","loadedEntityList");
            Object listObj=Reflect.get(loadedEntities,world);return listObj instanceof List?(List<?>)listObj:null;
        } catch(Throwable t){warn("loaded entity access",t);return null;}
    }

    public static boolean livingEntity(Object e) {
        if(e==null)return false;
        Class<?> ec=e.getClass();Boolean living=livingClassCache.get(ec);
        if(living==null){living=Boolean.valueOf(Reflect.method(ec,0,"func_110143_aJ","getHealth")!=null);livingClassCache.put(ec,living);}
        return living.booleanValue();
    }

    public static boolean canSeeAttackTarget(Object p,Object e) {
        if(p==null||e==null)return false;
        try { return canSeeEntity==null||Boolean.TRUE.equals(canSeeEntity.invoke(p,e)); }
        catch(Throwable ignored){return true;}
    }

    @SuppressWarnings("unchecked")
    public static Object findAttackTarget(Object p,float range,float minDot) {
        try {
            List<?> raw=loadedEntities(p);if(raw==null)return null;
            List<Object> list=(List<Object>)raw;
            double ox=x(p), oy=y(p)+Math.max(.35,height(p)*.85F), oz=z(p);
            double fx=viewX(p), fy=viewY(p), fz=viewZ(p);
            Object best=null; double bestScore=Double.MAX_VALUE;
            for(Object e:list) {
                if(e==null || e==p || dead(e))continue;
                // All living entities inherit health/attack handling. Resolve this once per
                // entity class; large mob fights previously rescanned every declared method
                // for every target candidate at each authored hit window.
                if(!livingEntity(e))continue;
                if(!canSeeAttackTarget(p,e))continue;
                double targetY=y(e)+Math.max(.35,height(e)*.55F);
                double dx=x(e)-ox, dy=targetY-oy, dz=z(e)-oz;
                double dist2=dx*dx+dy*dy+dz*dz; if(dist2>range*range || dist2<0.0001)continue;
                double dist=Math.sqrt(dist2);
                double dot=(dx*fx+dy*fy+dz*fz)/dist; if(dot<minDot)continue;
                // Prioritize what is closest to the actual crosshair ray, not merely
                // what happens to be somewhere inside a horizontal forward cone.
                double lateral2=Math.max(0.0,dist2-(dx*fx+dy*fy+dz*fz)*(dx*fx+dy*fy+dz*fz));
                double score=lateral2*3.0+dist2*.08+(1.0-dot)*4.0;
                if(score<bestScore){bestScore=score;best=e;}
            }
            return best;
        } catch(Throwable t){warn("target scan",t);return null;}
    }

    public static boolean vanillaAttack(Object p,Object target) {
        if(p==null || target==null)return false;
        try {
            Object ctrl=Reflect.get(mcController,minecraft); if(ctrl==null)return false;
            if(attackEntity==null)attackEntity=Reflect.method(ctrl.getClass(),2,"func_78764_a","attackEntity");
            if(attackEntity==null)return false;
            attackEntity.invoke(ctrl,p,target); return true;
        } catch(Throwable t){warn("PlayerControllerMP.attackEntity",t);return false;}
    }

    private static float wrapDegrees(float v){while(v<=-180.0F)v+=360.0F;while(v>180.0F)v-=360.0F;return v;}

    private static void warn(String area,Throwable t) {
        if(warned)return; warned=true;
        System.err.println("[EpicFight1710] Compatibility failure in "+area+"; falling back where possible.");
        t.printStackTrace();
    }
    private static final class EventAccess {
        static final EventAccess EMPTY=new EventAccess();
        Field player,partial,handPartial,renderer,button,buttonState,phase;
    }
}
