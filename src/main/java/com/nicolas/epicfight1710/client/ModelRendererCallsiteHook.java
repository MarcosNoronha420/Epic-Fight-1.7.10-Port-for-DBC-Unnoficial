package com.nicolas.epicfight1710.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stable bridge called directly by JRMCore ModelBipedBody.
 *
 * The hook decision is made once here. Native fallback is invoked through a cached
 * Java-8 MethodHandle instead of Method.invoke(Object,Object...), avoiding the
 * per-call Object[]/Float allocation and the duplicate global skin-dispatch pass.
 */
public final class ModelRendererCallsiteHook {
    private static final Map<Class<?>,Map<String,Invoker>> CACHE=new WeakHashMap<Class<?>,Map<String,Invoker>>();
    private static Class<?> lastType;
    private static String lastName;
    private static Invoker lastInvoker;
    private static boolean logged;
    private static boolean warned;
    private static long bridgeCalls;
    private static long skinnedCalls;
    private static long nativeFallbackCalls;

    private ModelRendererCallsiteHook() {}

    public static void render(Object part,float scale,String originalMethodName) {
        if(part==null)return;
        bridgeCalls++;
        if(!logged && NativeJbraSkinContext.INSTANCE.active()) {
            logged=true;
            System.out.println("[EpicFight1710] Direct JRMCore body-render bridge is executing inside live JBRA rendering; no dependency on final ModelRenderer bytecode.");
        }
        if(ModelRendererSkinHook.render(part,scale)) {
            skinnedCalls++;
            return;
        }
        nativeFallbackCalls++;
        invokeOriginal(part,scale,originalMethodName);
    }

    private static void invokeOriginal(Object part,float scale,String originalMethodName) {
        ModelRendererSkinHook.beginNativeFallback();
        try {
            Invoker invoker=invoker(part.getClass(),originalMethodName);
            if(invoker==null||invoker.handle==null)throw new NoSuchMethodException(part.getClass().getName()+"."+originalMethodName+"(float)");
            invoker.handle.invokeExact(part,scale);
        } catch(Throwable t) {
            if(!warned) {
                warned=true;
                System.err.println("[EpicFight1710] JRMCore bridge could not invoke one native ModelRenderer fallback; further identical errors are suppressed.");
                t.printStackTrace();
            }
        } finally {
            ModelRendererSkinHook.endNativeFallback();
        }
    }

    private static Invoker invoker(Class<?> type,String name) {
        Invoker last=lastInvoker;
        if(type==lastType&&name!=null&&name.equals(lastName)&&last!=null)return last;
        synchronized(CACHE) {
            Map<String,Invoker> byName=CACHE.get(type);
            if(byName==null){byName=new HashMap<String,Invoker>();CACHE.put(type,byName);}
            if(byName.containsKey(name)){
                Invoker cached=byName.get(name);
                if(cached!=null){lastType=type;lastName=name;lastInvoker=cached;}
                return cached;
            }
            Invoker found=find(type,name);
            byName.put(name,found);
            if(found!=null){lastType=type;lastName=name;lastInvoker=found;}
            return found;
        }
    }

    private static Invoker find(Class<?> type,String name) {
        Method m=null;
        try {m=type.getMethod(name,Float.TYPE);} catch(Throwable ignored) {}
        if(m==null) {
            Class<?> c=type;
            while(c!=null&&m==null) {
                try {m=c.getDeclaredMethod(name,Float.TYPE);} catch(Throwable ignored) {}
                c=c.getSuperclass();
            }
        }
        if(m==null) {
            String[] aliases={"func_78785_a","render"};
            for(String alias:aliases) if(!alias.equals(name)) {
                try {m=type.getMethod(alias,Float.TYPE);break;} catch(Throwable ignored) {}
            }
        }
        if(m==null)return null;
        try {
            m.setAccessible(true);
            MethodHandle h=MethodHandles.lookup().unreflect(m);
            h=h.asType(MethodType.methodType(Void.TYPE,Object.class,Float.TYPE));
            return new Invoker(h);
        } catch(Throwable ignored) {
            return null;
        }
    }

    public static long bridgeCalls(){return bridgeCalls;}
    public static long skinnedCalls(){return skinnedCalls;}
    public static long nativeFallbackCalls(){return nativeFallbackCalls;}
    static void resetPerformanceCounters(){bridgeCalls=skinnedCalls=nativeFallbackCalls=0L;}

    private static final class Invoker {
        final MethodHandle handle;
        Invoker(MethodHandle h){handle=h;}
    }
}
