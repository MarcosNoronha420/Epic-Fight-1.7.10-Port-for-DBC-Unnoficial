package com.nicolas.epicfight1710.anim;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class SkeletonMesh {
    public int jointCount;
    public String[] jointName;
    public int[] parent;
    public float[][] bindLocal;
    public float[][] invBindGlobal;

    public int vertexCount;
    public float[][] positions;
    public int[][] jointIds;
    public float[][] jointWeights;
    public float[][] uvs;
    public float[][] normals;
    public Part[] parts;

    public static SkeletonMesh load(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        if (in.readInt() != 0x45464231) throw new IOException("Bad biped.dat magic");
        int version = in.readInt();
        if (version != 1) throw new IOException("Unsupported biped.dat version " + version);
        SkeletonMesh s = new SkeletonMesh();
        s.jointCount = in.readInt();
        s.jointName = new String[s.jointCount];
        s.parent = new int[s.jointCount];
        s.bindLocal = new float[s.jointCount][16];
        s.invBindGlobal = new float[s.jointCount][16];
        for (int i=0;i<s.jointCount;i++) {
            s.jointName[i]=in.readUTF(); s.parent[i]=in.readInt();
            for (int j=0;j<16;j++) s.bindLocal[i][j]=in.readFloat();
            for (int j=0;j<16;j++) s.invBindGlobal[i][j]=in.readFloat();
        }
        s.vertexCount=in.readInt();
        s.positions=new float[s.vertexCount][3];
        s.jointIds=new int[s.vertexCount][4];
        s.jointWeights=new float[s.vertexCount][4];
        for (int i=0;i<s.vertexCount;i++) {
            for (int j=0;j<3;j++) s.positions[i][j]=in.readFloat();
            for (int j=0;j<4;j++) { s.jointIds[i][j]=in.readInt(); s.jointWeights[i][j]=in.readFloat(); }
        }
        int uvCount=in.readInt(); s.uvs=new float[uvCount][2];
        for (int i=0;i<uvCount;i++) { s.uvs[i][0]=in.readFloat(); s.uvs[i][1]=in.readFloat(); }
        int normalCount=in.readInt(); s.normals=new float[normalCount][3];
        for (int i=0;i<normalCount;i++) for (int j=0;j<3;j++) s.normals[i][j]=in.readFloat();
        int partCount=in.readInt(); s.parts=new Part[partCount];
        for (int p=0;p<partCount;p++) {
            Part part=new Part(); part.name=in.readUTF(); int count=in.readInt(); part.refs=new int[count][3];
            for (int i=0;i<count;i++) for (int j=0;j<3;j++) part.refs[i][j]=in.readInt();
            s.parts[p]=part;
        }
        in.close();
        return s;
    }

    public static final class Part {
        public String name;
        public int[][] refs;
    }
}
