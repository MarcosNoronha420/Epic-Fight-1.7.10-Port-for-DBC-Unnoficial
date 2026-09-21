package com.nicolas.epicfight1710.combat;

/** Pure, partial native descriptor. Inputs must be captured outside render from
 * authoritative player/config data. Does NOT read native render statics, supply
 * world/joint sockets, or claim that major-part bases describe every surface.
 * Equations audited against JBRA 1.6.52 / JRMCore 1.3.51 / DBC 1.4.85.
 */
public final class NativeDbcSpatialProvider {
    public enum Flight { GROUNDED, NORMAL, FAST }
    public enum Part { HEAD, TORSO, ARM_R, ARM_L, LEG_R, LEG_L }

    /** Active native configuration, not baked-in default tables. Rows are race
     * 0..5; Saiyan and Half-Saiyan must both receive the native Sai tables.
     */
    public static final class Config {
        public final long revision;
        public final boolean constitutionSize,transformSize,godCosmetics;
        public final int maxAttribute;
        private final float[][] bulk,size;
        public Config(long revision,boolean constitutionSize,boolean transformSize,boolean godCosmetics,
                      int maxAttribute,float[][] bulk,float[][] size){
            this.revision=revision;this.constitutionSize=constitutionSize;this.transformSize=transformSize;
            this.godCosmetics=godCosmetics;this.maxAttribute=maxAttribute;
            this.bulk=copy(bulk);this.size=copy(size);
        }
        private static float[][] copy(float[][] a){
            if(a==null)return null;float[][] b=new float[a.length][];
            for(int i=0;i<a.length;i++)b[i]=a[i]==null?null:a[i].clone();return b;
        }
    }

    /** Parsed immutable tick inputs. modelVariant is native gen/g (1..3), NOT
     * a cosmetic body-type index. ageDivisor is the independently resolved f.
     * revision is the snapshot's spatialRevision and must change for any
     * spatial-state change within the same tick; it is not an action serial.
     * Normal flight means the port's non-prone presentation; FAST is unsupported.
     */
    public static final class State {
        public final Object player,world;
        public final int tick,race,form,constitution,release,modelVariant,nativeState,powerType;
        public final long revision;
        public final float ageDivisor,modelPixelScale;
        public final boolean child,sneaking,divine,spectator;
        public final Flight flight;
        public State(Object player,Object world,int tick,long revision,int race,int form,int constitution,
                     int release,int modelVariant,float ageDivisor,float modelPixelScale,boolean child,
                     boolean sneaking,boolean divine,boolean spectator,int nativeState,Flight flight,int powerType){
            this.player=player;this.world=world;this.tick=tick;this.revision=revision;this.race=race;this.form=form;
            this.constitution=constitution;this.release=release;this.modelVariant=modelVariant;
            this.ageDivisor=ageDivisor;this.modelPixelScale=modelPixelScale;this.child=child;
            this.sneaking=sneaking;this.divine=divine;this.spectator=spectator;
            this.nativeState=nativeState;this.flight=flight;this.powerType=powerType;
        }
    }

    public static final class Descriptor {
        public final State state;
        public final long configRevision;
        private final String failure;
        private final float[] outerScale;
        // Each row preserves native S(x,y,z); T(x,y,z) operation arguments.
        private final float[][] parts;
        private Descriptor(State s,Config c,String reason,float[] outer,float[][] parts){
            state=s;configRevision=c==null?0:c.revision;failure=reason;outerScale=outer;this.parts=parts;
        }
        public boolean isValid(){return failure==null;}
        public String invalidReason(){return failure;}
        /** Explicit consumption check; a pure descriptor cannot observe live ticks. */
        public boolean isValidFor(Object player,Object world,int tick,long revision,long configRevision){
            return isValid()&&state!=null&&state.player==player&&state.world==world&&state.tick==tick
                &&state.revision==revision&&this.configRevision==configRevision;
        }
        /** Always false: sockets/complete topology/physical world basis not implemented. */
        public boolean hasCompleteWorldTransform(){return false;}
        public boolean copyOuterScale(float[] out){
            if(!isValid()||out==null||out.length<3)return false;System.arraycopy(outerScale,0,out,0,3);return true;
        }
        public boolean copyPartOperations(Part part,float[] out){
            if(!isValid()||part==null||out==null||out.length<6)return false;
            System.arraycopy(parts[part.ordinal()],0,out,0,6);return true;
        }
        /** LOCAL external part basis, before neutral ModelRenderer tree/retarget.
         * Does not include outerScale. Never use this as a Hand/Tool global.
         */
        public boolean copyPartMatrix(Part part,float[] out){
            if(!isValid()||part==null||out==null||out.length<16)return false;
            float[] b=parts[part.ordinal()];for(int i=0;i<16;i++)out[i]=0;
            out[0]=b[0];out[5]=b[1];out[10]=b[2];out[15]=1;
            out[3]=b[0]*b[3];out[7]=b[1]*b[4];out[11]=b[2]*b[5];return true;
        }
    }

