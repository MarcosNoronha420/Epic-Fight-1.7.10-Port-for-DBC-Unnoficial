package com.nicolas.epicfight1710.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class Reflect {
    private Reflect() {}

    public static Field field(Class<?> type, String... names) {
        Class<?> c=type;
        while(c!=null) {
            for(String n:names) {
                try { Field f=c.getDeclaredField(n); f.setAccessible(true); return f; }
                catch(Throwable ignored) {}
            }
            c=c.getSuperclass();
        }
        return null;
    }

    public static Method method(Class<?> type, int params, String... names) {
        Class<?> c=type;
        while(c!=null) {
            Method[] methods;
            try { methods=c.getDeclaredMethods(); } catch(Throwable t) { methods=new Method[0]; }
            for(String n:names) for(Method m:methods) {
                if(m.getName().equals(n) && m.getParameterTypes().length==params) {
                    try { m.setAccessible(true); } catch(Throwable ignored) {}
                    return m;
                }
            }
            c=c.getSuperclass();
        }
        return null;
    }

    public static Method staticMethod(Class<?> type, int params, String... names) {
        Method m=method(type,params,names);
        return m!=null && Modifier.isStatic(m.getModifiers()) ? m : null;
    }

    public static Object get(Field f,Object o) {
        if(f==null) return null;
        try { return f.get(o); } catch(Throwable t) { return null; }
    }
    public static double getDouble(Field f,Object o,double d) {
        if(f==null) return d;
        try { return f.getDouble(o); } catch(Throwable t) {
            try { Object v=f.get(o); return v instanceof Number?((Number)v).doubleValue():d; } catch(Throwable ignored) { return d; }
        }
    }
    public static float getFloat(Field f,Object o,float d) {
        if(f==null) return d;
        try { return f.getFloat(o); } catch(Throwable t) {
            try { Object v=f.get(o); return v instanceof Number?((Number)v).floatValue():d; } catch(Throwable ignored) { return d; }
        }
    }
    public static int getInt(Field f,Object o,int d) {
        if(f==null) return d;
        try { return f.getInt(o); } catch(Throwable t) {
            try { Object v=f.get(o); return v instanceof Number?((Number)v).intValue():d; } catch(Throwable ignored) { return d; }
        }
    }
    public static boolean getBoolean(Field f,Object o,boolean d) {
        if(f==null) return d;
        try { return f.getBoolean(o); } catch(Throwable t) {
            try { Object v=f.get(o); return v instanceof Boolean?(Boolean)v:d; } catch(Throwable ignored) { return d; }
        }
    }
}
