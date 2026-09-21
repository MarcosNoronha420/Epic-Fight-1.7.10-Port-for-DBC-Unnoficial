package com.nicolas.epicfight1710.client;

/**
 * Single flight-state authority for the DBC/JBRA compatibility path.
 *
 * DBCKiTech.floating=true is promoted by DbcClientState as the primary exact local
 * flight signal. Live JRMCore/JBRA body/native-prone evidence can confirm flight immediately and bridge short render-order gaps. Missing evidence gets
 * a modest grace period only when the exact JRM state is unavailable; an explicit
 * JRMCore=false is intentionally stronger and ends flight promptly after debounce.
 */
final class DbcFlightStateMachine {
    static final int NONE=0, HOVER=1, CRUISE=2;

    static final int EVIDENCE_JRM=1;
    static final int EVIDENCE_BODY=2;
    static final int EVIDENCE_NATIVE_PRONE=4;
    static final int EVIDENCE_CAPABILITY=8;

    private static final int NATIVE_CRUISE_GRACE=6;
    private static final int UNAVAILABLE_POSITIVE_GRACE=20;
    private static final int EXPLICIT_FALSE_AIR_DEBOUNCE=8;
    private static final int EXPLICIT_FALSE_GROUND_DEBOUNCE=4;
    private static final int UNAVAILABLE_AIR_FAILSAFE=120;

    static final class State {
        int mode=NONE;
        int lastPositiveTick=Integer.MIN_VALUE;
        int lastNativeProneTick=Integer.MIN_VALUE;
        int lastBodyFlightTick=Integer.MIN_VALUE;
        int absentTicks;
        int explicitFalseTicks;
        int groundedAbsentTicks;
        int lastEvidence;
        int lastRefreshTick=Integer.MIN_VALUE;
        int transitionTick=Integer.MIN_VALUE;
        String transitionReason="initial";
        float smoothedSpeed;
        boolean speedInitialized;
    }

    void refresh(State s,int tick,float speed,boolean grounded,int exact,boolean bodyFlightRecent,boolean nativeProneRecent,boolean capability) {
        if(s==null)return;
        smoothSpeed(s,speed);
        if(s.lastRefreshTick==tick)return;
        s.lastRefreshTick=tick;

        int evidence=0;
        if(exact==1)evidence|=EVIDENCE_JRM;
        if(bodyFlightRecent)evidence|=EVIDENCE_BODY;
        if(nativeProneRecent)evidence|=EVIDENCE_NATIVE_PRONE;
        if(capability)evidence|=EVIDENCE_CAPABILITY;
        s.lastEvidence=evidence;

        if(evidence!=0) {
            s.lastPositiveTick=tick;
            s.absentTicks=0;
            s.explicitFalseTicks=0;
            s.groundedAbsentTicks=0;
        } else {
            if(s.absentTicks<1000000)s.absentTicks++;
            if(exact==0) {
                if(s.explicitFalseTicks<1000000)s.explicitFalseTicks++;
                if(grounded) {
                    if(s.groundedAbsentTicks<1000000)s.groundedAbsentTicks++;
                } else s.groundedAbsentTicks=0;
            } else {
                s.explicitFalseTicks=0;
                s.groundedAbsentTicks=0;
            }
        }

        // The native prone branch is the strongest visual evidence and confirms the
        // whole-player prone presentation for this exact player.
        if(nativeProneRecent) {
            transition(s,CRUISE,tick,"nativeProne");
            return;
        }

        // DBCKiTech.floating=true is promoted before this point, so an airborne false
        // here means neither the direct DBC toggle nor any body/native/capability
        // evidence is present. Keep a short debounce for render-order gaps, not a
        // multi-second grace that masks a deliberate flight exit.
        if(exact==0&&evidence==0) {
            if(grounded&&s.groundedAbsentTicks>=EXPLICIT_FALSE_GROUND_DEBOUNCE) {
                transition(s,NONE,tick,"explicitGroundFalse");
                return;
            }
            if(!grounded&&s.explicitFalseTicks>=EXPLICIT_FALSE_AIR_DEBOUNCE) {
                transition(s,NONE,tick,"explicitAirborneFalse");
                return;
            }
            // During the debounce retain the current state rather than manufacturing
            // a new flight state when we were already NONE.
            return;
        }

        int positiveAge=age(tick,s.lastPositiveTick);
        int nativeAge=age(tick,s.lastNativeProneTick);

        // HOVER/CRUISE are evidence-quality diagnostics only; animation selection is
        // handled independently by EpicCreativeFlightPolicy. Retain CRUISE briefly so
        // intermittent render calls do not spam internal transitions.
        if(s.mode==CRUISE&&nativeAge<=NATIVE_CRUISE_GRACE)return;

        if(evidence!=0) {
            transition(s,HOVER,tick,"positiveEvidence");
            return;
        }

        // Only installations where exact JRM state cannot be resolved get positive
        // grace. The target DBC/JRMCore stack normally never needs this fallback.
        if(exact<0&&positiveAge<=UNAVAILABLE_POSITIVE_GRACE) {
            transition(s,HOVER,tick,"unavailableGrace");
            return;
        }

        if(grounded) {
            transition(s,NONE,tick,"groundedNoEvidence");
        } else if(exact<0&&s.absentTicks>=UNAVAILABLE_AIR_FAILSAFE) {
            transition(s,NONE,tick,"unavailableAirFailsafe");
        } else if(exact<0&&positiveAge>UNAVAILABLE_POSITIVE_GRACE) {
            transition(s,NONE,tick,"unavailableGraceExpired");
        }
    }

