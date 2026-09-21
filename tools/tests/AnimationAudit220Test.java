import com.nicolas.epicfight1710.anim.*;
import com.nicolas.epicfight1710.anim.runtime.*;
import java.io.*;
import java.util.*;

/** Standalone RC2 source-pose regressions; deliberately excludes DBC/render state.
 * Golden data freezes the baseline, not a claim of upstream or visual correctness.
 */
public final class AnimationAudit220Test {
    private static final String DATA="src/main/resources/assets/epicfight1710/data/";
    private static final String GOLDEN="tools/tests/fixtures/rc2-source-pose.csv";
    private static final String[] CLIPS={"fist_auto1","fist_auto2","fist_auto3",
        "relentless_combo","sword_auto1","walk","creative_fly_forward","creative_fly_backward"};
    private static final float[] TIMES={0.0F,.10F,.20F};
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void near(float a,float b,String message){
        check(!Float.isNaN(a)&&!Float.isInfinite(a)&&Math.abs(a-b)<.00002F,message+": "+a+" != "+b);
    }
    private static float[][] matrices(SkeletonMesh mesh,Clip clip,float time){
        float[][] global=new float[mesh.jointCount][16];
        float[] trs=new float[10],delta=new float[16],local=new float[16];
        for(int j=0;j<mesh.jointCount;j++){
            clip.sampleTRS(j,time,false,trs);
            Mat4.fromTRS(trs,delta);Mat4.mul(mesh.bindLocal[j],delta,local);
            if(mesh.parent[j]<0)Mat4.copy(local,global[j]);
            else Mat4.mul(global[mesh.parent[j]],local,global[j]);
        }
        return global;
    }
    public static void main(String[] args)throws Exception{
        SkeletonMesh mesh=SkeletonMesh.load(new FileInputStream(DATA+"biped.dat"));
        ClipLibrary clips=ClipLibrary.load(new FileInputStream(DATA+"clips.dat"),mesh.jointCount);
        AnimationCatalog catalog=new AnimationCatalog(mesh,clips);
        check(clips.size()==242,"clip count");
        for(String name:clips.names()){
            check(catalog.get(name)!=null,"unregistered "+name);
            Clip clip=clips.get(name);
            for(float time:new float[]{0,clip.duration*.25F,clip.duration*.5F,clip.duration}){
                float[][] global=matrices(mesh,clip,time);
                for(float[] m:global)for(float value:m)check(!Float.isNaN(value)&&!Float.isInfinite(value),"non-finite "+name);
            }
        }
        // Interpolation must advance across a loop boundary, not rewind through it.
        AnimationPlayer player=new AnimationPlayer(clips);
        float duration=clips.get("walk").duration;
        player.play(catalog.get("walk"),duration-.02F);player.tick(.05F,1);
        near(player.interpolatedElapsed(.2F),duration-.01F,"loop before wrap");
        near(player.interpolatedElapsed(.8F),.02F,"loop after wrap");
        JointMask mask=JointMask.basicAttack(mesh);
        for(int j=0;j<mesh.jointCount;j++){
            if(mesh.jointName[j].startsWith("Thigh_")||mesh.jointName[j].startsWith("Leg_")||mesh.jointName[j].startsWith("Knee_"))
                near(mask.weight(j),0,"lower body attack mask");
        }
        boolean record=args.length==1&&"--record-baseline".equals(args[0]);
        if(record){
            check(!new File(GOLDEN).exists(),"Refusing to overwrite golden fixture");
            PrintWriter out=new PrintWriter(new OutputStreamWriter(new FileOutputStream(GOLDEN),"UTF-8"));
            out.println("# baseline ce1d9162003718bc8bdf0f1d94e82e32de1293f8; raw clip global matrices, no DBC sanitization");
            for(String name:CLIPS)for(float time:TIMES){
                float[][] global=matrices(mesh,clips.get(name),time);
                for(int j=0;j<mesh.jointCount;j++){
                    out.print(name+","+time+","+mesh.jointName[j]);
                    for(float value:global[j])out.print(","+Float.toString(value));
                    out.println();
                }
            }
            out.close();System.out.println("RECORDED baseline raw source-pose fixture");
        }
        BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(GOLDEN),"UTF-8"));
        int count=0;String line;Set<String> seen=new HashSet<String>();
        while((line=in.readLine())!=null){
            if(line.startsWith("#"))continue;
            String[] fields=line.split(",");check(fields.length==19,"fixture columns");
            check(seen.add(fields[0]+","+fields[1]+","+fields[2]),"duplicate fixture row");
            int joint=Arrays.asList(mesh.jointName).indexOf(fields[2]);check(joint>=0,"fixture joint");
            float[][] global=matrices(mesh,clips.get(fields[0]),Float.parseFloat(fields[1]));
            for(int i=0;i<16;i++)near(global[joint][i],Float.parseFloat(fields[i+3]),fields[0]+" "+fields[1]+" "+fields[2]+"["+i+"]");
            count++;
        }
        in.close();check(count==CLIPS.length*TIMES.length*mesh.jointCount,"fixture coverage");
        System.out.println("PASS AnimationAudit220Test: 242 registered clips, 968 finite poses, loop wrap, lower-body mask, "+count+" golden joint matrices");
    }
}