    public Descriptor evaluate(State s,Config c){
        String error=validate(s,c);if(error!=null)return new Descriptor(s,c,error,null,null);
        float bulk=1,size=1;
        if(c.transformSize){
            if(!entry(c.bulk,s.race,s.form)||!entry(c.size,s.race,s.form))
                return new Descriptor(s,c,"Missing authoritative form table entry",null,null);
            bulk=c.bulk[s.race][s.form];size=c.size[s.race][s.form];
            if(s.race==3&&s.form==3&&c.godCosmetics&&s.divine){bulk=1.1F;size=1.5F;}
        }
        int variant=s.modelVariant;
        float base=variant<=1?.73F:.7F,scale=base;
        int max=c.maxAttribute>1000000000?1000000000:c.maxAttribute<100?0:c.maxAttribute;
        scale+=c.constitutionSize?.192F*(float)(s.constitution>max?max:s.constitution)/(float)max:.2F;
        int release=s.release;
        float candidate=(size-1.0F)*(float)release*.02F+1.0F;
        size=candidate>size?size:(size>1.0F?candidate:size);
        float bulkCandidate=(bulk-1.0F)*(float)release*.02F+1.0F;
        bulk=bulk>1.0F?bulkCandidate:bulk;
        float delta=(scale-base)*(release<=50?.25F:.5F);
        float deltaCurrent=delta*(float)release*.02F;
        scale=scale-base-delta+deltaCurrent+base;
        float[] outer={scale*bulk*size,scale*size,scale*bulk*size};
        float f=s.ageDivisor;
        float h=.5F+.5F/f;
        float hy=(f-1.0F)/f*(2.0F-(f>=1.5F&&f<=2.0F?(2.0F-f)/2.5F:(f<1.5F&&f>=1.0F?(f*2.0F-2.0F)*.2F:0.0F)));
        float by=(f-1.0F)*1.5F;
        float[][] parts=new float[Part.values().length][];
        if(variant<=1){
            parts[0]=s.child?basis(.75F,.75F,.75F,0,16.0F*s.modelPixelScale,0):basis(h,h,h,0,hy,0);
            for(int i=1;i<parts.length;i++)parts[i]=s.child?basis(.5F,.5F,.5F,0,24.0F*s.modelPixelScale,0):basis(1.0F/f,1.0F/f,1.0F/f,0,by,0);
        }else{
            parts[0]=basis(h*.85F,h,h*.85F,0,hy,0);
            for(int i=1;i<=3;i++)parts[i]=basis(1.0F/f*.7F,1.0F/f,1.0F/f*.7F,0,by,0);
            parts[4]=basis(1.0F/f*.85F,1.0F/f,1.0F/f*.775F,-.015F,by,s.sneaking?-0.0F:-.015F);
            parts[5]=basis(1.0F/f*.85F,1.0F/f,1.0F/f*.775F,.015F,by,s.sneaking?-0.0F:-.015F);
        }
        for(float v:outer)if(!positive(v))return new Descriptor(s,c,"Non-finite/degenerate native scale",null,null);
        for(float[] b:parts)for(float v:b)if(!finite(v))return new Descriptor(s,c,"Non-finite native part basis",null,null);
        return new Descriptor(s,c,null,outer,parts);
    }

    /**
     * Consume the authoritative per-player snapshot without consulting any
     * renderer state.  Missing or stale inputs become an invalid descriptor;
     * no native default is synthesized here.
     */
    public Descriptor evaluate(DbcSpatialStateSnapshot snapshot,Config c){
        if(snapshot==null)return new Descriptor(null,c,"Missing spatial snapshot",null,null);
        State state=snapshot.toProviderState();
        if(state==null)return new Descriptor(null,c,"Snapshot data unavailable or unsupported",null,null);
        return evaluate(state,c);
    }
    private static float[] basis(float x,float y,float z,float tx,float ty,float tz){return new float[]{x,y,z,tx,ty,tz};}
    private static boolean entry(float[][] a,int race,int form){return a!=null&&race<a.length&&a[race]!=null&&form<a[race].length&&positive(a[race][form]);}
    private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}
    private static boolean positive(float f){return finite(f)&&f>0;}
    private static String validate(State s,Config c){
        if(s==null||c==null||s.player==null||s.world==null)return "Missing state/config/context";
        if(s.powerType!=1)return "Only explicitly captured DBC Ki state is supported";
        if(s.race<0||s.race>5||s.form<0||s.constitution<0||s.release<0||s.release>100)return "Invalid native player state";
        if((s.race==1||s.race==2)&&(s.form==7||s.form==8))return "Oozaru form-specific geometry is not represented";
        if(s.modelVariant<1||s.modelVariant>3||!positive(s.ageDivisor)||!positive(s.modelPixelScale))return "Unresolved body/age descriptor";
        if(s.flight==null||s.flight==Flight.FAST||s.spectator||s.nativeState!=1)return "Unsupported prone/KO/UI/spectator state";
        if(c.constitutionSize&&c.maxAttribute<100)return "Native constitution denominator is zero";
        return null;
    }
}
