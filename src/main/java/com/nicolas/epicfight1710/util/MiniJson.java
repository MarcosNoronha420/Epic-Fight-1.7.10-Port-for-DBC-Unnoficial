package com.nicolas.epicfight1710.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader used by the 1.7.10 runtime configuration.
 * Forge/Minecraft ships Gson, but keeping this parser self-contained prevents the
 * offline Java-8 compatibility compiler from baking another third-party ABI into
 * the port. It intentionally implements JSON only: objects, arrays, strings,
 * numbers, booleans and null.
 */
public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String text) {
        if(text==null)throw new IllegalArgumentException("json text is null");
        Parser p=new Parser(text);
        Object v=p.value();
        p.ws();
        if(!p.end())throw p.error("trailing content");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object v) {
        return v instanceof Map?(Map<String,Object>)v:null;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object v) {
        return v instanceof List?(List<Object>)v:null;
    }
    public static String string(Object v,String def){return v instanceof String?(String)v:def;}
    public static double number(Object v,double def){return v instanceof Number?((Number)v).doubleValue():def;}
    public static int integer(Object v,int def){return v instanceof Number?((Number)v).intValue():def;}
    public static boolean bool(Object v,boolean def){return v instanceof Boolean?((Boolean)v).booleanValue():def;}

    private static final class Parser {
        private final String s; private int i;
        Parser(String s){this.s=s;}
        boolean end(){return i>=s.length();}
        RuntimeException error(String m){return new IllegalArgumentException(m+" at character "+i);}
        void ws(){while(i<s.length()){char c=s.charAt(i);if(c==' '||c=='\t'||c=='\r'||c=='\n')i++;else break;}}
        Object value(){
            ws();if(end())throw error("expected value");
            char c=s.charAt(i);
            if(c=='{')return object(); if(c=='[')return array(); if(c=='\"')return string();
            if(c=='t'){literal("true");return Boolean.TRUE;} if(c=='f'){literal("false");return Boolean.FALSE;}
            if(c=='n'){literal("null");return null;} if(c=='-'||(c>='0'&&c<='9'))return number();
            throw error("unexpected '"+c+"'");
        }
        Map<String,Object> object(){
            LinkedHashMap<String,Object> out=new LinkedHashMap<String,Object>();expect('{');ws();
            if(peek('}')){i++;return out;}
            while(true){ws();if(!peek('\"'))throw error("expected object key");String k=string();ws();expect(':');Object v=value();out.put(k,v);ws();
                if(peek('}')){i++;return out;}expect(',');}
        }
        List<Object> array(){
            ArrayList<Object> out=new ArrayList<Object>();expect('[');ws();if(peek(']')){i++;return out;}
            while(true){out.add(value());ws();if(peek(']')){i++;return out;}expect(',');}
        }
        String string(){
            expect('\"');StringBuilder b=new StringBuilder();
            while(!end()){
                char c=s.charAt(i++);if(c=='\"')return b.toString();
                if(c!='\\'){b.append(c);continue;}
                if(end())throw error("unterminated escape");char e=s.charAt(i++);
                switch(e){case '\"':b.append('\"');break;case '\\':b.append('\\');break;case '/':b.append('/');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;
                    case 'u': if(i+4>s.length())throw error("bad unicode escape");String h=s.substring(i,i+4);i+=4;try{b.append((char)Integer.parseInt(h,16));}catch(NumberFormatException ex){throw error("bad unicode escape");}break;
                    default:throw error("bad escape \\"+e);
                }
            }
            throw error("unterminated string");
        }
        Number number(){
            int start=i;if(peek('-'))i++;if(peek('0'))i++;else digits();
            boolean dec=false;if(peek('.')){dec=true;i++;digits();}
            if(peek('e')||peek('E')){dec=true;i++;if(peek('+')||peek('-'))i++;digits();}
            String n=s.substring(start,i);try{return dec?Double.valueOf(n):Long.valueOf(n);}catch(NumberFormatException ex){throw error("bad number");}
        }
        void digits(){int st=i;while(i<s.length()&&s.charAt(i)>='0'&&s.charAt(i)<='9')i++;if(i==st)throw error("expected digit");}
        void literal(String x){if(!s.regionMatches(i,x,0,x.length()))throw error("expected "+x);i+=x.length();}
        void expect(char c){ws();if(end()||s.charAt(i)!=c)throw error("expected '"+c+"'");i++;}
        boolean peek(char c){return i<s.length()&&s.charAt(i)==c;}
    }
}
