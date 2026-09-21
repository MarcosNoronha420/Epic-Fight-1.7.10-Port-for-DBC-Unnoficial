package com.nicolas.epicfight1710.combat;

import java.util.HashMap;
import java.util.Map;

/** Epic Fight 20.9.5 fist basic attacks plus the real RELENTLESS_COMBO innate clip. */
public final class FistMoveset {
    private static final Map<String,AttackProfile> MAP=new HashMap<String,AttackProfile>();
    static {
        // Animations.FIST_AUTO*: BasicAttackAnimation(convert=.08,
        // antic/preDelay=.05, contact=.15, recovery=.15/.15/.50).
        MAP.put("fist_auto1",new AttackProfile("fist_auto1",new AttackPhase(.05F,.15F,.15F,Float.MAX_VALUE,3.10F,.42F),1,false));
        MAP.put("fist_auto2",new AttackProfile("fist_auto2",new AttackPhase(.05F,.15F,.15F,Float.MAX_VALUE,3.15F,.38F),2,false));
        // The three source normal fist motions remain the normal low-CPS loop.
        // RELENTLESS_COMBO below is the real Epic Fight innate clip and is selected
        // only by CombatController's >6.8 CPS compatibility gate.
        MAP.put("fist_auto3",new AttackProfile("fist_auto3",new AttackPhase(.05F,.15F,.50F,Float.MAX_VALUE,3.35F,.34F),3,false));

        // Animations.RELENTLESS_COMBO = new AttackAnimation(.05, ..., 8 phases)
        // with BASIS_ATTACK_SPEED=4.0 and FIST_FIXED for every phase. The five-float
        // Phase constructor maps (start, antic/preDelay, contact, recovery, end).
        AttackPhase[] relentlessHits=new AttackPhase[]{
            new AttackPhase(.016F,.066F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.133F,.183F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.250F,.300F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.366F,.416F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.483F,.533F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.600F,.650F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.716F,.766F,1.10F,Float.MAX_VALUE,3.15F,.30F),
            new AttackPhase(.833F,.883F,1.10F,Float.MAX_VALUE,3.15F,.30F)
        };
        AttackPhase relentlessChain=new AttackPhase(.016F,.066F,1.10F,Float.MAX_VALUE,3.15F,.30F);
        MAP.put("relentless_combo",new AttackProfile("relentless_combo",relentlessChain,relentlessHits,0,false));

        // DashAttackAnimation(.06,.05,.15,.30,.70,...): attack collision is
        // active from preDelay=.15 to contact=.30.
        MAP.put("fist_dash",new AttackProfile("fist_dash",new AttackPhase(.15F,.30F,Float.MAX_VALUE,Float.MAX_VALUE,3.85F,.30F),0,true));
        // Air slash is kept addressable as source data; BasicAttack itself refuses
        // ordinary in-air execution in Epic 20.9.5 and does not select this slot here.
        MAP.put("fist_airslash",new AttackProfile("fist_airslash",new AttackPhase(.10F,.30F,Float.MAX_VALUE,Float.MAX_VALUE,4.10F,.05F),0,false));
    }
    private FistMoveset(){}
    public static AttackProfile get(String clip){return MAP.get(clip);}
    public static AttackProfile combo(int counter){return combo(counter,true);}
    public static AttackProfile combo(int counter,boolean allowRelentless){
        int i=counter%4;if(i<0)i+=4;
        if(i==3)return allowRelentless?get("relentless_combo"):get("fist_auto1");
        return get("fist_auto"+(i+1));
    }
    public static AttackProfile dash(){return get("fist_dash");}
}
