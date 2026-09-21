package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.EpicFight1710;
import com.nicolas.epicfight1710.anim.ClipLibrary;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import java.io.InputStream;

public final class RuntimeAssets {
    /** Armature + modern 64x64 mesh. PoseEngine always uses this armature. */
    public static SkeletonMesh MESH;
    /** Epic Fight's legacy/old-texture mesh, matching the 64x32-era player UV layout. */
    public static SkeletonMesh OLD_MESH;
    public static ClipLibrary CLIPS;
    public static boolean READY;
    private RuntimeAssets() {}

    public static synchronized void load() {
        if (READY) return;
        try {
            InputStream mesh=EpicFight1710.class.getResourceAsStream("/assets/epicfight1710/data/biped.dat");
            InputStream oldMesh=EpicFight1710.class.getResourceAsStream("/assets/epicfight1710/data/biped_old.dat");
            InputStream clips=EpicFight1710.class.getResourceAsStream("/assets/epicfight1710/data/clips.dat");
            if(mesh==null || clips==null) throw new IllegalStateException("Port animation resources are missing");
            MESH=SkeletonMesh.load(mesh);
            OLD_MESH=oldMesh==null?null:SkeletonMesh.load(oldMesh);
            if(OLD_MESH!=null) validateSameArmature(MESH,OLD_MESH);
            CLIPS=ClipLibrary.load(clips,MESH.jointCount);
            READY=true;
            System.out.println("[EpicFight1710] Loaded Epic armature: "+MESH.jointCount+" joints, modern="+MESH.vertexCount+" vertices, legacy="+(OLD_MESH==null?0:OLD_MESH.vertexCount)+" vertices, clips="+CLIPS.size()+".");
        } catch(Throwable t) {
            READY=false;
            throw new RuntimeException("Could not load Epic Fight 1.7.10 runtime assets",t);
        }
    }

    private static void validateSameArmature(SkeletonMesh a,SkeletonMesh b) {
        if(a.jointCount!=b.jointCount) throw new IllegalStateException("Legacy mesh joint count mismatch");
        for(int i=0;i<a.jointCount;i++) {
            if(a.parent[i]!=b.parent[i] || !a.jointName[i].equals(b.jointName[i]))
                throw new IllegalStateException("Legacy mesh armature mismatch at joint "+i);
        }
    }
}
