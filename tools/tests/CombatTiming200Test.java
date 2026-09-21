import com.nicolas.epicfight1710.combat.*;
import java.lang.reflect.*;
public final class CombatTiming200Test {
  static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
  public static void main(String[]args)throws Exception{
    AttackProfile relentless=FistMoveset.get("relentless_combo");check(relentless!=null&&relentless.hitPhases.length==8,"relentless phase count");int mask=0,pointMask=0;float prev=0f;
    for(float cur=.1f;cur<=1.3f+1e-6f;cur+=.1f){for(int i=0;i<relentless.hitPhases.length;i++){int bit=1<<i;if((mask&bit)==0&&relentless.hitPhases[i].activeBetween(prev,cur))mask|=bit;if(relentless.hitPhases[i].active(cur))pointMask|=bit;}prev=cur;}
    check(Integer.bitCount(mask)==8,"interval sample skipped phases");check(Integer.bitCount(pointMask)<8,"point sample unexpectedly complete");
    System.out.println("PASS CombatTiming200Test pointControl="+Integer.bitCount(pointMask)+"/8 interval=8/8");
  }
}