    void noteNativeProne(State s,int tick) {
        if(s==null)return;
        s.lastNativeProneTick=tick;
        s.lastPositiveTick=tick;
        s.absentTicks=0;
        s.explicitFalseTicks=0;
        s.groundedAbsentTicks=0;
        s.lastEvidence|=EVIDENCE_NATIVE_PRONE;
        transition(s,CRUISE,tick,"nativeProne");
    }

    void noteBodyState(State s,int tick,int renderState) {
        if(s==null||renderState!=2)return;
        s.lastBodyFlightTick=tick;
        s.lastPositiveTick=tick;
        s.absentTicks=0;
        s.explicitFalseTicks=0;
        s.groundedAbsentTicks=0;
        s.lastEvidence|=EVIDENCE_BODY;
        if(s.mode==NONE)transition(s,HOVER,tick,"bodyState2");
    }

    private static void smoothSpeed(State s,float speed) {
        if(!s.speedInitialized){s.smoothedSpeed=speed;s.speedInitialized=true;}
        else s.smoothedSpeed=s.smoothedSpeed*.68F+speed*.32F;
    }

    private static void transition(State s,int mode,int tick,String reason) {
        if(s.mode==mode)return;
        s.mode=mode;
        s.transitionTick=tick;
        s.transitionReason=reason;
    }

    static int age(int tick,int then) {
        if(then==Integer.MIN_VALUE)return Integer.MAX_VALUE;
        int a=tick-then;
        return a<0?Integer.MAX_VALUE:a;
    }

    static String evidenceName(int mask) {
        if(mask==0)return "none";
        StringBuilder b=new StringBuilder();
        append(b,mask,EVIDENCE_JRM,"JRM");
        append(b,mask,EVIDENCE_BODY,"BODY");
        append(b,mask,EVIDENCE_NATIVE_PRONE,"NATIVE");
        append(b,mask,EVIDENCE_CAPABILITY,"CAP");
        return b.toString();
    }

    private static void append(StringBuilder b,int mask,int bit,String name) {
        if((mask&bit)==0)return;
        if(b.length()>0)b.append('+');
        b.append(name);
    }
}
